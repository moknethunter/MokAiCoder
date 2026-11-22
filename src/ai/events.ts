export async function* eventData(
  stream: ReadableStream<Uint8Array>,
  signal: AbortSignal,
) {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let data = '';
  let hasData = false;
  const cancel = () => void reader.cancel().catch(() => {});
  signal.addEventListener('abort', cancel, { once: true });
  try {
    signal.throwIfAborted();
    for (;;) {
      const chunk = await reader.read();
      signal.throwIfAborted();
      buffer += chunk.done
        ? decoder.decode()
        : decoder.decode(chunk.value, { stream: true });
      if (buffer.length + data.length > 262_144)
        throw new Error('Model event exceeded the size limit');
      let newline: number;
      while ((newline = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, newline).replace(/\r$/, '');
        buffer = buffer.slice(newline + 1);
        if (line.startsWith('data:')) {
          data += (hasData ? '\n' : '') + line.slice(5).replace(/^ /, '');
          hasData = true;
        }
        if (!line && hasData) {
          yield data;
          data = '';
          hasData = false;
        }
      }
      if (chunk.done) return;
    }
  } finally {
    signal.removeEventListener('abort', cancel);
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
