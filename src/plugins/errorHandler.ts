import type { FastifyInstance } from 'fastify';
import { ZodError } from 'zod';

export class AppError extends Error {
  constructor(
    readonly statusCode: number,
    readonly code: string,
    message: string,
    readonly retryAfter?: string,
  ) {
    super(message);
  }
}

export function registerErrorHandler(app: FastifyInstance): void {
  app.setErrorHandler((error, req, reply) => {
    if (error instanceof AppError) {
      if (error.statusCode >= 500)
        req.log.warn({ code: error.code }, error.message);
      if (error.retryAfter) reply.header('Retry-After', error.retryAfter);
      return reply
        .code(error.statusCode)
        .send({ error: error.message, code: error.code });
    }

    if (error instanceof ZodError) {
      const details = error.issues.map(({ path, message }) => ({
        path,
        message,
      }));
      return reply
        .code(400)
        .send({ error: 'Invalid request', code: 'INVALID_REQUEST', details });
    }

    const status =
      error instanceof Error && 'statusCode' in error
        ? Number(error.statusCode)
        : 500;
    if (status === 413) {
      return reply
        .code(413)
        .send({ error: 'Request is too large', code: 'REQUEST_TOO_LARGE' });
    }
    if (status === 415) {
      return reply
        .code(415)
        .send({
          error: 'Use application/json',
          code: 'UNSUPPORTED_MEDIA_TYPE',
        });
    }
    if (status >= 400 && status < 500) {
      return reply
        .code(status)
        .send({ error: 'Invalid request', code: 'INVALID_REQUEST' });
    }

    req.log.error({ err: error }, 'Unexpected request error');
    return reply
      .code(500)
      .send({ error: 'Internal server error', code: 'INTERNAL_ERROR' });
  });
}
