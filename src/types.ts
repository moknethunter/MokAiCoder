
import { Octokit } from '@octokit/rest';
import { ProjectFile } from '../types.js';

const IGNORE_DIRS = [
  'node_modules/', '/bin/', '/obj/', '.git/', '.idea/', '/build/',
  '/dist/', '/.gradle/', '/.vs/', '/packages/', '/vendor/', '/.next/',
  '/coverage/', '/.dart_tool/'
];

const IGNORE_EXTS = [
  '.png', '.jpg', '.jpeg', '.gif', '.ico', '.svg', '.webp',
  '.dll', '.exe', '.so', '.dylib', '.zip', '.tar', '.gz',
  '.jar', '.apk', '.aab', '.lock', '.map', '.pdf',
  '.woff', '.woff2', '.ttf', '.eot', '.mp3', '.mp4'
];

const LOCK_FILES = [
  'package-lock.json', 'yarn.lock', 'pnpm-lock.yaml',
  'poetry.lock', 'cargo.lock', 'packages.lock.json'
];

const FORBIDDEN = [
  '.env', '.env.local', 'secrets.json', 'appsettings.json',
  'appsettings.production.json', '.keystore', 'google-services.json'
];

export function shouldIgnore(path: string): boolean {
  const lower = path.toLowerCase();
  if (IGNORE_DIRS.some(d => lower.includes(d))) return true;
  if (IGNORE_EXTS.some(e => lower.endsWith(e))) return true;
  if (LOCK_FILES.some(l => lower.endsWith(l))) return true;
  if (FORBIDDEN.some(f => lower.endsWith(f))) return true;
  return false;
}

export class ProjectService {
  private octokit: Octokit;
  private owner: string;
  private repo: string;
  private branch: string;

  constructor() {
    const token = process.env.GITHUB_TOKEN;
    this.owner = process.env.GITHUB_OWNER || '';
    this.repo = process.env.GITHUB_REPO || '';
    this.branch = process.env.GITHUB_BRANCH || 'main';

    if (!token) throw new Error('GITHUB_TOKEN is required');
    if (!this.owner) throw new Error('GITHUB_OWNER is required');
    if (!this.repo) throw new Error('GITHUB_REPO is required');

    this.octokit = new Octokit({ auth: token });
  }

  async loadAllFiles(branch?: string): Promise<ProjectFile[]> {
    const targetBranch = branch || this.branch;
    console.log(`[project] fetching ${this.owner}/${this.repo}@${targetBranch}`);

    const { data: tree } = await this.octokit.git.getTree({
      owner: this.owner,
      repo: this.repo,
      tree_sha: targetBranch,
      recursive: '1'
    });

    const items = tree.tree.filter(
      t =>
        t.type === 'blob' &&
        t.path &&
        t.sha &&
        !shouldIgnore(t.path) &&
        (t.size || 0) > 0 &&
        (t.size || 0) < 500_000
    );

    console.log(`[project] filtered ${items.length}/${tree.tree.length} files`);

    const files: ProjectFile[] = [];
    const BATCH = 10;
    for (let i = 0; i < items.length; i += BATCH) {
      const batch = items.slice(i, i + BATCH);
      const loaded = await Promise.all(
        batch.map(item => this.loadBlob(item.path!, item.sha!))
      );
      for (const f of loaded) if (f) files.push(f);
    }
    return files;
  }

  private async loadBlob(path: string, sha: string): Promise<ProjectFile | null> {
    try {
      const { data: blob } = await this.octokit.git.getBlob({
        owner: this.owner,
        repo: this.repo,
        file_sha: sha
      });
      const content =
        blob.encoding === 'base64'
          ? Buffer.from(blob.content, 'base64').toString('utf-8')
          : blob.content;
      return { path, content, size: blob.size ?? content.length };
    } catch (err) {
      console.warn(`[project] failed ${path}:`, (err as Error).message);
      return null;
    }
  }
}
