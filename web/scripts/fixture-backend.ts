import { createServer } from "node:http";
import { handleChatFixture } from "../tests/fixtures/chat-server.ts";
import { handleMockLibrary } from "../tests/fixtures/mock-workspace.ts";

/**
 * The synthetic backend of the browser tests and `pnpm mock-api`: the chat fixture plus the OAuth redirect stubs.
 * `library` adds the sample Library routes of `pnpm mock-api`; the browser tests leave it off and route their own.
 */
export function createFixtureBackend({ library = false }: { library?: boolean } = {}) {
  return createServer(async (request, response) => {
    if (library && handleMockLibrary(request, response)) return;
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
}
