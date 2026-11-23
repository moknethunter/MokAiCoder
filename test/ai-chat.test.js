import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createApp } from '../src/app.ts';
import { loadProviders } from '../src/ai/providers.ts';

const token = 'fixture-chat-token-long-enough';
const routerKey = 'fixture-router-key';
const paidKey = 'fixture-paid-key';
const headers = { authorization: `Bearer ${token}` };
const messages = [{ role: 'user', content: 'اشرح هذه الدالة' }];
const baseRequest = {
  provider: 'openrouter',
  model: 'openrouter/free',
  messages,
};
const authRequest = (app, payload) =>
  app.inject({ method: 'POST', url: '/api/ai/chat', headers, payload });

async function appWithProviders(t, env = {}) {
  for (const [name, value] of Object.entries({
    MOKAI_CHAT_TOKEN: token,
    OPENROUTER_API_KEY: routerKey,
    OPENROUTER_FREE_MODELS: undefined,
    OLLAMA_BASE_URL: undefined,
    XAI_API_KEY: undefined,
    XAI_MODELS: undefined,
    MOKAI_WORKSPACE_ROOT: undefined,
    MOKAI_GITHUB_WRITE_TOKEN: undefined,
    GITHUB_BRANCH: undefined,
    PAID_AI_API_KEY: undefined,
    PAID_AI_BASE_URL: undefined,
    PAID_AI_MODELS: undefined,
    PAID_AI_TOKEN_PARAMETER: undefined,
    GITHUB_OWNER: undefined,
    GITHUB_REPO: undefined,
    GITHUB_TOKEN: undefined,
    ...env,
  })) {
    const prior = process.env[name];
    if (value === undefined) delete process.env[name];
    else process.env[name] = value;
    t.after(() => {
      if (prior === undefined) delete process.env[name];
      else process.env[name] = prior;
    });
  }
  const app = await createApp({ logger: false });
  t.after(() => app.close());
  return app;
}

function modelStream(frames) {
  const bytes = Buffer.from(
    frames
      .map(
        (frame) =>
          `data: ${frame === '[DONE]' ? frame : JSON.stringify(frame)}\r\n\r\n`,
      )
      .join(''),
  );
  return new Response(
    new ReadableStream({
      start(controller) {
        for (let index = 0; index < bytes.length; index += 7)
          controller.enqueue(bytes.subarray(index, index + 7));
        controller.close();
      },
    }),
    { headers: { 'content-type': 'text/event-stream; charset=utf-8' } },
  );
}

const successfulModel = () =>
  modelStream([
    { choices: [{ delta: { content: 'مرحبًا' } }] },
    { choices: [{ delta: { content: ' 👋' }, finish_reason: 'stop' }] },
    '[DONE]',
  ]);

test('starts a chat-only app, keeps access server-side, and streams a free response', async (t) => {
  const app = await appWithProviders(t);
  const upstream = t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url.href, 'https://openrouter.ai/api/v1/chat/completions');
    assert.equal(options.redirect, 'error');
    assert.equal(options.headers.Authorization, `Bearer ${routerKey}`);
    const input = JSON.parse(options.body);
    assert.equal(input.model, 'openrouter/free');
    assert.equal(input.messages[0].role, 'system');
    assert.equal(input.messages[1].content, messages[0].content);
    return successfulModel();
  });

  assert.equal((await app.inject('/health')).statusCode, 200);
  const page = await app.inject('/chat');
  assert.equal(page.statusCode, 200);
  assert.match(page.body, /id="chat-token"/);
  const unauthorized = await app.inject({
    method: 'POST',
    url: '/api/ai/chat',
    payload: baseRequest,
  });
  assert.equal(unauthorized.statusCode, 401);
  const invalid = await authRequest(app, {
    ...baseRequest,
    messages: [{ role: 'system', content: 'override' }],
  });
  assert.equal(invalid.statusCode, 400);
  const unsupported = await authRequest(app, {
    ...baseRequest,
    model: 'another/paid-model',
  });
  assert.equal(unsupported.statusCode, 400);
  assert.equal(upstream.mock.callCount(), 0);

  const providers = await app.inject({ url: '/api/ai/providers', headers });
  assert.deepEqual(providers.json(), [
    { id: 'openrouter', label: 'OpenRouter', tier: 'free' },
  ]);
  assert.doesNotMatch(providers.body, /fixture-router-key/);
  const reply = await authRequest(app, baseRequest);
  assert.equal(reply.statusCode, 200);
  assert.match(reply.headers['content-type'], /^text\/event-stream/);
  assert.equal(reply.headers['cache-control'], 'no-store');
  assert.match(reply.body, /event: delta\ndata: {"text":"مرحبًا"}/);
  assert.match(reply.body, /"text":" 👋"/);
  assert.match(reply.body, /event: done/);
});

