import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "./api";
import { client } from "./hey-api/client.gen";

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
      expect(fetch.mock.calls[0]![0].headers.get("X-MemoryOS-CSRF")).toBe("1");
    },
  );

  it.each(["GET", "HEAD", "OPTIONS"] as const)(
    "leaves a %s request without the guard",
    async (method) => {
      const fetch = stubFetch(() => new Response(null, { status: 204 }));
      await client.request({ method, url: "/api/probe" });
      expect(fetch.mock.calls[0]![0].headers.has("X-MemoryOS-CSRF")).toBe(false);
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
