// TEMPORARY BENCH (not to be committed).
import { createServer as createHttpServer } from "node:http";
import { handleChatFixture } from "../tests/fixtures/chat-server.ts";

const host = "127.0.0.1";
const backend = createHttpServer(async (request, response) => {
  if (await handleChatFixture(request, response)) return;
  response.writeHead(404);
  response.end();
});
await new Promise<void>((resolve) => backend.listen(0, host, () => resolve()));
const address = backend.address();
if (!address || typeof address === "string") throw new Error("no port");
process.env.MEMORYOS_API_URL = `http://${host}:${address.port}`;
const { preview } = await import("vite");
const server = await preview({
  build: { outDir: process.env.BENCH_DIST! },
  preview: { host, port: 4174, strictPort: true },
});
server.printUrls();