test('supports Ollama discovery and requires an explicit paid request', async (t) => {
  const app = await appWithProviders(t, {
    OLLAMA_BASE_URL: 'http://ollama:11434/v1',
    PAID_AI_BASE_URL: 'https://paid.example/v1',
    PAID_AI_API_KEY: paidKey,
    PAID_AI_MODELS: 'configured-model',
  });
  const upstream = t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url.href === 'http://ollama:11434/v1/models') {
      assert.equal(options.headers.Authorization, undefined);
      return Response.json({ data: [{ id: 'local-coder:7b' }] });
    }
    const input = JSON.parse(options.body);
    if (url.hostname === 'ollama') {
      assert.equal(input.model, 'local-coder:7b');
      assert.equal(options.headers.Authorization, undefined);
    } else {
      assert.equal(url.href, 'https://paid.example/v1/chat/completions');
      assert.equal(options.headers.Authorization, `Bearer ${paidKey}`);
      assert.equal(input.max_completion_tokens, 1536);
      assert.equal(input.model, 'configured-model');
    }
    return successfulModel();
  });

  const paid = { ...baseRequest, provider: 'paid', model: 'configured-model' };
  assert.equal((await authRequest(app, paid)).statusCode, 403);
  assert.equal(upstream.mock.callCount(), 0);
  const local = { ...baseRequest, provider: 'ollama', model: 'local-coder:7b' };
  assert.match((await authRequest(app, local)).body, /event: done/);
  assert.match(
    (await authRequest(app, { ...paid, allowPaid: true })).body,
    /event: done/,
  );
  assert.equal(upstream.mock.callCount(), 3);
  assert.throws(
    () =>
      loadProviders({
        OPENROUTER_API_KEY: routerKey,
        OPENROUTER_FREE_MODELS: 'model-with-a-price',
      }),
    /only the free router/,
  );
  assert.throws(
    () =>
      loadProviders({
        PAID_AI_API_KEY: paidKey,
        PAID_AI_BASE_URL: 'http://paid.example/v1',
        PAID_AI_MODELS: 'model',
      }),
    /Invalid model service URL/,
  );
});

