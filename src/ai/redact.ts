const sensitivePath =
  /^(?:\.env(?:[._-].*)?|\.netrc|\.npmrc|\.pypirc|\.gnupg|\.htpasswd|credentials?(?:[._-].*)?|secrets?(?:[._-].*)?)$/i;

export function redact(text: string, secrets: string[]): string {
  for (const secret of secrets) text = text.replaceAll(secret, '[REDACTED]');
  return text
    .replace(
      /-----BEGIN [^\r\n]+?PRIVATE KEY-----[\s\S]*?(?:-----END [^\r\n]+?PRIVATE KEY-----|$)/g,
      '[REDACTED]',
    )
    .replace(
      /\b(?:gh[pousr]_[A-Za-z0-9]{10,}|github_pat_[A-Za-z0-9_]{12,}|sk-[A-Za-z0-9_-]{12,}|AKIA[A-Z0-9]{16})\b/g,
      '[REDACTED]',
    )
    .replace(
      /(\b(?:access[_-]?token|api[_-]?key|client[_-]?secret|password|authorization|token|secret)\b["']?\s*[:=]\s*["']?)(?:Bearer\s+)?[^\s"'\x60,;]+/gi,
      '$1[REDACTED]',
    );
}

export function canShareFile(path: string) {
  return !path.split('/').some((part) => sensitivePath.test(part));
}

export function configuredSecrets(env: NodeJS.ProcessEnv) {
  return [
    env.MOKAI_CHAT_TOKEN,
    env.OPENROUTER_API_KEY,
    env.XAI_API_KEY,
    env.MOKAI_GITHUB_WRITE_TOKEN,
    env.PAID_AI_API_KEY,
    env.GITHUB_TOKEN,
  ].filter((secret): secret is string => Boolean(secret));
}
