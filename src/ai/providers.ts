import { z } from 'zod';
import { AppError } from '../plugins/errorHandler.js';
import { eventData } from './events.js';

export type Turn = { role: 'system' | 'user' | 'assistant'; content: string };
type Model = { id: string; label: string };
type Tier = 'free' | 'local' | 'paid';

export interface ModelProvider {
  readonly id: string;
  readonly label: string;
  readonly tier: Tier;
  listModels(): Promise<Model[]>;
  chat(
    model: string,
    messages: Turn[],
    signal: AbortSignal,
    requireComplete?: boolean,
    maxTokens?: number,
  ): Promise<AsyncIterable<string>>;
}

type CompatibleConfig = Pick<ModelProvider, 'id' | 'label' | 'tier'> & {
  base: URL;
  key?: string;
  models?: Model[];
  tokenParameter?: 'max_tokens' | 'max_completion_tokens';
};

const modelPattern = /^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,179}$/;
const responseShape = z.object({
  error: z.object({ code: z.unknown().optional() }).optional(),
  choices: z
    .array(
      z.object({
        delta: z.object({ content: z.string().nullish() }).optional(),
        finish_reason: z.string().nullish(),
      }),
    )
    .optional(),
});
const modelCatalog = z.object({
  data: z.array(
    z.object({
      id: z.string().regex(modelPattern),
      name: z.string().optional(),
    }),
  ),
});

function retryAfter(value: string | null): string | undefined {
  if (!value) return;
  if (/^\d{1,8}$/.test(value)) return value;
  const date = Date.parse(value);
  if (Number.isFinite(date))
    return String(Math.max(0, Math.ceil((date - Date.now()) / 1000)));
}

async function verifyResponse(response: Response) {
  if (response.ok) return;
  await response.body?.cancel().catch(() => {});
  if (response.status === 429) {
    throw new AppError(
      429,
      'AI_RATE_LIMIT',
      'Model provider rate limit reached',
      retryAfter(response.headers.get('retry-after')),
    );
  }
  if (response.status === 401 || response.status === 403) {
    throw new AppError(
      502,
      'AI_PROVIDER_AUTH',
      'Model provider rejected the server credentials',
    );
  }
  if (response.status === 402) {
    throw new AppError(
      502,
      'AI_PROVIDER_CREDITS',
      'Model provider requires credits',
    );
  }
  throw new AppError(
    502,
    'AI_PROVIDER_ERROR',
    'Model provider could not complete the request',
  );
}

export function providerError(error: unknown): AppError {
  if (error instanceof AppError) return error;
  if (error instanceof Error && error.name === 'TimeoutError') {
    return new AppError(504, 'AI_TIMEOUT', 'Model response timed out');
  }
  return new AppError(
    502,
    'AI_PROVIDER_ERROR',
    'Could not complete the model response',
  );
}

class CompatibleProvider implements ModelProvider {
  readonly id;
  readonly label;
  readonly tier;
  private catalog?: { models: Model[]; until: number };
  private loading?: Promise<Model[]>;

  constructor(private readonly config: CompatibleConfig) {
    this.id = config.id;
    this.label = config.label;
    this.tier = config.tier;
  }

  private headers(): Record<string, string> {
    return this.config.key
      ? { Authorization: `Bearer ${this.config.key}` }
      : {};
  }

  async listModels(): Promise<Model[]> {
    if (this.config.models) return this.config.models;
    if (this.catalog && this.catalog.until > Date.now())
      return this.catalog.models;
    this.loading ??= this.loadModels().finally(() => {
      this.loading = undefined;
    });
    return this.loading;
  }

  private async loadModels(): Promise<Model[]> {
    try {
      const response = await fetch(new URL('models', this.config.base), {
        headers: this.headers(),
        redirect: 'error',
        signal: AbortSignal.timeout(8_000),
      });
      await verifyResponse(response);
      const parsed = modelCatalog.parse(await response.json());
      const models = parsed.data
        .slice(0, 100)
        .map(({ id, name }) => ({ id, label: name?.slice(0, 150) || id }));
      this.catalog = { models, until: Date.now() + 60_000 };
      return models;
    } catch (error) {
      throw providerError(error);
    }
  }

  async chat(
    model: string,
    messages: Turn[],
    signal: AbortSignal,
    requireComplete = false,
    maxTokens = 1536,
  ): Promise<AsyncIterable<string>> {
    try {
      const response = await fetch(
        new URL('chat/completions', this.config.base),
        {
          method: 'POST',
          signal,
          redirect: 'error',
          headers: {
            ...this.headers(),
            'Content-Type': 'application/json',
            Accept: 'text/event-stream',
          },
          body: JSON.stringify({
            model,
            messages,
            stream: true,
            [this.config.tokenParameter ?? 'max_tokens']: maxTokens,
          }),
        },
      );
      await verifyResponse(response);
      if (
        !response.body ||
        !response.headers.get('content-type')?.includes('text/event-stream')
      ) {
        await response.body?.cancel().catch(() => {});
        throw new AppError(
          502,
          'AI_INVALID_STREAM',
          'Model provider returned an unsupported response',
        );
      }
      return this.readReply(response.body, signal, requireComplete);
    } catch (error) {
      throw providerError(signal.aborted ? signal.reason : error);
    }
  }

