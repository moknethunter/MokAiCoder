import { createHash, randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import { link, lstat, open, realpath, rename, unlink } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative, sep } from 'node:path';
import { Octokit } from '@octokit/rest';
import { RequestError } from '@octokit/request-error';
import { z } from 'zod';
import { AppError } from '../plugins/errorHandler.js';
import { shouldIgnore } from './projectService.js';

export const EDIT_LIMIT = 8_000;
const SOURCE_LIMIT = 64_000;
const textDecoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
const checksum = (text: string | Buffer) =>
  createHash('sha256').update(text).digest('hex');
const changed = () =>
  new AppError(
    409,
    'WORKSPACE_CHANGED',
    'File or base branch changed. Reopen the file.',
  );
const unavailable = () =>
  new AppError(
    404,
    'WORKSPACE_UNAVAILABLE',
    'Workspace or file is unavailable',
  );
const unreadable = () =>
  new AppError(
    422,
    'WORKSPACE_TEXT_REQUIRED',
    'Select a small UTF-8 text file',
  );

export const fileSelection = z.strictObject({
  source: z.enum(['github', 'local']),
  path: z
    .string()
    .min(1)
    .max(240)
    .refine(
      (path) =>
        path
          .split('/')
          .every((part) => /^[a-zA-Z0-9_@+-][a-zA-Z0-9_.@+-]*$/.test(part)) &&
        !shouldIgnore(path) &&
        !/(?:^|\/)(?:credentials?|secrets?|id_rsa|id_dsa|id_ed25519)(?:[._/-]|$)/i.test(
          path,
        ),
      'Expected a supported project file path',
    ),
});
export const editSelection = fileSelection.extend({
  revision: z.string().regex(/^[0-9a-f]{40,64}$|^new$/),
});
export const publication = editSelection.extend({
  content: z
    .string()
    .min(1)
    .max(EDIT_LIMIT)
    .refine((value) => !value.includes('\0')),
});

type Selection = z.infer<typeof fileSelection>;
type Publication = z.infer<typeof publication>;
type Snapshot = {
  content: string;
  revision: string;
  exists: boolean;
  mode?: '100644' | '100755';
};

function decode(bytes: Buffer) {
  if (bytes.length > SOURCE_LIMIT || bytes.includes(0)) throw unreadable();
  try {
    const text = textDecoder.decode(bytes);
    if (text.length > EDIT_LIMIT) throw unreadable();
    return text;
  } catch {
    throw unreadable();
  }
}

export class Workspace {
  private readonly reader: Octokit;
  private readonly writer?: Octokit;
  private readonly owner: string;
  private readonly repo: string;
  private readonly branch: string;
  private readonly root?: string;
  private readonly pending = new Set<string>();

  constructor(env: NodeJS.ProcessEnv) {
    this.reader = new Octokit({
      auth: env.GITHUB_TOKEN || env.MOKAI_GITHUB_WRITE_TOKEN || undefined,
    });
    this.writer = env.MOKAI_GITHUB_WRITE_TOKEN
      ? new Octokit({ auth: env.MOKAI_GITHUB_WRITE_TOKEN })
      : undefined;
    this.owner = env.GITHUB_OWNER || '';
    this.repo = env.GITHUB_REPO || '';
    this.branch = env.GITHUB_BRANCH || 'main';
    this.root = env.MOKAI_WORKSPACE_ROOT || undefined;
    if (this.root && !isAbsolute(this.root))
      throw new Error('MOKAI_WORKSPACE_ROOT must be an absolute directory');
  }

  capabilities() {
    const github =
      this.owner && this.repo
        ? {
            repository: this.owner + '/' + this.repo,
            branch: this.branch,
            publish: Boolean(this.writer),
          }
        : null;
    return { github, local: Boolean(this.root) };
  }

  async open(selection: Selection): Promise<Snapshot> {
    if (selection.source === 'local')
      return (await this.localFile(selection.path)).snapshot;
    try {
      return (await this.githubFile(selection.path, this.reader)).snapshot;
    } catch (error) {
      throw this.githubError(error);
    }
  }