test('only shares a current scanned attachment with explicit cloud consent and redaction', async (t) => {
  const app = await appWithProviders(t, {
    GITHUB_OWNER: 'owner',
    GITHUB_REPO: 'repo',
  });
  const source =
    'const api_key = "ghp_abcdef0123456789";\nexport const hello = 1;';
  let delivered;
  t.mock.method(globalThis, 'fetch', async (input, options) => {
    const url = new URL(input);
    if (url.origin === 'https://api.github.com') {
      if (url.pathname.includes('/trees/')) {
        return Response.json({
          tree: [
            {
              type: 'blob',
              path: 'src/hello.js',
              sha: 'file-sha',
              size: Buffer.byteLength(source),
            },
          ],
        });
      }
      return Response.json({
        encoding: 'base64',
        content: Buffer.from(source).toString('base64'),
        size: Buffer.byteLength(source),
      });
    }
    delivered = JSON.parse(options.body);
    return successfulModel();
  });
  assert.equal(
    (await app.inject({ method: 'POST', url: '/api/project/scan' })).statusCode,
    200,
  );
  const { headers: treeHeaders } = await app.inject('/api/project/tree');
  const attachment = { path: 'src/hello.js', scanId: treeHeaders['x-scan-id'] };

  assert.equal(
    (await authRequest(app, { ...baseRequest, attachment })).statusCode,
    403,
  );
  assert.equal(delivered, undefined);
  const result = await authRequest(app, {
    ...baseRequest,
    attachment: { ...attachment, shareCloud: true },
  });
  assert.match(result.body, /event: done/);
  const prompt = delivered.messages.at(-1).content;
  assert.match(prompt, /src\/hello\.js/);
  assert.match(prompt, /\[REDACTED\]/);
  assert.doesNotMatch(prompt, /ghp_abcdef0123456789/);
});

test('preserves 429 timing, suppresses provider error bodies, and detects incomplete streams', async (t) => {
  const app = await appWithProviders(t);
  const replies = [
    Response.json(
      { error: { message: 'private upstream payload' } },
      { status: 429, headers: { 'retry-after': '12' } },
    ),
    modelStream([{ choices: [{ delta: { content: 'partial reply' } }] }]),
  ];
  const upstream = t.mock.method(globalThis, 'fetch', async () =>
    replies.shift(),
  );

  const throttled = await authRequest(app, baseRequest);
  assert.equal(throttled.statusCode, 429);
  assert.equal(throttled.json().code, 'AI_RATE_LIMIT');
  assert.equal(throttled.headers['retry-after'], '12');
  assert.doesNotMatch(throttled.body, /private upstream payload/);
  const truncated = await authRequest(app, baseRequest);
  assert.match(truncated.body, /event: error\ndata: {"code":"AI_INCOMPLETE"}/);
  assert.doesNotMatch(truncated.body, /event: done/);
  assert.equal(upstream.mock.callCount(), 2);
});

test('requires cloud and cost consent, then sends the draft, review and final in order', async (t) => {
  const app = await appWithProviders(t, {
    XAI_API_KEY: 'fixture-xai-secret',
    XAI_MODELS: 'fixture-grok',
  });
  const calls = [];
  const fetcher = t.mock.method(globalThis, 'fetch', async (url, options) => {
    const body = JSON.parse(options.body);
    calls.push({ host: url.hostname, path: url.pathname, body });
    if (url.hostname === 'api.x.ai')
      assert.equal(options.headers.Authorization, 'Bearer fixture-xai-secret');
    const text = ['مسودة fixture-xai-secret', 'مراجعة', 'رد نهائي'][
      calls.length - 1
    ];
    return modelStream([
      { choices: [{ delta: { content: text }, finish_reason: 'stop' }] },
    ]);
  });
  const collaboration = {
    ...baseRequest,
    reviewer: { provider: 'grok', model: 'fixture-grok' },
  };
  const status = await app.inject({ url: '/api/ai/providers', headers });
  assert.deepEqual(status.json().at(-1), {
    id: 'grok',
    label: 'Grok (xAI)',
    tier: 'paid',
  });
  assert.doesNotMatch(status.body, /fixture-xai-secret/);
  assert.equal(
    (await authRequest(app, collaboration)).json().code,
    'AI_REVIEW_CONSENT',
  );
  assert.equal(
    (await authRequest(app, { ...collaboration, shareReview: true })).json()
      .code,
    'AI_PAID_CONSENT',
  );
  const duplicate = {
    ...collaboration,
    reviewer: { provider: 'openrouter', model: 'openrouter/free' },
  };
  assert.equal(
    (await authRequest(app, duplicate)).json().code,
    'AI_REVIEW_PROVIDER',
  );
  assert.equal(fetcher.mock.callCount(), 0);

  const reply = await authRequest(app, {
    ...collaboration,
    shareReview: true,
    allowPaid: true,
  });
  assert.deepEqual(
    calls.map(({ host }) => host),
    ['openrouter.ai', 'api.x.ai', 'openrouter.ai'],
  );
  assert.equal(calls[1].path, '/v1/chat/completions');
  assert.match(calls[1].body.messages.at(-1).content, /مسودة \[REDACTED\]/);
  assert.doesNotMatch(
    calls[1].body.messages.at(-1).content,
    /fixture-xai-secret/,
  );
  assert.match(calls[2].body.messages.at(-1).content, /"review":"مراجعة"/);
  assert.match(
    reply.body,
    /"stage":"draft"[\s\S]*"stage":"review"[\s\S]*"stage":"final"/,
  );
  assert.match(reply.body, /"text":"رد نهائي"/);
  assert.match(reply.body, /event: done/);
  assert.doesNotMatch(reply.body, /fixture-xai-secret/);
});

