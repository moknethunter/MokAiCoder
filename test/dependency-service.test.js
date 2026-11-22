import assert from 'node:assert/strict';
import { test } from 'node:test';
import { DependencyService } from '../src/services/dependencyService.ts';
import { ProjectCache } from '../src/services/cache.ts';

const service = new DependencyService();
const file = (path, content = '') => ({
  path,
  content,
  size: Buffer.byteLength(content),
});

test('resolves relative JavaScript imports against the importing directory', () => {
  const graph = service.buildGraph([
    file(
      'src/features/index.ts',
      `
      import {
        helper
      } from '../shared/helper.js';
      import './styles';
      export { chosen } from './nested';
      const plugin = require ( './plugin.cjs' );
      import('./missing.js');
      import 'fastify';
    `,
    ),
    file('src/shared/helper.ts'),
    file('src/features/styles.js'),
    file('src/features/nested/index.ts'),
    file('src/features/plugin.cts'),
    file('somewhere/helper.ts'),
  ]);

  assert.deepEqual(graph.edges['src/features/index.ts'], [
    'src/shared/helper.ts',
    'src/features/styles.js',
    'src/features/nested/index.ts',
    'src/features/plugin.cts',
  ]);
  assert.deepEqual(graph.missing, {
    'src/features/index.ts': ['./missing.js'],
  });
  assert.deepEqual(graph.orphans, ['somewhere/helper.ts']);
});

test('detects closed cycles and groups duplicate declarations by file', () => {
  const graph = service.buildGraph([
    file(
      'src/a.ts',
      "import './b'; class Duplicate {} class Duplicate {} class __proto__ {}",
    ),
    file(
      'src/b.ts',
      "import './a'; interface Duplicate {} interface __proto__ {}",
    ),
    file('src/orphan.ts', 'class constructor {}'),
    file('__proto__', 'class constructor {}'),
  ]);
  assert.deepEqual(graph.cycles, [['src/a.ts', 'src/b.ts', 'src/a.ts']]);
  assert.deepEqual(graph.orphans, ['src/orphan.ts', '__proto__']);
  assert.deepEqual(graph.duplicateSymbols.Duplicate, ['src/a.ts', 'src/b.ts']);
  assert.deepEqual(graph.duplicateSymbols.__proto__, ['src/a.ts', 'src/b.ts']);
  assert.deepEqual(graph.duplicateSymbols.constructor, [
    'src/orphan.ts',
    '__proto__',
  ]);
  assert.deepEqual(graph.edges.__proto__, []);
});

test('identifies qualified Kotlin, C#, and Python dependencies', () => {
  const graph = service.buildGraph([
    file('kotlin/App.kt', 'import com.demo.A'),
    file('kotlin/com/demo/A.kt'),
    file('src/App.cs', 'using Company.Services;'),
    file('src/Company/Services/Client.cs'),
    file('python/app.py', 'from demo.module import helper'),
    file('python/demo/module.py'),
  ]);
  assert.deepEqual(graph.edges['kotlin/App.kt'], ['kotlin/com/demo/A.kt']);
  assert.deepEqual(graph.edges['src/App.cs'], [
    'src/Company/Services/Client.cs',
  ]);
  assert.deepEqual(graph.edges['python/app.py'], ['python/demo/module.py']);
});

test('expires and replaces cache snapshots atomically', (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: 0 });
  const cache = new ProjectCache(100);
  const files = [file('src/a.ts')];
  const id = cache.set(files, service.buildGraph(files));
  assert.equal(cache.current.id, id);
  t.mock.timers.tick(100);
  assert.equal(cache.current, undefined);
  const replacement = cache.set([], service.buildGraph([]));
  assert.notEqual(replacement, id);
  assert.equal(cache.current.files.size, 0);
  cache.clear();
  assert.equal(cache.current, undefined);
});
