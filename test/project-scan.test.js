import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createApp } from '../src/app.ts';

process.env.GITHUB_TOKEN = 'fixture-token';
process.env.GITHUB_OWNER = 'fixture-owner';
process.env.GITHUB_REPO = 'fixture-repo';
process.env.GITHUB_BRANCH = 'fixture-main';

test('serves the existing Arabic web app and health endpoint', async (t) => {
  const app = await createApp({ logger: false });
  t.after(() => app.close());

  const health = await app.inject('/health');
  assert.equal(health.statusCode, 200);
  assert.equal(health.json().status, 'ok');
  assert.equal(health.json().version, '1.2.0');

  const homepage = await app.inject('/');
  assert.equal(homepage.statusCode, 200);
  assert.match(homepage.body, /lang="ar" dir="rtl"/);

  const script = await app.inject('/app.js');
  assert.equal(script.statusCode, 200);
  assert.match(script.body, /async function scanProject/);
  const stylesheet = await app.inject('/style.css');
  assert.equal(stylesheet.statusCode, 200);
  assert.match(stylesheet.headers['content-type'], /^text\/css/);

  const missingApi = await app.inject('/api/missing');
  assert.equal(missingApi.statusCode, 404);
  assert.deepEqual(missingApi.json(), {
    error: 'Not found',
    code: 'NOT_FOUND',
  });
});