test('keeps a workspace proposal read-only until an explicit save and rejects stale edits', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'mokai-workspace-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const path = join(directory, 'sample.js');
  await writeFile(path, 'export const value = 1;\n');
  await symlink(path, join(directory, 'link.js'));
  const app = await appWithProviders(t, { MOKAI_WORKSPACE_ROOT: directory });
  const workspace = (action, payload, authorization = headers) =>
    app.inject({
      method: 'POST',
      url: '/api/ai/workspace/' + action,
      headers: authorization,
      payload,
    });
  const target = { source: 'local', path: 'sample.js' };
  assert.equal((await workspace('open', target, {})).statusCode, 401);
  assert.equal(
    (await workspace('open', { ...target, path: 'link.js' })).statusCode,
    404,
  );
  assert.equal(
    (await workspace('open', { ...target, path: '../sample.js' })).statusCode,
    400,
  );
  const snapshot = (await workspace('open', target)).json();
  const edit = { ...target, revision: snapshot.revision };
  assert.equal(
    (await authRequest(app, { ...baseRequest, edit })).json().code,
    'AI_FILE_CONSENT',
  );

  const next = 'export const value = 2;\n';
  let attempt = 0;
  t.mock.method(globalThis, 'fetch', async (_url, options) => {
    const { messages } = JSON.parse(options.body);
    assert.match(messages.at(-1).content, /export const value = 1/);
    const finish_reason = ++attempt === 1 ? 'length' : 'stop';
    return modelStream([
      { choices: [{ delta: { content: next }, finish_reason }] },
    ]);
  });
  const input = { ...baseRequest, edit: { ...edit, shareCloud: true } };
  const incomplete = await authRequest(app, input);
  assert.match(incomplete.body, /AI_INCOMPLETE/);
  assert.doesNotMatch(incomplete.body, /event: done/);
  const proposal = await authRequest(app, input);
  assert.match(proposal.body, /event: done/);
  assert.equal(await readFile(path, 'utf8'), snapshot.content);

  const applied = await workspace('publish', { ...edit, content: next });
  assert.equal(applied.statusCode, 200);
  assert.equal(await readFile(path, 'utf8'), next);
  assert.equal(
    (await workspace('publish', { ...edit, content: 'stale' })).statusCode,
    409,
  );
  assert.equal(await readFile(path, 'utf8'), next);
});

