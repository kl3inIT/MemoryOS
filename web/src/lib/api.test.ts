import { QueryClient } from "@tanstack/react-query";
import { delay, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";
import { server } from "@/test/msw";
import { ApiError, withRequestTimeout } from "./api";
import { deleteChatPersonaMutation } from "./hey-api/@tanstack/react-query.gen";
import { client } from "./hey-api/client.gen";
import { handleDeleteChatPersona } from "./hey-api/msw.gen";

function stubFetch(response: () => Response) {
  const fetch = vi.fn(async (_request: Request) => response());
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

describe("API client defaults", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it.each(["POST", "PUT", "PATCH", "DELETE"] as const)(
    "adds the same-origin guard to a %s request",
    async (method) => {
      const fetch = stubFetch(() => Response.json({}));
      await client.request({ method, url: "/api/probe" });
      expect(fetch.mock.calls[0]?.[0].headers.get("X-MemoryOS-CSRF")).toBe("1");
    },
  );

  it.each(["GET", "HEAD", "OPTIONS"] as const)(
    "leaves a %s request without the guard",
    async (method) => {
      const fetch = stubFetch(() => new Response(null, { status: 204 }));
      await client.request({ method, url: "/api/probe" });
      expect(fetch.mock.calls[0]?.[0].headers.has("X-MemoryOS-CSRF")).toBe(false);
    },
  );

  it("rejects a failed response with ApiError without a per-call option", async () => {
    stubFetch(() => Response.json({ code: "not-found" }, { status: 404 }));
    const failure = await client.get({ url: "/api/probe" }).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).status).toBe(404);
    expect((failure as ApiError).cause).toEqual({ code: "not-found" });
  });
});

describe("request timeout", () => {
  const cache = new QueryClient();
  const context = { client: cache, meta: undefined, mutationKey: undefined };
  const path = { personaId: "7f000000-0000-4000-8000-000000000001" };
  // One wrapped mutation for every call, as a mounted hook holds it.
  const remove = withRequestTimeout(deleteChatPersonaMutation(), 50).mutationFn!;
  const call = () => remove({ path, query: { revision: 1 } }, context);

  it("gives up on a request that does not answer in time", async () => {
    server.use(handleDeleteChatPersona(async () => delay("infinite")));
    await expect(call()).rejects.toBeInstanceOf(ApiError);
  });

  it("times each call from its own start", async () => {
    server.use(handleDeleteChatPersona(() => new HttpResponse(null, { status: 204 })));
    await call();
    await new Promise((resolve) => setTimeout(resolve, 80));
    await expect(call()).resolves.not.toThrow();
  });

  it("still stops when the caller aborts", async () => {
    server.use(handleDeleteChatPersona(async () => delay("infinite")));
    const caller = new AbortController();
    const pending = withRequestTimeout(deleteChatPersonaMutation(), 60_000).mutationFn!(
      { path, query: { revision: 1 }, signal: caller.signal },
      context,
    );
    caller.abort();
    await expect(pending).rejects.toBeInstanceOf(ApiError);
  });
});