test('scans GitHub files and preserves the project API response and cache', async (t) => {
  const sourceFiles = {
    'src/index.ts': "import { helper } from './helper';",
    'src/helper.ts': "export const helper = 'أهلاً';",
    'scripts/report.py': 'print("report")',
  };
  const blobs = new Map(
    Object.entries(sourceFiles).map(([path, content], index) => [
      `blob-${index}`,
      { path, content, size: Buffer.byteLength(content) },
    ]),
  );
  const tree = Array.from(blobs, ([sha, { path, size }]) => ({
    path,
    size,
    sha,
    type: 'blob',
  }));
  tree.push(
    { path: '.env', size: 64, sha: 'blocked-secret', type: 'blob' },
    { path: 'assets/icon.png', size: 64, sha: 'blocked-image', type: 'blob' },
    { path: 'large.txt', size: 500_000, sha: 'blocked-large', type: 'blob' },
    {
      path: 'configs/.env.production',
      size: 64,
      sha: 'blocked-env',
      type: 'blob',
    },
    {
      path: 'symlink',
      size: 6,
      sha: 'blocked-link',
      type: 'blob',
      mode: '120000',
    },
    { path: 'data.bin', size: 2, sha: 'binary', type: 'blob' },
  );

  const requests = [];
  t.mock.method(globalThis, 'fetch', async (input) => {
    const url = new URL(input);
    requests.push(url.pathname);
    assert.equal(url.origin, 'https://api.github.com');

    const gitPath = '/repos/fixture-owner/fixture-repo/git';
    if (
      url.pathname === `${gitPath}/trees/release` ||
      url.pathname === `${gitPath}/trees/fixture-main`
    ) {
      assert.equal(url.searchParams.get('recursive'), '1');
      return Response.json({ tree });
    }
    if (url.pathname === `${gitPath}/trees/unknown`) {
      return Response.json({ message: 'Not Found' }, { status: 404 });
    }
    if (url.pathname === `${gitPath}/trees/bad-blob`) {
      return Response.json({
        tree: [{ path: 'bad.ts', sha: 'bad-blob', size: 10, type: 'blob' }],
      });
    }
    if (url.pathname === `${gitPath}/blobs/bad-blob`) {
      return Response.json(
        { message: 'Sensitive upstream details' },
        { status: 500 },
      );
    }
    if (url.pathname === `${gitPath}/blobs/binary`) {
      return Response.json({ encoding: 'base64', content: '/wA=', size: 2 });
    }
    const blob = blobs.get(url.pathname.slice(`${gitPath}/blobs/`.length));
    assert.ok(
      url.pathname.startsWith(`${gitPath}/blobs/`) && blob,
      `Unexpected request: ${url}`,
    );
    return Response.json({
      encoding: 'base64',
      content: Buffer.from(blob.content).toString('base64'),
      size: blob.path === 'src/helper.ts' ? null : blob.size,
    });
  });

  const app = await createApp({ logger: false });
  t.after(() => app.close());

  const treeBeforeScan = await app.inject('/api/project/tree');
  assert.equal(treeBeforeScan.statusCode, 409);
  assert.equal((await app.inject('/api/project/graph')).statusCode, 409);

  const scan = await app.inject({
    method: 'POST',
    url: '/api/project/scan',
    payload: { branch: 'release' },
  });
  assert.equal(scan.statusCode, 200, scan.body);
  assert.equal(scan.headers['cache-control'], 'no-store');
  assert.ok(scan.headers['x-scan-id']);
  const graph = {
    edges: {
      'src/index.ts': ['src/helper.ts'],
      'src/helper.ts': [],
      'scripts/report.py': [],
    },
    cycles: [],
    orphans: ['scripts/report.py'],
    duplicateSymbols: {},
    missing: {},
  };
  assert.deepEqual(scan.json(), {
    fileCount: 3,
    edgeCount: 1,
    cycleCount: 0,
    orphanCount: 1,
    duplicateCount: 0,
    totalBytes: Array.from(blobs.values()).reduce(
      (sum, { size }) => sum + size,
      0,
    ),
    graph,
    files: Object.keys(sourceFiles),
  });

  const defaultScan = await app.inject({
    method: 'POST',
    url: '/api/project/scan',
  });
  assert.equal(defaultScan.statusCode, 200, defaultScan.body);
  assert.deepEqual(defaultScan.json(), scan.json());
  assert.deepEqual(
    requests
      .filter((path) => path.includes('/trees/'))
      .map((path) => path.split('/').at(-1)),
    ['release', 'fixture-main'],
  );
  assert.equal(requests.length, 10);
  const staleFile = await app.inject({
    url: '/api/project/file',
    query: { path: 'src/helper.ts', scanId: scan.headers['x-scan-id'] },
  });
  assert.equal(staleFile.statusCode, 409);
  assert.equal(staleFile.json().code, 'SCAN_CHANGED');

  const failedScan = await app.inject({
    method: 'POST',
    url: '/api/project/scan',
    payload: { branch: 'unknown' },
  });
  assert.equal(failedScan.statusCode, 404);
  assert.equal(failedScan.json().code, 'GITHUB_NOT_FOUND');

  const partialScan = await app.inject({
    method: 'POST',
    url: '/api/project/scan',
    payload: { branch: 'bad-blob' },
  });
  assert.equal(partialScan.statusCode, 502);
  assert.equal(partialScan.json().code, 'GITHUB_UNAVAILABLE');
  assert.doesNotMatch(partialScan.body, /Sensitive upstream details/);

  const cachedTree = await app.inject('/api/project/tree');
  assert.equal(cachedTree.statusCode, 200);
  assert.deepEqual(
    cachedTree.json(),
    Array.from(blobs.values(), ({ path, size }) => ({ path, size })),
  );
  assert.deepEqual((await app.inject('/api/project/graph')).json(), graph);

  const cachedFile = await app.inject('/api/project/file?path=src%2Fhelper.ts');
  assert.equal(cachedFile.statusCode, 200);
  assert.deepEqual(cachedFile.json(), blobs.get('blob-1'));

  const blocked = await app.inject('/api/project/file?path=.env');
  assert.equal(blocked.statusCode, 404);
});

