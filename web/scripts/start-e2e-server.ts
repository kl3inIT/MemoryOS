import { createServer as createHttpServer } from "node:http";

const host = "127.0.0.1";
const frontendPort = 4173;

const backend = createHttpServer((request, response) => {
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

const { createServer: createViteServer } = await import("vite");
const vite = await createViteServer({
  server: {
    host,
    port: frontendPort,
    strictPort: true,
  },
});

await vite.listen();

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
