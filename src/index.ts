import { createApp } from './app.js';

const app = await createApp();
const port = Number(process.env.PORT) || 8080;
await app.listen({ port, host: '0.0.0.0' });