test('publishes the reviewed content on a new GitHub branch based on the approved revision', async (t) => {
  const app = await appWithProviders(t, {
    GITHUB_OWNER: 'example',
    GITHUB_REPO: 'project',
    GITHUB_BRANCH: 'main',
    GITHUB_TOKEN: 'fixture-github-read',
    MOKAI_GITHUB_WRITE_TOKEN: 'fixture-github-write',
  });
  const original = 'a'.repeat(40);
  const tree = 'b'.repeat(40);
  const nextTree = 'c'.repeat(40);
  const commit = 'd'.repeat(40);
  const upstream = t.mock.method(
    globalThis,
    'fetch',
    async (input, options) => {
      const suffix = decodeURIComponent(
        new URL(input).pathname.split('/git/')[1],
      );
      const body = options.body ? JSON.parse(options.body) : undefined;
      if (options.method === 'POST')
        assert.equal(
          options.headers.authorization,
          'token fixture-github-write',
        );
      if (suffix === 'ref/heads/main')
        return Response.json({ object: { sha: original } });
      if (suffix === 'commits/' + original)
        return Response.json({ sha: original, tree: { sha: tree } });
      if (suffix === 'trees/' + tree) return Response.json({ tree: [] });
      if (suffix === 'trees') {
        assert.equal(body.base_tree, tree);
        assert.deepEqual(body.tree, [
          {
            path: 'sample.js',
            mode: '100644',
            type: 'blob',
            content: 'export {};\n',
          },
        ]);
        return Response.json({ sha: nextTree });
      }
      if (suffix === 'commits') {
        assert.deepEqual(body.parents, [original]);
        assert.equal(body.tree, nextTree);
        return Response.json({ sha: commit });
      }
      assert.equal(suffix, 'refs');
      assert.match(body.ref, /^refs\/heads\/mokai\/edit-[0-9a-f]{20}$/);
      assert.equal(body.sha, commit);
      return Response.json({ ref: body.ref });
    },
  );
  assert.equal((await app.inject('/api/project/tree')).statusCode, 401);
  const result = await app.inject({
    method: 'POST',
    url: '/api/ai/workspace/publish',
    headers,
    payload: {
      path: 'sample.js',
      source: 'github',
      revision: original,
      content: 'export {};\n',
    },
  });
  assert.equal(result.statusCode, 200, result.body);
  assert.equal(upstream.mock.callCount(), 6);
  assert.match(
    result.json().compareUrl,
    /^https:\/\/github\.com\/example\/project\/compare\/main\.\.\.mokai%2Fedit-/,
  );
});

