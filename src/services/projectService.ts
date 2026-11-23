import { Octokit } from '@octokit/rest';
import { RequestError } from '@octokit/request-error';
import { AppError } from '../plugins/errorHandler.js';
import type { ProjectFile } from '../types.js';

const IGNORE_DIRS = new Set([
  'node_modules',
  'bin',
  'obj',
  '.git',
  '.idea',
  'build',
  'dist',
  '.gradle',
  '.vs',
  'vendor',
  '.next',
  'coverage',
  '.dart_tool',
  '.ssh',
  '.aws',
]);

const IGNORE_EXTS = [
  '.png',
  '.jpg',
  '.jpeg',
  '.gif',
  '.ico',
  '.svg',
  '.webp',
  '.dll',
  '.exe',
  '.so',
  '.dylib',
  '.zip',
  '.tar',
  '.gz',
  '.jar',
  '.apk',
  '.aab',
  '.lock',
  '.map',
  '.pdf',
  '.woff',
  '.woff2',
  '.ttf',
  '.eot',
  '.mp3',
  '.mp4',
  '.pem',
  '.key',
  '.p12',
  '.pfx',
  '.jks',
];

const IGNORED_FILES = new Set([
  'package-lock.json',
  'yarn.lock',
  'pnpm-lock.yaml',
  'poetry.lock',
  'cargo.lock',
  'packages.lock.json',
  '.env',
  '.npmrc',
  '.netrc',
  '.git-credentials',
  '.keystore',
  'secrets.json',
  'appsettings.json',
  'google-services.json',
  'id_rsa',
  'id_dsa',
  'id_ed25519',
  'id_ecdsa',
]);

const MAX_FILE_BYTES = 500_000;
const MAX_PROJECT_BYTES = 25_000_000;
const MAX_FILES = 1000;
const SCAN_TIMEOUT_MS = 60_000;
const BATCH_SIZE = 10;
const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });

export function isRepositoryPath(path: string): boolean {
  return (
    path.length <= 4096 &&
    !Array.from(path).some(
      (char) => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127,
    ) &&
    path
      .split('/')
      .every((part) => part !== '' && part !== '.' && part !== '..')
  );
}

export function shouldIgnore(path: string): boolean {
  const parts = path.toLowerCase().split('/');
  const filename = parts.pop() ?? '';
  return (
    parts.some((part) => IGNORE_DIRS.has(part)) ||
    IGNORE_EXTS.some((ext) => filename.endsWith(ext)) ||
    IGNORED_FILES.has(filename) ||
    filename.startsWith('.env.') ||
    /^appsettings\..+\.json$/.test(filename)
  );
}

function githubError(error: unknown, signal: AbortSignal): AppError {
  if (signal.aborted) {
    return new AppError(
      504,
      'GITHUB_TIMEOUT',
      'GitHub did not finish the scan in time',
    );
  }
  if (error instanceof RequestError) {
    const headers = error.response?.headers;
    const retryAfter = String(headers?.['retry-after'] ?? '');
    const reset = String(headers?.['x-ratelimit-reset'] ?? '');
    const exhausted = String(headers?.['x-ratelimit-remaining']) === '0';
    const limited =
      exhausted || retryAfter || /rate limit/i.test(error.message);
    if (error.status === 429 || (error.status === 403 && limited)) {
      const wait =
        retryAfter && /^\d{1,10}$/.test(retryAfter)
          ? retryAfter
          : exhausted && reset && /^\d{1,10}$/.test(reset)
            ? String(Math.max(0, Math.ceil(Number(reset) - Date.now() / 1000)))
            : undefined;
      return new AppError(
        429,
        'GITHUB_RATE_LIMIT',
        'GitHub rate limit reached',
        wait,
      );
    }
    if (error.status === 404) {
      return new AppError(
        404,
        'GITHUB_NOT_FOUND',
        'Repository, ref, or file was not available on GitHub',
      );
    }
  }
  return new AppError(
    502,
    'GITHUB_UNAVAILABLE',
    'GitHub could not complete the scan',
  );
}

export class ProjectService {
  private readonly octokit: Octokit;
  private readonly owner: string;
  private readonly repo: string;
  private readonly branch: string;

  constructor(env = process.env) {
    const token = env.GITHUB_TOKEN || env.MOKAI_GITHUB_WRITE_TOKEN;
    this.owner = env.GITHUB_OWNER || '';
    this.repo = env.GITHUB_REPO || '';
    this.branch = env.GITHUB_BRANCH || 'main';
    if (!this.owner) throw new Error('GITHUB_OWNER is required');
    if (!this.repo) throw new Error('GITHUB_REPO is required');

    this.octokit = new Octokit({ auth: token || undefined });
  }

  async loadAllFiles(
    branch = this.branch,
    cancellation?: AbortSignal,
  ): Promise<ProjectFile[]> {
    const timeout = AbortSignal.timeout(SCAN_TIMEOUT_MS);
    const signal = cancellation
      ? AbortSignal.any([timeout, cancellation])
      : timeout;
    try {
      const { data: tree } = await this.octokit.git.getTree({
        owner: this.owner,
        repo: this.repo,
        tree_sha: branch,
        recursive: '1',
        request: { signal },
      });

      const items = tree.tree.flatMap(({ type, mode, path, sha, size }) =>
        type === 'blob' &&
        mode !== '120000' &&
        path &&
        sha &&
        size != null &&
        size >= 0 &&
        size < MAX_FILE_BYTES &&
        isRepositoryPath(path) &&
        !shouldIgnore(path)
          ? [{ path, sha, size }]
          : [],
      );
      const totalBytes = items.reduce((total, item) => total + item.size, 0);
      if (
        tree.truncated ||
        items.length > MAX_FILES ||
        totalBytes > MAX_PROJECT_BYTES
      ) {
        throw new AppError(
          422,
          'PROJECT_TOO_LARGE',
          'Repository exceeds the scan limits',
        );
      }

      const files: ProjectFile[] = [];
      for (let offset = 0; offset < items.length; offset += BATCH_SIZE) {
        const batch = items.slice(offset, offset + BATCH_SIZE);
        const loaded = await Promise.allSettled(
          batch.map(({ path, sha }) => this.loadBlob(path, sha, signal)),
        );
        for (const result of loaded) {
          if (result.status === 'rejected') throw result.reason;
          if (result.value !== null) files.push(result.value);
        }
      }
      return files;
    } catch (error) {
      if (error instanceof AppError) throw error;
      throw githubError(error, signal);
    }
  }

  private async loadBlob(
    path: string,
    sha: string,
    signal: AbortSignal,
  ): Promise<ProjectFile | null> {
    const { data: blob } = await this.octokit.git.getBlob({
      owner: this.owner,
      repo: this.repo,
      file_sha: sha,
      request: { signal },
    });
    const bytes = Buffer.from(
      blob.content,
      blob.encoding === 'base64' ? 'base64' : 'utf8',
    );
    if (bytes.includes(0)) return null;

    let content: string;
    try {
      content = decoder.decode(bytes);
    } catch {
      return null;
    }
    return { path, content, size: bytes.length };
  }
}
