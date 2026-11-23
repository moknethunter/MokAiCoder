import Fastify from 'fastify';
import cors from '@fastify/cors';
import fastifyStatic from '@fastify/static';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';
import { registerAi } from './ai/routes.js';
import { accessGuard } from './auth.js';
import { Workspace } from './services/workspace.js';
import { AppError, registerErrorHandler } from './plugins/errorHandler.js';
import { isRepositoryPath, ProjectService } from './services/projectService.js';
import { DependencyService } from './services/dependencyService.js';
import { ProjectCache } from './services/cache.js';
import type { ScanRequest, ScanResponse } from './types.js';

const webRoot = fileURLToPath(new URL('../web/', import.meta.url));
const { version } = createRequire(import.meta.url)('../package.json') as {
  version: string;
};
const scanSchema: z.ZodType<ScanRequest> = z.object({
  branch: z
    .string()
    .min(1)
    .max(100)
    .refine(
      (ref) =>
        isRepositoryPath(ref) &&
        !/[ ~^:?*[\\]/.test(ref) &&
        !ref.includes('..') &&
        !ref.includes('@{') &&
        ref !== '@' &&
        !ref.endsWith('.') &&
        ref
          .split('/')
          .every((part) => !part.startsWith('.') && !part.endsWith('.lock')),
      'Expected a valid Git ref',
    )
    .optional(),
});
const fileQuerySchema = z.object({
  path: z
    .string()
    .refine(isRepositoryPath, 'Expected a repository-relative path'),
  scanId: z.uuid().optional(),
});

export async function createApp({ logger = true } = {}) {
  const app = Fastify({
    logger: logger
      ? { redact: ['req.headers.authorization', 'req.headers.cookie'] }
      : false,
    bodyLimit: 16 * 1024,
  });

  registerErrorHandler(app);
  const allowedOrigin = process.env.CORS_ORIGIN?.trim();
  await app.register(cors, {
    origin: allowedOrigin ? [allowedOrigin] : false,
    exposedHeaders: ['X-Scan-Id', 'Retry-After'],
  });
  await app.register(fastifyStatic, {
    root: webRoot,
    prefix: '/',
    wildcard: false,
  });

  let projectService: ProjectService | undefined;
  const dependencyService = new DependencyService();
  const cache = new ProjectCache();
  let scanning = false;

  app.addHook('onRequest', async (req, reply) => {
    if (req.url.startsWith('/api/')) reply.header('Cache-Control', 'no-store');
  });

  app.get('/health', async () => ({
    status: 'ok',
    time: new Date().toISOString(),
    service: 'MokhtarAiCoder',
    version,
  }));

  registerAi(app, cache, new Workspace(process.env));
  const projectAccess = process.env.GITHUB_TOKEN
    ? { onRequest: accessGuard(process.env.MOKAI_CHAT_TOKEN) }
    : {};
  app.get('/chat', async (_req, reply) => reply.sendFile('chat.html'));
  app.get('/project', async (_req, reply) => reply.sendFile('project.html'));

  app.post('/api/project/scan', projectAccess, async (req, reply) => {
    const { branch } = scanSchema.parse(req.body === undefined ? {} : req.body);
    if (!process.env.GITHUB_OWNER || !process.env.GITHUB_REPO) {
      throw new AppError(
        503,
        'GITHUB_SETUP_REQUIRED',
        'Configure the GitHub owner and repository',
      );
    }
    if (scanning)
      throw new AppError(409, 'SCAN_IN_PROGRESS', 'A scan is already running');
    scanning = true;

    try {
      projectService ??= new ProjectService();
      const files = await projectService.loadAllFiles(branch);
      const graph = dependencyService.buildGraph(files);
      const response: ScanResponse = {
        fileCount: files.length,
        edgeCount: Object.values(graph.edges).reduce(
          (count, dependencies) => count + dependencies.length,
          0,
        ),
        cycleCount: graph.cycles.length,
        orphanCount: graph.orphans.length,
        duplicateCount: Object.keys(graph.duplicateSymbols).length,
        totalBytes: files.reduce((size, file) => size + file.size, 0),
        graph,
        files: files.map((file) => file.path),
      };

      reply.header('X-Scan-Id', cache.set(files, graph));
      return response;
    } finally {
      scanning = false;
    }
  });

  app.get('/api/project/tree', projectAccess, async (_req, reply) => {
    const snapshot = cache.current;
    if (!snapshot) throw new AppError(409, 'SCAN_REQUIRED', 'Run scan first');
    reply.header('X-Scan-Id', snapshot.id);
    return Array.from(snapshot.files.values(), ({ path, size }) => ({
      path,
      size,
    }));
  });

  app.get('/api/project/graph', projectAccess, async () => {
    const snapshot = cache.current;
    if (!snapshot) throw new AppError(409, 'SCAN_REQUIRED', 'Run scan first');
    return snapshot.graph;
  });

  app.get('/api/project/file', projectAccess, async (req) => {
    const { path, scanId } = fileQuerySchema.parse(req.query);
    const snapshot = cache.current;
    if (scanId && snapshot?.id !== scanId) {
      throw new AppError(
        409,
        'SCAN_CHANGED',
        'The scan result has changed or expired',
      );
    }
    const file = snapshot?.files.get(path);
    if (!file) throw new AppError(404, 'FILE_NOT_FOUND', 'File not in cache');
    return { path: file.path, content: file.content, size: file.size };
  });

  app.setNotFoundHandler((req, reply) => {
    if (req.method === 'GET' && req.url.split('?')[0] === '/')
      return reply.sendFile('index.html');
    throw new AppError(404, 'NOT_FOUND', 'Not found');
  });

  return app;
}
