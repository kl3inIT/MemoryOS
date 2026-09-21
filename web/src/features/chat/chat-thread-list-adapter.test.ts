import { QueryClient } from "@tanstack/react-query";
import { AssistantMessageStream } from "assistant-stream";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ChatMessage, ChatSession } from "@/lib/hey-api/types.gen";
import { ChatThreadRegistry } from "./chat-thread-controller";
import {
  chatHistoryAdapter,
  createChatThreadListAdapter,
  sessionFromThread,
  threadMetadata,
} from "./chat-thread-list-adapter";

const session: ChatSession = {
  id: "5230ab53-dab0-4441-acbf-840636b52953",
  rootMessageId: "49b9bc3c-5b2b-4560-a2cf-e69ce5dbe627",
  personaId: "cc9aa9f0-bcb7-4f28-ae4e-a43b5b44ce43",
  projectId: null,
  title: "Question",
  createdAt: "2026-09-09T00:00:00Z",
  updatedAt: "2026-09-10T00:00:00Z",
  reasoningEffort: null,
  archivedAt: null,
  branchedFromSessionId: null,
  branchedFromMessageId: null,
};
const message = (id: string, role: ChatMessage["role"], status: ChatMessage["status"]) =>
  ({
    id,
    sessionId: session.id,
    parentMessageId: null,
    latestChildMessageId: null,
    role,
    content: id,
    status,
    createdAt: session.createdAt,
    finishedAt: null,
    sources: [],
    artifacts: [],
    files: [],
    activity: { steps: [], reasoning: [] },
    images: [],
    generatedFiles: [],
    research: { clarification: false, plan: null, agents: [] },
    failureCode: null,
  }) satisfies ChatMessage;
const json = (data: unknown, status = 200) => Response.json(data, { status });

function stubFetch(handler: (request: Request, path: string) => Response) {
  const requests: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input.clone() : new Request(input, init);
      const url = new URL(request.url);
      requests.push(`${request.method} ${url.pathname}${url.search}`);
      return handler(request, url.pathname);
    }),
  );
  return requests;
}

function setup() {
  const queries = new QueryClient();
  const registry = new ChatThreadRegistry(queries, "actor");
  return { registry, adapter: createChatThreadListAdapter(registry, queries) };
}

afterEach(() => vi.unstubAllGlobals());

describe("assistant-ui thread list adapter over the session API", () => {
  it("lists conversations with an offset cursor and rebuilds rows", async () => {
    const page = Array.from({ length: 30 }, (_, index) => ({
      ...session,
      id: `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`,
    }));
    const requests = stubFetch(() => json(page));
    const { adapter } = setup();

    const first = await adapter.list();

    expect(requests).toEqual(["GET /api/chat/sessions?offset=0&limit=30&archived=false"]);
    expect(first.nextCursor).toBe("30");
    expect(sessionFromThread(threadMetadata(page[1]!))).toEqual(page[1]);
    await adapter.list({ after: "30" });
    expect(requests.at(-1)).toBe("GET /api/chat/sessions?offset=30&limit=30&archived=false");
  });

  it("archives and unarchives through the session routes, and holds an archived row as archived", async () => {
    const archived = { ...session, archivedAt: "2026-09-21T00:00:00Z" };
    const requests = stubFetch((request) => json(request.method === "POST" ? archived : session));
    const { adapter } = setup();

    await adapter.archive!(session.id);
    expect(requests.at(-1)).toBe(`POST /api/chat/sessions/${session.id}/archive`);
    await adapter.unarchive!(session.id);
    expect(requests.at(-1)).toBe(`POST /api/chat/sessions/${session.id}/unarchive`);

    // The thread list keeps an archived conversation as archived rather than dropping it.
    expect(threadMetadata(archived).status).toBe("archived");
    expect(threadMetadata(session).status).toBe("regular");
    expect(sessionFromThread(threadMetadata(archived))).toEqual(archived);
  });

  it("initializes from the transport's session and allows retry after a failed first send", async () => {
    const requests = stubFetch((request) =>
      request.method === "POST" ? json(session, 201) : json({}, 404),
    );
    const { registry, adapter } = setup();
    const controller = registry.obtain("__LOCALID_a", undefined);

    const failed = adapter.initialize("__LOCALID_a");
    await expect(
      controller.transport.sendMessages({
        chatId: "__LOCALID_a",
        messageId: undefined,
        abortSignal: undefined,
        trigger: "submit-message",
        messages: [{ id: "m1", role: "user", parts: [{ type: "text", text: " " }] }],
      }),
    ).rejects.toThrow();
    await expect(failed).rejects.toThrow();
    expect(requests).toEqual([]);

    const retried = adapter.initialize("__LOCALID_a");
    await controller.transport
      .sendMessages({
        chatId: "__LOCALID_a",
        messageId: undefined,
        abortSignal: undefined,
        trigger: "submit-message",
        messages: [{ id: "m2", role: "user", parts: [{ type: "text", text: "Question" }] }],
      })
      .catch(() => undefined);
    await expect(retried).resolves.toEqual({ remoteId: session.id, externalId: undefined });
    expect(requests[0]).toBe("POST /api/chat/sessions");
    expect(registry.byRemoteId(session.id)).toBe(controller);
  });

  it("asks the server for a title only after the first answer completes", async () => {
    const requests = stubFetch((_request, path) =>
      path.endsWith("/title") ? json({ ...session, title: "Named" }) : json(session),
    );
    const { registry, adapter } = setup();
    const controller = registry.obtain("local", session.id);
    controller.listen();

    const pending = adapter.generateTitle(session.id, []);
    await Promise.resolve();
    expect(requests).toEqual([]);
    controller.transport.callbacks.accepted(session, "user", "local-user");
    controller.transport.callbacks.state("ready");
    const stream = await pending;

    let title = "";
    for await (const result of AssistantMessageStream.fromAssistantStream(stream))
      title = result.parts.find((part) => part.type === "text")?.text ?? title;
    expect(requests).toEqual([`POST /api/chat/sessions/${session.id}/title`]);
    expect(title).toBe("Named");
    expect(controller.getState().session?.title).toBe("Named");
  });

  it("loads the selected branch linearly, defers a running reply and skips new threads", async () => {
    const saved = [
      message("11111111-1111-4111-8111-111111111111", "USER", "COMPLETED"),
      message("22222222-2222-4222-8222-222222222222", "ASSISTANT", "COMPLETED"),
      message("33333333-3333-4333-8333-333333333333", "USER", "COMPLETED"),
      message("44444444-4444-4444-8444-444444444444", "ASSISTANT", "RUNNING"),
    ];
    const requests = stubFetch((request, path) => {
      if (!path.endsWith("/messages")) return json(session);
      return json(new URL(request.url).searchParams.get("after") ? [] : saved);
    });
    const { registry } = setup();

    const empty = await chatHistoryAdapter(registry.obtain("new", undefined)).withFormat!(
      {} as never,
    ).load();
    expect(empty.messages).toEqual([]);
    expect(requests).toEqual([]);

    const controller = registry.obtain("saved", session.id);
    const loaded = await chatHistoryAdapter(controller).withFormat!({} as never).load();
    expect(loaded.messages.map((item) => item.parentId)).toEqual([
      null,
      saved[0]!.id,
      saved[1]!.id,
    ]);
    expect(controller.getState()).toMatchObject({
      resume: true,
      connection: "recovering",
      session,
    });
  });
});
