import assert from 'node:assert/strict';
import { test } from 'node:test';
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