test('rejects malformed requests without fetching GitHub', async (t) => {
  const fetch = t.mock.method(globalThis, 'fetch', async () =>
    assert.fail('Unexpected GitHub request'),
  );
  const app = await createApp({ logger: false });
  t.after(() => app.close());

  for (const branch of ['', 5, '../main', 'has spaces', 'a'.repeat(101)]) {
    const invalid = await app.inject({
      method: 'POST',
      url: '/api/project/scan',
      payload: { branch },
    });
    assert.equal(invalid.statusCode, 400);
    assert.equal(invalid.json().code, 'INVALID_REQUEST');
  }

  const invalidJson = await app.inject({
    method: 'POST',
    url: '/api/project/scan',
    headers: { 'content-type': 'application/json' },
    payload: '{',
  });
  assert.equal(invalidJson.statusCode, 400);
  assert.equal(invalidJson.json().code, 'INVALID_REQUEST');

  for (const path of ['', '/src/a.ts', 'src/../a.ts', 'src//a.ts']) {
    const invalid = await app.inject({
      url: '/api/project/file',
      query: { path },
    });
    assert.equal(invalid.statusCode, 400);
    assert.equal(invalid.json().code, 'INVALID_REQUEST');
  }
  assert.equal((await app.inject('/api/project/file')).statusCode, 400);
  assert.equal(fetch.mock.callCount(), 0);
});

test('only permits configured cross-origin requests', async (t) => {
  t.after(() => delete process.env.CORS_ORIGIN);
  delete process.env.CORS_ORIGIN;
  const defaultApp = await createApp({ logger: false });
  t.after(() => defaultApp.close());
  const denied = await defaultApp.inject({
    url: '/health',
    headers: { origin: 'https://another.example' },
  });
  assert.equal(denied.headers['access-control-allow-origin'], undefined);

  process.env.CORS_ORIGIN = 'https://scanner.example';
  const configured = await createApp({ logger: false });
  t.after(() => configured.close());
  const ok = await configured.inject({
    url: '/health',
    headers: { origin: 'https://scanner.example' },
  });
  assert.equal(
    ok.headers['access-control-allow-origin'],
    'https://scanner.example',
  );
  const foreign = await configured.inject({
    url: '/health',
    headers: { origin: 'https://another.example' },
  });
  assert.equal(foreign.headers['access-control-allow-origin'], undefined);
});

test('deduplicates simultaneous scans and publishes a valid empty snapshot', async (t) => {
  const pending = Promise.withResolvers();
  const started = Promise.withResolvers();
  t.mock.method(globalThis, 'fetch', async () => {
    started.resolve();
    return pending.promise;
  });
  const app = await createApp({ logger: false });
  t.after(() => app.close());

  const initial = app.inject({ method: 'POST', url: '/api/project/scan' });
  try {
    await started.promise;
    const concurrent = await app.inject({
      method: 'POST',
      url: '/api/project/scan',
    });
    assert.equal(concurrent.statusCode, 409);
    assert.equal(concurrent.json().code, 'SCAN_IN_PROGRESS');
  } finally {
    pending.resolve(Response.json({ tree: [] }));
  }
  const scanned = await initial;
  assert.equal(scanned.statusCode, 200);
  assert.equal(scanned.json().fileCount, 0);

  const tree = await app.inject('/api/project/tree');
  assert.equal(tree.statusCode, 200);
  assert.deepEqual(tree.json(), []);
  assert.equal((await app.inject('/api/project/graph')).statusCode, 200);
});

test('returns rate-limit timing and rejects truncated GitHub trees', async (t) => {
  t.mock.method(globalThis, 'fetch', async (input) => {
    if (new URL(input).pathname.endsWith('/limited')) {
      return Response.json(
        { message: 'Rate limit exceeded' },
        {
          status: 403,
          headers: { 'retry-after': '30' },
        },
      );
    }
    return Response.json({ tree: [], truncated: true });
  });
  const app = await createApp({ logger: false });
  t.after(() => app.close());

  const throttled = await app.inject({
    method: 'POST',
    url: '/api/project/scan',
    payload: { branch: 'limited' },
  });
  assert.equal(throttled.statusCode, 429);
  assert.equal(throttled.json().code, 'GITHUB_RATE_LIMIT');
  assert.equal(throttled.headers['retry-after'], '30');

  const truncated = await app.inject({
    method: 'POST',
    url: '/api/project/scan',
  });
  assert.equal(truncated.statusCode, 422);
  assert.equal(truncated.json().code, 'PROJECT_TOO_LARGE');
  assert.equal((await app.inject('/api/project/tree')).statusCode, 409);
});
