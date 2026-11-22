import Fastify from 'fastify';
import cors from '@fastify/cors';
import fastifyStatic from '@fastify/static';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { ProjectService } from './services/projectService.js';
import { DependencyService } from './services/dependencyService.js';
import { ProjectCache } from './services/cache.js';
import type { ScanRequest, ScanResponse } from './types.js';

const __dirname = dirname(fileURLToPath(import.meta.url));

export async function createApp({ logger = true } = {}) {
  const app = Fastify({ logger });

  await app.register(cors, { origin: true });

  const webRoot = join(__dirname, '..', 'web');
  await app.register(fastifyStatic, {
    root: webRoot,
    prefix: '/',
    wildcard: false,
  });

  const projectService = new ProjectService();
  const dependencyService = new DependencyService();
  const cache = new ProjectCache();

  app.get('/health', async () => ({
    status: 'ok',
    time: new Date().toISOString(),
    service: 'MokhtarAiCoder',
    version: '1.1.0',
  }));

  app.post<{ Body: ScanRequest }>('/api/project/scan', async (req, reply) => {
    try {
      const files = await projectService.loadAllFiles(req.body?.branch);
      const graph = dependencyService.buildGraph(files);

      cache.files = files;
      cache.graph = graph;
      cache.scannedAt = new Date();
      cache.branch = req.body?.branch || '';

      const response: ScanResponse = {
        fileCount: files.length,
        edgeCount: Object.values(graph.edges).reduce((s, v) => s + v.length, 0),
        cycleCount: graph.cycles.length,
        orphanCount: graph.orphans.length,
        duplicateCount: Object.keys(graph.duplicateSymbols).length,
        totalBytes: files.reduce((s, f) => s + f.size, 0),
        graph,
        files: files.map(f => f.path),
      };
      return response;
    } catch (err) {
      reply.code(500);
      return { error: (err as Error).message };
    }
  });

  app.get('/api/project/tree', async (_req, reply) => {
    if (cache.isEmpty) {
      reply.code(409);
      return { error: 'Run scan first' };
    }
    return cache.files.map(f => ({ path: f.path, size: f.size }));
  });

  app.get('/api/project/graph', async (_req, reply) => {
    if (!cache.graph) {
      reply.code(409);
      return { error: 'Run scan first' };
    }
    return cache.graph;
  });

  app.get<{ Querystring: { path: string } }>(
    '/api/project/file',
    async (req, reply) => {
      const file = cache.files.find(f => f.path === req.query.path);
      if (!file) {
        reply.code(404);
        return { error: `File ${req.query.path} not in cache` };
      }
      return { path: file.path, content: file.content, size: file.size };
    }
  );

  app.setNotFoundHandler((req, reply) => {
    if (req.url.startsWith('/api/')) {
      reply.code(404);
      return { error: 'Not found' };
    }
    return reply.sendFile('index.html');
  });

  return app;
}