test('reviews a project in batches, previews coordinated changes, and publishes one commit', async (t) => {
  const ref = 'a'.repeat(40);
  const treeSha = 'b'.repeat(40);
  const updated = 'c'.repeat(40);
  const commit = 'd'.repeat(40);
  const app = await appWithProviders(t, {
    XAI_API_KEY: 'fixture-grok-project',
    XAI_MODELS: 'grok-project',
    GITHUB_OWNER: 'my-team',
    GITHUB_REPO: 'sample',
    MOKAI_GITHUB_WRITE_TOKEN: 'fixture-project-write',
  });
  const files = new Map([
    ['src/a.ts', 'export const value = "before";\n'],
    ['src/b.ts', 'export function read() { return "before"; }\n'],
    ['docs/overview.md', 'project details\n'.repeat(2100)],
    ['secrets/service.txt', 'secret credential from source'],
  ]);
  const tree = [...files].map(([path, content], index) => ({
    path,
    type: 'blob',
    mode: '100644',
    sha: 'file-' + index,
    size: Buffer.byteLength(content),
  }));
  const readSegments = [];
  let writes = 0;
  let advanced = false;
  const respond = (value) =>
    modelStream([
      {
        choices: [
          { delta: { content: JSON.stringify(value) }, finish_reason: 'stop' },
        ],
      },
      '[DONE]',
    ]);

  t.mock.method(globalThis, 'fetch', async (url, options) => {
    const address = new URL(url);
    if (address.hostname === 'api.x.ai') {
      const [{ content: system }, { content }] = JSON.parse(
        options.body,
      ).messages;
      assert.doesNotMatch(content, /secret credential from source/);
      const input = JSON.parse(content);
      if (system.includes('افحص هذه الدفعة')) {
        readSegments.push(...input.segments);
        return respond({
          notes: 'project notes',
          candidates: input.segments
            .filter(({ path }) => path.endsWith('.ts'))
            .map(({ path }) => path),
        });
      }
      if (system.includes('اجمع نتائج'))
        return respond({ report: 'Full review' });
      if (system.includes('اقترح تعديلات')) {
        assert.deepEqual(
          input.selected.map(({ path }) => path),
          ['src/a.ts', 'src/b.ts'],
        );
        return respond({
          summary: 'Two coordinated edits',
          edits: ['src/a.ts', 'src/b.ts'].map((path) => ({
            kind: 'replace',
            path,
            replacements: [{ before: '"before"', after: '"after"' }],
          })),
        });
      }
      if (system.includes('مرحلة مستقلة'))
        return respond({ report: 'Reviewed both edits' });
      assert.fail('unexpected model stage');
    }
    const path = decodeURIComponent(address.pathname);
    if (options?.method === 'POST') {
      writes++;
      const body = JSON.parse(options.body);
      if (path.endsWith('/git/trees')) {
        assert.equal(body.base_tree, treeSha);
        assert.deepEqual(
          body.tree.map(({ path }) => path),
          ['src/a.ts', 'src/b.ts'],
        );
        assert.equal(
          body.tree.every(({ content }) => content.includes('"after"')),
          true,
        );
        return Response.json({ sha: updated });
      }
      if (path.endsWith('/git/commits')) {
        assert.deepEqual(body.parents, [ref]);
        assert.equal(body.tree, updated);
        return Response.json({ sha: commit });
      }
      if (path.endsWith('/git/refs')) {
        assert.equal(body.sha, commit);
        return Response.json({ ref: body.ref });
      }
    }
    if (path.endsWith('/git/ref/heads/main'))
      return Response.json({ object: { sha: advanced ? updated : ref } });
    if (path.includes('/git/commits/'))
      return Response.json({
        sha: advanced ? updated : ref,
        tree: { sha: treeSha },
      });
    if (path.endsWith('/git/trees/' + treeSha))
      return Response.json({ tree, truncated: false });
    const item = tree.find(({ sha }) => path.endsWith('/' + sha));
    if (item)
      return Response.json({
        content: Buffer.from(files.get(item.path)).toString('base64'),
        encoding: 'base64',
      });
    assert.fail('Unexpected request ' + path);
  });

  const base = '/api/ai/project-assistant';
  const request = {
    model: 'grok-project',
    action: 'edit',
    task: 'Update both values',
    allowPaid: true,
    shareCloud: true,
  };
  assert.equal(
    (await app.inject({ method: 'POST', url: base + '/run', payload: request }))
      .statusCode,
    401,
  );
  assert.equal(
    (
      await app.inject({
        method: 'POST',
        url: base + '/run',
        headers,
        payload: { ...request, shareCloud: false },
      })
    ).statusCode,
    400,
  );
  const reviewed = await app.inject({
    method: 'POST',
    url: base + '/run',
    headers,
    payload: request,
  });
  assert.equal(reviewed.statusCode, 200);
  const event = reviewed.body.match(/event: result\ndata: ([^\n]+)/);
  assert.ok(event, reviewed.body);
  const result = JSON.parse(event[1]);
  assert.equal(result.coverage.reviewed, 3);
  assert.ok(result.coverage.batches > 1);
  assert.equal(result.changes.length, 2);
  assert.equal(result.verification.review, 'Reviewed both edits');
  assert.equal(
    readSegments
      .filter(({ path }) => path === 'docs/overview.md')
      .map(({ content }) => content)
      .join(''),
    files.get('docs/overview.md'),
  );
  assert.equal(writes, 0);

  const published = await app.inject({
    method: 'POST',
    url: base + '/publish',
    headers,
    payload: { id: result.id },
  });
  assert.equal(published.statusCode, 200, published.body);
  assert.equal(writes, 3);
  advanced = true;
  const stale = await app.inject({
    method: 'POST',
    url: base + '/publish',
    headers,
    payload: { id: result.id },
  });
  assert.equal(stale.statusCode, 409);
  assert.equal(writes, 3);
});
