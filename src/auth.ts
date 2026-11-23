import { createHash, timingSafeEqual } from 'node:crypto';
import type { FastifyRequest } from 'fastify';
import { AppError } from './plugins/errorHandler.js';

function digest(token: string) {
  return createHash('sha256').update(token).digest();
}

export function accessGuard(token: string | undefined) {
  if (token && token.length < 24)
    throw new Error('MOKAI_CHAT_TOKEN must have at least 24 characters');
  const key = token ? digest(token) : undefined;

  return async (req: FastifyRequest) => {
    if (!key)
      throw new AppError(
        503,
        'AI_SETUP_REQUIRED',
        'Configure the server access token',
      );
    const header = req.headers.authorization;
    if (
      !header?.startsWith('Bearer ') ||
      !timingSafeEqual(digest(header.slice(7)), key)
    )
      throw new AppError(
        401,
        'AI_UNAUTHORIZED',
        'A valid MokAiCoder access token is required',
      );
  };
}