  async publish(input: Publication) {
    if (input.source === 'github' && !this.writer)
      throw new AppError(
        503,
        'WORKSPACE_WRITE_DISABLED',
        'Configure a GitHub publishing credential',
      );
    const key = input.source + '/' + input.path;
    if (this.pending.has(key))
      throw new AppError(
        409,
        'WORKSPACE_BUSY',
        'A save is in progress for this file',
      );
    this.pending.add(key);
    try {
      if (input.source === 'local') return await this.saveLocal(input);
      return await this.publishGithub(input, this.writer!);
    } finally {
      this.pending.delete(key);
    }
  }

  private async localFile(path: string) {
    if (!this.root) throw unavailable();
    try {
      const root = await realpath(this.root);
      const target = join(root, path);
      const parent = dirname(target);
      const actualParent = await realpath(parent);
      const fromRoot = relative(root, actualParent);
      if (
        actualParent !== parent ||
        fromRoot === '..' ||
        fromRoot.startsWith('..' + sep) ||
        isAbsolute(fromRoot)
      )
        throw unavailable();
      const info = await lstat(target, { throwIfNoEntry: false });
      if (!info)
        return {
          target,
          snapshot: {
            content: '',
            revision: 'new',
            exists: false,
          } satisfies Snapshot,
          mode: 0o644,
        };
      if (!info.isFile() || info.nlink !== 1 || info.size > SOURCE_LIMIT)
        throw unavailable();
      const file = await open(
        target,
        constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK,
      );
      try {
        const actual = await file.stat();
        if (actual.ino !== info.ino || actual.dev !== info.dev) throw changed();
        const bytes = Buffer.alloc(SOURCE_LIMIT + 1);
        let bytesRead = 0;
        while (bytesRead < bytes.length) {
          const chunk = await file.read(
            bytes,
            bytesRead,
            bytes.length - bytesRead,
            bytesRead,
          );
          if (!chunk.bytesRead) break;
          bytesRead += chunk.bytesRead;
        }
        const contents = bytes.subarray(0, bytesRead);
        const snapshot = {
          content: decode(contents),
          revision: checksum(contents),
          exists: true,
        };
        return { target, snapshot, mode: actual.mode & 0o777 };
      } finally {
        await file.close();
      }
    } catch (error) {
      if (error instanceof AppError) throw error;
      throw unavailable();
    }
  }

  private async saveLocal(input: Publication) {
    const { target, snapshot, mode } = await this.localFile(input.path);
    this.verify(input, snapshot);
    const temporary = join(dirname(target), '.mokai-' + randomUUID());
    try {
      const file = await open(temporary, 'wx', mode);
      try {
        await file.writeFile(input.content, 'utf8');
        await file.sync();
      } finally {
        await file.close();
      }
      const latest = await this.localFile(input.path);
      if (
        latest.target !== target ||
        latest.snapshot.revision !== input.revision
      )
        throw changed();
      if (snapshot.exists) await rename(temporary, target);
      else await link(temporary, target);
      return { source: 'local', path: input.path } as const;
    } catch (error) {
      if (error instanceof AppError) throw error;
      if (error instanceof Error && 'code' in error && error.code === 'EEXIST')
        throw changed();
      throw new AppError(
        502,
        'WORKSPACE_SAVE_FAILED',
        'Could not save the workspace file',
      );
    } finally {
      await unlink(temporary).catch(() => {});
    }
  }

