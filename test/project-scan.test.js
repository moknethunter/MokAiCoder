import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createApp } from '../dist/app.js';

process.env.GITHUB_TOKEN = 'fixture-token';
process.env.GITHUB_OWNER = 'fixture-owner';
process.env.GITHUB_REPO = 'fixture-repo';
process.env.GITHUB_BRANCH = 'fixture-main';

test('serves the existing Arabic web app and health endpoint', async t => {
  const app = await createApp({ logger: false });
  t.after(() => app.close());

  const health = await app.inject('/health');
  assert.equal(health.statusCode, 200);
  assert.equal(health.json().status, 'ok');

  const homepage = await app.inject('/');
  assert.equal(homepage.statusCode, 200);
  assert.match(homepage.body, /lang="ar" dir="rtl"/);

  const script = await app.inject('/app.js');
  assert.equal(script.statusCode, 200);
  assert.match(script.body, /async function scanProject/);

  const missingApi = await app.inject('/api/missing');
  assert.equal(missingApi.statusCode, 404);
  assert.deepEqual(missingApi.json(), { error: 'Not found' });
});

test('scans GitHub files and preserves the project API response and cache', async t => {
  const sourceFiles = {
    'src/index.ts': "import { helper } from './helper';",
    'src/helper.ts': "export const helper = 'أهلاً';",
    'scripts/report.py': 'print("report")',
  };
  const blobs = new Map(Object.entries(sourceFiles).map(([path, content], index) => [
    `blob-${index}`,
    { path, content, size: Buffer.byteLength(content) },
  ]));
  const tree = Array.from(blobs, ([sha, { path, size }]) => ({
    path, size, sha, type: 'blob',
  }));
  tree.push(
    { path: '.env', size: 64, sha: 'blocked-secret', type: 'blob' },
    { path: 'assets/icon.png', size: 64, sha: 'blocked-image', type: 'blob' },
    { path: 'large.txt', size: 500_000, sha: 'blocked-large', type: 'blob' },
  );

  const requests = [];
  t.mock.method(globalThis, 'fetch', async input => {
    const url = new URL(input);
    requests.push(url.pathname);
    assert.equal(url.origin, 'https://api.github.com');

    const gitPath = '/repos/fixture-owner/fixture-repo/git';
    if (url.pathname === `${gitPath}/trees/release` || url.pathname === `${gitPath}/trees/fixture-main`) {
      assert.equal(url.searchParams.get('recursive'), '1');
      return Response.json({ tree });
    }
    if (url.pathname === `${gitPath}/trees/unknown`) {
      return Response.json({ message: 'Not Found' }, { status: 404 });
    }
    const blob = blobs.get(url.pathname.slice(`${gitPath}/blobs/`.length));
    assert.ok(url.pathname.startsWith(`${gitPath}/blobs/`) && blob, `Unexpected request: ${url}`);
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
  const graph = {
    edges: { 'src/index.ts': ['src/helper.ts'], 'src/helper.ts': [], 'scripts/report.py': [] },
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
    totalBytes: Array.from(blobs.values()).reduce((sum, { size }) => sum + size, 0),
    graph,
    files: Object.keys(sourceFiles),
  });

  const defaultScan = await app.inject({ method: 'POST', url: '/api/project/scan' });
  assert.equal(defaultScan.statusCode, 200, defaultScan.body);
  assert.deepEqual(defaultScan.json(), scan.json());
  assert.deepEqual(
    requests.filter(path => path.includes('/trees/')).map(path => path.split('/').at(-1)),
    ['release', 'fixture-main'],
  );
  assert.equal(requests.length, 8);

  const failedScan = await app.inject({
    method: 'POST',
    url: '/api/project/scan',
    payload: { branch: 'unknown' },
  });
  assert.equal(failedScan.statusCode, 500);
  assert.match(failedScan.json().error, /Not Found/);

  const cachedTree = await app.inject('/api/project/tree');
  assert.equal(cachedTree.statusCode, 200);
  assert.deepEqual(cachedTree.json(), Array.from(blobs.values(), ({ path, size }) => ({ path, size })));
  assert.deepEqual((await app.inject('/api/project/graph')).json(), graph);

  const cachedFile = await app.inject('/api/project/file?path=src%2Fhelper.ts');
  assert.equal(cachedFile.statusCode, 200);
  assert.deepEqual(cachedFile.json(), blobs.get('blob-1'));

  const blocked = await app.inject('/api/project/file?path=.env');
  assert.equal(blocked.statusCode, 404);
});