  private async *readReply(
    body: ReadableStream<Uint8Array>,
    signal: AbortSignal,
    requireComplete: boolean,
  ) {
    let received = 0;
    let finished = false;
    try {
      for await (const data of eventData(body, signal)) {
        if (data.trim() === '[DONE]') {
          finished = true;
          break;
        }
        const event = responseShape.parse(JSON.parse(data));
        if (event.error?.code === 429 || event.error?.code === '429') {
          throw new AppError(
            429,
            'AI_RATE_LIMIT',
            'Model provider rate limit reached',
          );
        }
        const choice = event.choices?.[0];
        if (event.error || choice?.finish_reason === 'error') {
          throw new AppError(
            502,
            'AI_PROVIDER_ERROR',
            'Model provider ended the stream with an error',
          );
        }
        const text = choice?.delta?.content;
        if (text) {
          received += text.length;
          if (received > 32_000)
            throw new AppError(
              502,
              'AI_RESPONSE_LIMIT',
              'Model response exceeded the size limit',
            );
          yield text;
        }
        if (
          requireComplete &&
          choice?.finish_reason &&
          choice.finish_reason !== 'stop'
        )
          throw new AppError(
            502,
            'AI_INCOMPLETE',
            'Model did not return the entire file',
          );
        if (choice?.finish_reason) finished = true;
      }
      if (!finished || !received)
        throw new AppError(
          502,
          'AI_INCOMPLETE',
          'Model did not return a complete text response',
        );
    } catch (error) {
      throw providerError(signal.aborted ? signal.reason : error);
    }
  }
}

function endpoint(value: string, local: boolean): URL {
  const url = URL.parse(value);
  if (
    !url ||
    (url.protocol !== 'https:' && (!local || url.protocol !== 'http:')) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash
  ) {
    throw new Error('Invalid model service URL');
  }
  if (!url.pathname.endsWith('/')) url.pathname += '/';
  return url;
}

function configuredModels(value: string): Model[] {
  const ids = [
    ...new Set(
      value
        .split(',')
        .map((id) => id.trim())
        .filter(Boolean),
    ),
  ];
  if (
    !ids.length ||
    ids.length > 25 ||
    ids.some((id) => !modelPattern.test(id))
  ) {
    throw new Error('Invalid configured model list');
  }
  return ids.map((id) => ({ id, label: id }));
}

export function loadProviders(env: NodeJS.ProcessEnv): ModelProvider[] {
  const providers: ModelProvider[] = [];
  if (env.OPENROUTER_API_KEY) {
    const models = configuredModels(
      env.OPENROUTER_FREE_MODELS || 'openrouter/free',
    );
    if (
      models.some(({ id }) => id !== 'openrouter/free' && !id.endsWith(':free'))
    ) {
      throw new Error(
        'OPENROUTER_FREE_MODELS must contain only the free router or :free model IDs',
      );
    }
    providers.push(
      new CompatibleProvider({
        id: 'openrouter',
        label: 'OpenRouter',
        tier: 'free',
        base: new URL('https://openrouter.ai/api/v1/'),
        key: env.OPENROUTER_API_KEY,
        models,
      }),
    );
  }
  if (env.OLLAMA_BASE_URL) {
    providers.push(
      new CompatibleProvider({
        id: 'ollama',
        label: 'Ollama',
        tier: 'local',
        base: endpoint(env.OLLAMA_BASE_URL, true),
      }),
    );
  }
  if (env.XAI_API_KEY) {
    providers.push(
      new CompatibleProvider({
        id: 'grok',
        label: 'Grok (xAI)',
        tier: 'paid',
        base: new URL('https://api.x.ai/v1/'),
        key: env.XAI_API_KEY,
        models: env.XAI_MODELS ? configuredModels(env.XAI_MODELS) : undefined,
      }),
    );
  }
  if (env.PAID_AI_API_KEY) {
    if (!env.PAID_AI_BASE_URL || !env.PAID_AI_MODELS) {
      throw new Error(
        'PAID_AI_BASE_URL and PAID_AI_MODELS are required for the paid provider',
      );
    }
    const tokenParameter =
      env.PAID_AI_TOKEN_PARAMETER || 'max_completion_tokens';
    if (
      tokenParameter !== 'max_completion_tokens' &&
      tokenParameter !== 'max_tokens'
    ) {
      throw new Error('Invalid PAID_AI_TOKEN_PARAMETER');
    }
    providers.push(
      new CompatibleProvider({
        id: 'paid',
        label: 'OpenAI-compatible',
        tier: 'paid',
        base: endpoint(env.PAID_AI_BASE_URL, false),
        key: env.PAID_AI_API_KEY,
        models: configuredModels(env.PAID_AI_MODELS),
        tokenParameter,
      }),
    );
  }
  return providers;
}
