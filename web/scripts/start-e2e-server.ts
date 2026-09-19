import { createServer as createHttpServer } from "node:http";
import { handleChatFixture } from "../tests/fixtures/chat-server.ts";

const host = "127.0.0.1";
const frontendPort = 4173;

const backend = createHttpServer(async (request, response) => {
  if (await handleChatFixture(request, response)) return;
  if (request.url === "/oauth2/authorization/memoryos") {
    response.writeHead(302, {
      location: "/login/oauth2/code/memoryos?code=test&state=test",
      "set-cookie": "SESSION=oauth-state; Path=/; Secure; HttpOnly; SameSite=Lax",
    });
    response.end();
    return;
  }

  if (request.url?.startsWith("/login/oauth2/code/memoryos")) {
    response.writeHead(200, { "content-type": "text/plain" });
    response.end(request.headers.cookie ?? "missing session cookie");
    return;
  }

  response.writeHead(404);
  response.end();
});

await new Promise<void>((resolve, reject) => {
  backend.once("error", reject);
  backend.listen(0, host, () => resolve());
});

const backendAddress = backend.address();
if (!backendAddress || typeof backendAddress === "string") {
  backend.close();
  throw new Error("The browser-test backend did not bind a TCP port.");
}

// Local HTTP is required to exercise Secure-cookie stripping at the loopback boundary.
// noinspection HttpUrlsUsage
process.env.MEMORYOS_API_URL = `http://${host}:${backendAddress.port}`;

// MEMORYOS_E2E_PREVIEW=1 serves the production build with the deployment Content-Security-Policy from nginx.conf,
// so previews and charts are checked under the policy staging enforces; the dev server cannot run under it.
const previewMode = process.env.MEMORYOS_E2E_PREVIEW === "1";
const vite = previewMode ? await startPreview() : await startDevServer();

async function startDevServer() {
  const { createServer: createViteServer } = await import("vite");
  const server = await createViteServer({
    server: {
      host,
      port: frontendPort,
      strictPort: true,
    },
  });
  await server.listen();
  return server;
}

async function startPreview() {
  const { readFileSync } = await import("node:fs");
  const { build, preview } = await import("vite");
  const nginx = readFileSync(new URL("../nginx.conf", import.meta.url), "utf8");
  const policy = /add_header Content-Security-Policy "([^"]+)"/.exec(nginx)?.[1];
  if (!policy) throw new Error("nginx.conf declares no Content-Security-Policy.");
  await build({ logLevel: "warn" });
  // preview.proxy defaults to server.proxy, so /api reaches the fixture backend as in dev.
  return preview({
    preview: {
      host,
      port: frontendPort,
      strictPort: true,
      headers: {
        "Content-Security-Policy": policy.replace(/\$\{[A-Z_]+\}/g, "").replace(/ +/g, " "),
      },
    },
  });
}

let closing = false;
const close = async () => {
  if (closing) {
    return;
  }
  closing = true;

  await vite.close();
  await new Promise<void>((resolve, reject) => {
    backend.close((error) => (error ? reject(error) : resolve()));
  });
};

process.once("SIGINT", () => void close());
process.once("SIGTERM", () => void close());