  private async githubFile(path: string, github: Octokit) {
    if (!this.owner || !this.repo) throw unavailable();
    const request = {
      owner: this.owner,
      repo: this.repo,
      request: { signal: AbortSignal.timeout(15_000) },
    };
    const { data: ref } = await github.git.getRef({
      ...request,
      ref: 'heads/' + this.branch,
    });
    const { data: head } = await github.git.getCommit({
      ...request,
      commit_sha: ref.object.sha,
    });
    const base = head.tree.sha;
    let tree = base;
    const segments = path.split('/');
    for (const [index, segment] of segments.entries()) {
      const { data } = await github.git.getTree({ ...request, tree_sha: tree });
      if (data.truncated) throw unavailable();
      const entry = data.tree.find((item) => item.path === segment);
      if (!entry)
        return {
          request,
          head,
          snapshot: {
            exists: false,
            content: '',
            revision: head.sha,
          } satisfies Snapshot,
        };
      if (index < segments.length - 1) {
        if (entry.type !== 'tree' || !entry.sha) throw unavailable();
        tree = entry.sha;
        continue;
      }
      if (
        entry.type !== 'blob' ||
        !entry.sha ||
        (entry.mode !== '100644' && entry.mode !== '100755')
      )
        throw unavailable();
      if (entry.size === undefined || entry.size > SOURCE_LIMIT)
        throw unreadable();
      const { data: blob } = await github.git.getBlob({
        ...request,
        file_sha: entry.sha,
      });
      if (blob.encoding !== 'base64') throw unreadable();
      const content = decode(Buffer.from(blob.content, 'base64'));
      return {
        request,
        head,
        snapshot: {
          content,
          exists: true,
          revision: head.sha,
          mode: entry.mode,
        } satisfies Snapshot,
      };
    }
    throw unavailable();
  }

  private async publishGithub(input: Publication, github: Octokit) {
    try {
      const { request, head, snapshot } = await this.githubFile(
        input.path,
        github,
      );
      this.verify(input, snapshot);
      const mode = 'mode' in snapshot ? snapshot.mode : '100644';
      const { data: tree } = await github.git.createTree({
        ...request,
        base_tree: head.tree.sha,
        tree: [
          { path: input.path, mode, type: 'blob', content: input.content },
        ],
      });
      const { data: commit } = await github.git.createCommit({
        ...request,
        message: 'MokAiCoder: update ' + input.path,
        tree: tree.sha,
        parents: [head.sha],
      });
      const branch =
        'mokai/edit-' + checksum(head.sha + ':' + tree.sha).slice(0, 20);
      try {
        await github.git.createRef({
          ...request,
          ref: 'refs/heads/' + branch,
          sha: commit.sha,
        });
      } catch (error) {
        if (
          !(error instanceof RequestError) ||
          ![409, 422].includes(error.status)
        )
          throw error;
        const { data: existing } = await github.git.getRef({
          ...request,
          ref: 'heads/' + branch,
        });
        const { data: saved } = await github.git.getCommit({
          ...request,
          commit_sha: existing.object.sha,
        });
        if (saved.tree.sha !== tree.sha || saved.parents[0]?.sha !== head.sha)
          throw error;
      }
      const repository =
        'https://github.com/' +
        encodeURIComponent(this.owner) +
        '/' +
        encodeURIComponent(this.repo);
      return {
        source: 'github',
        branch,
        compareUrl:
          repository +
          '/compare/' +
          encodeURIComponent(this.branch) +
          '...' +
          encodeURIComponent(branch) +
          '?expand=1',
      } as const;
    } catch (error) {
      throw this.githubError(error);
    }
  }

  private verify(input: Publication, snapshot: Snapshot) {
    if (input.revision !== snapshot.revision) throw changed();
    if (input.content === snapshot.content)
      throw new AppError(
        400,
        'WORKSPACE_NO_CHANGE',
        'The file content has not changed',
      );
  }

  private githubError(error: unknown) {
    if (error instanceof AppError) return error;
    if (error instanceof RequestError) {
      if (error.status === 404) return unavailable();
      if ([401, 403].includes(error.status))
        return new AppError(
          502,
          'WORKSPACE_GITHUB_AUTH',
          'GitHub rejected the configured credentials or permissions',
        );
    }
    return new AppError(
      502,
      'WORKSPACE_GITHUB_ERROR',
      'GitHub could not complete the workspace operation',
    );
  }
}
