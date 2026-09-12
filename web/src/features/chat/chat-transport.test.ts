import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryOsChatTransport } from "./chat-transport";
import { initialChatTitle, loadChatHistory, toUiMessages } from "./chat-api";
import { fixtureSource } from "../../../tests/fixtures/chat-data";
import type { ChatMessage, ChatSession } from "@/lib/hey-api/types.gen";
import type { UIMessageChunk } from "ai";

const session: ChatSession = {
  id: "5230ab53-dab0-4441-acbf-840636b52953",
  rootMessageId: "49b9bc3c-5b2b-4560-a2cf-e69ce5dbe627",
  personaId: "cc9aa9f0-bcb7-4f28-ae4e-a43b5b44ce43",
  projectId: null,
  title: "Test",
  createdAt: "2026-09-09T00:00:00Z",
  updatedAt: "2026-09-09T00:00:00Z",
};
const runId = "7c6f01e4-a456-4157-bb67-3b9e3ae8e3a4";
it("uses a short, whitespace-normalized and Unicode-safe fallback title", () => {
  expect(initialChatTitle("  Phân tích\n  tài liệu  ")).toBe("Phân tích tài liệu");
  expect(initialChatTitle(" ")).toBe("Hội thoại mới");
  expect(initialChatTitle("😀".repeat(45))).toBe("😀".repeat(40) + "…");
});
it("keeps family emoji and combining marks intact at the title boundary", () => {
  for (const grapheme of ["👨‍👩‍👧‍👦", "e\u0301", "a\u0306\u0301"]) {
    expect(initialChatTitle("A".repeat(39) + grapheme + "x")).toBe("A".repeat(39) + grapheme + "…");
    expect(initialChatTitle("A".repeat(39) + grapheme)).toBe("A".repeat(39) + grapheme);
  }
});
it("keeps grapheme titles inside the API length limit", () => {
  const family = "👨‍👩‍👧‍👦";
  const title = initialChatTitle(family.repeat(40));
  expect(title).toBe(family.repeat(18) + "…");
  expect(title.length).toBeLessThanOrEqual(200);
});
const userId = "9a1b5318-f15b-4e37-899a-0809354cda6f";
const requestId = "e7a05ee5-cfd5-470b-9641-f4c322a3b4bb";
const row: ChatMessage = {
  files: [],
  sources: [],
  id: runId,
  sessionId: session.id,
  parentMessageId: userId,
  latestChildMessageId: null,
  role: "ASSISTANT",
  content: "Hello 👋",
  status: "COMPLETED",
  createdAt: session.createdAt,
  finishedAt: session.createdAt,
};
const json = (data: unknown, status = 200) => Response.json(data, { status });
function packet(sequence: number, event: string, data: object) {
  return `id: ${runId}:${sequence}\nevent: ${event}\ndata: ${JSON.stringify({ assistantMessageId: runId, sequence, ...data })}\n\n`;
}
const delta = packet(1, "text-delta", { text: "Hello 👋" });
const terminal = (status = "COMPLETED") => packet(2, "outcome", { status, failureCode: null });
const sse = (body: string) =>
  new Response(body, { headers: { "content-type": "text/event-stream" } });
function fixture(
  stream: (request: Request) => Response | Promise<Response>,
  status: ChatMessage["status"] = "COMPLETED",
  sources: ChatMessage["sources"] = [],
) {
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input.clone() : new Request(input, init);
    const path = new URL(request.url).pathname;
    if (path.endsWith("/events")) return stream(request);
    if (path.endsWith("/cancel"))
      return json({ assistantMessageId: runId, status: "RUNNING" }, 202);
    if (request.method === "POST")
      return json({ userMessageId: userId, assistantMessageId: runId }, 202);
    if (path.endsWith("/messages"))
      return json(
        new URL(request.url).searchParams.get("after") === runId
          ? []
          : [{ ...row, status, sources }],
      );
    return json(session);
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}
async function send(transport: MemoryOsChatTransport) {
  return transport.sendMessages({
    chatId: session.id,
    messageId: undefined,
    abortSignal: undefined,
    trigger: "submit-message",
    messages: [{ id: requestId, role: "user", parts: [{ type: "text", text: "Question" }] }],
  });
}
async function collect(stream: ReadableStream<UIMessageChunk>) {
  const reader = stream.getReader();
  const chunks: UIMessageChunk[] = [];
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    chunks.push(value);
  }
  return chunks;
}
afterEach(() => vi.unstubAllGlobals());

describe("MemoryOS ChatTransport using the generated HTTP/SSE clients", () => {
  it("replays search plans once and retains them while selected documents are being read", async () => {
    const search = {
      queries: ["HR-2026"],
      filters: { sources: ["FILE"], created: null, updated: null },
    };
    const documents = [
      {
        documentId: fixtureSource.documentId,
        generation: fixtureSource.generation,
        title: fixtureSource.title,
        startOrdinal: fixtureSource.startOrdinal,
        endOrdinal: fixtureSource.endOrdinal,
      },
    ];
    const plan = packet(1, "search", {
      toolCallId: "s1",
      stage: "SEARCHING",
      source: null,
      search,
      documents: [],
    });
    fixture(() =>
      sse(
        plan +
          plan +
          packet(2, "search", {
            toolCallId: "s1",
            stage: "EXPANDING",
            source: null,
            search: null,
            documents,
          }) +
          packet(3, "outcome", { status: "CANCELED" }),
      ),
    );
    const chunks = await collect(await send(new MemoryOsChatTransport(session)));
    const metadata = chunks.filter((chunk) => chunk.type === "message-metadata");
    expect(metadata).toHaveLength(3);
    expect(metadata[1]).toMatchObject({
      messageMetadata: {
        sources: [],
        searchProgress: { s1: { stage: "EXPANDING", search, documents } },
      },
    });
    expect(metadata[2]).toMatchObject({
      messageMetadata: { searchProgress: {}, serverStatus: "CANCELED" },
    });
  });

  it("captures the configuration ID before sending and forwards the accepted selection", async () => {
    const fetch = fixture(() => sse(delta + terminal()));
    const transport = new MemoryOsChatTransport(session);
    transport.selectModel(fixtureSource.documentId);
    const accepted = vi.fn();
    transport.listenModelSelection(accepted);
    const pending = send(transport);
    transport.selectModel(fixtureSource.generation);
    await collect(await pending);
    const request = new Request(fetch.mock.calls[0]![0], fetch.mock.calls[0]![1]);
    expect((await request.json()).modelConfigurationId).toBe(fixtureSource.documentId);
    expect(accepted).toHaveBeenCalledWith({ userMessageId: userId, assistantMessageId: runId });
  });

  it("feeds sequenced sources into native message state once and retains them on Stop", async () => {
    const source = packet(2, "search", {
      toolCallId: "s1",
      stage: "SOURCE",
      source: fixtureSource,
    });
    fixture(() => sse(delta + source + source + packet(3, "outcome", { status: "CANCELED" })));
    const chunks = await collect(await send(new MemoryOsChatTransport(session)));
    expect(chunks.filter((chunk) => chunk.type === "message-metadata")).toEqual([
      {
        type: "message-metadata",
        messageMetadata: {
          sources: [fixtureSource],
          searchProgress: { s1: { stage: "SOURCE", search: null, documents: [] } },
        },
      },
      {
        type: "message-metadata",
        messageMetadata: { sources: [fixtureSource], searchProgress: {}, serverStatus: "CANCELED" },
      },
    ]);
  });

  it("restores source metadata from durable outcomes on replay gaps and history reload", async () => {
    fixture(() => sse(`event: reset\ndata: {}\n\n`), "COMPLETED", [fixtureSource]);
    const chunks = await collect(await send(new MemoryOsChatTransport(session)));
    expect(chunks.find((chunk) => chunk.type === "message-metadata")).toMatchObject({
      messageMetadata: { sources: [fixtureSource], serverStatus: "COMPLETED" },
    });
    expect(toUiMessages([{ ...row, sources: [fixtureSource] }])[0]?.metadata?.sources).toEqual([
      fixtureSource,
    ]);
  });
  it.each([
    ["reversed range", { endOrdinal: 2 }],
    ["start beyond document", { startOrdinal: 10000, endOrdinal: 10000 }],
    ["end beyond document", { endOrdinal: 10000 }],
    ["missing provenance", { provenance: [] }],
    [
      "too many passages",
      { provenance: Array.from({ length: 61 }, () => fixtureSource.provenance[0]!) },
    ],
    ["provenance outside range", { provenance: [{ ordinal: 2, provenanceJson: "{}" }] }],
    ["oversized provenance", { provenance: [{ ordinal: 3, provenanceJson: "x".repeat(8193) }] }],
  ])("rejects %s before exposing history citations", (_name, overrides) => {
    expect(() =>
      toUiMessages([{ ...row, sources: [{ ...fixtureSource, ...overrides }] }]),
    ).toThrow();
  });

  it("accepts the last supported passage and provenance size in history", () => {
    const source = {
      ...fixtureSource,
      startOrdinal: 9999,
      endOrdinal: 9999,
      provenance: [{ ordinal: 9999, provenanceJson: "x".repeat(8192) }],
    };
    expect(toUiMessages([{ ...row, sources: [source] }])[0]?.metadata?.sources).toEqual([source]);
  });

  it("advances over search progress events without losing the text stream or falling back to history", async () => {
    const fetch = fixture(() =>
      sse(
        packet(1, "search", { toolCallId: "search-1", stage: "STARTED", source: null }) +
          packet(2, "text-delta", { text: "Answer [1]" }) +
          packet(3, "outcome", { status: "COMPLETED", failureCode: null }),
      ),
    );
    const chunks = await collect(await send(new MemoryOsChatTransport(session)));
    expect(chunks.filter((chunk) => chunk.type === "text-delta")).toEqual([
      { type: "text-delta", id: runId, delta: "Answer [1]" },
    ]);
    expect(chunks.at(-1)).toEqual({ type: "finish", finishReason: "stop" });
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("uses server IDs and stable request identity, ignores duplicate replay and finishes only on outcome", async () => {
    const fetch = fixture(() => sse(delta + delta + terminal()));
    const transport = new MemoryOsChatTransport(session);
    const accepted = vi.fn();
    transport.callbacks.accepted = accepted;
    const chunks = await collect(await send(transport));
    expect(accepted).toHaveBeenCalledWith(session, userId, requestId);
    expect(chunks.filter((chunk) => chunk.type === "text-delta")).toEqual([
      { type: "text-delta", id: runId, delta: "Hello 👋" },
    ]);
    expect(chunks.at(-1)).toEqual({ type: "finish", finishReason: "stop" });
    const request = new Request(fetch.mock.calls[0]![0], fetch.mock.calls[0]![1]);
    expect(await request.json()).toMatchObject({
      clientRequestId: requestId,
      parentMessageId: session.rootMessageId,
    });
    expect(request.headers.get("X-MemoryOS-CSRF")).toBe("1");
  });

  it("reconnects after clean EOF with a cursor and never sends a second inference", async () => {
    let streams = 0;
    const fetch = fixture((request) => {
      streams++;
      if (streams === 1) return sse(delta);
      expect(new URL(request.url).searchParams.get("after")).toBe(`${runId}:1`);
      return sse(terminal());
    });
    expect((await collect(await send(new MemoryOsChatTransport(session)))).at(-1)?.type).toBe(
      "finish",
    );
    expect(
      fetch.mock.calls.filter(([input, init]) => new Request(input, init).method === "POST"),
    ).toHaveLength(1);
  });

  it.each(["reset", "gap"])(
    "uses durable history on %s without duplicating partial text",
    async (mode) => {
      fixture(() =>
        sse(
          delta +
            (mode === "reset"
              ? `event: reset\ndata: ${JSON.stringify({ assistantMessageId: runId, reason: "BUFFER_MISSING" })}\n\n`
              : packet(4, "outcome", { status: "COMPLETED" })),
        ),
      );
      const chunks = await collect(await send(new MemoryOsChatTransport(session)));
      expect(chunks.filter((chunk) => chunk.type === "text-delta")).toHaveLength(1);
      expect(chunks.at(-1)?.type).toBe("finish");
    },
  );

  it("keeps a failed partial reply and never emits finish", async () => {
    fixture(() => sse(delta + terminal("FAILED")));
    const chunks = await collect(await send(new MemoryOsChatTransport(session)));
    expect(chunks.some((chunk) => chunk.type === "text-delta")).toBe(true);
    expect(chunks.at(-1)?.type).toBe("error");
    expect(chunks.some((chunk) => chunk.type === "finish")).toBe(false);
  });

  it("does not mark Stop complete at the 202 response; committed cancellation triggers native cancel", async () => {
    fixture(() => sse(delta + terminal("CANCELED")));
    const transport = new MemoryOsChatTransport(session);
    const canceled = vi.fn();
    transport.callbacks.canceled = canceled;
    const stream = await send(transport);
    await transport.stop();
    expect(canceled).not.toHaveBeenCalled();
    const chunks = await collect(stream);
    expect(canceled).toHaveBeenCalledOnce();
    expect(chunks.some((chunk) => chunk.type === "finish")).toBe(false);
  });

  it("keeps a completed winner when Stop races with completion", async () => {
    fixture(() => sse(delta + terminal()));
    const transport = new MemoryOsChatTransport(session);
    const canceled = vi.fn();
    transport.callbacks.canceled = canceled;
    const stream = await send(transport);
    await transport.stop();
    expect((await collect(stream)).at(-1)?.type).toBe("finish");
    expect(canceled).not.toHaveBeenCalled();
  });

  it("remembers Stop while the send reservation is pending", async () => {
    const fetch = fixture(() => sse(delta + terminal("CANCELED")));
    const acceptance = Promise.withResolvers<Response>();
    fetch.mockImplementationOnce(() => acceptance.promise);
    const transport = new MemoryOsChatTransport(session);
    const canceled = vi.fn();
    transport.callbacks.canceled = canceled;
    const sending = send(transport);
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    await transport.stop();
    expect(canceled).not.toHaveBeenCalled();
    acceptance.resolve(json({ userMessageId: userId, assistantMessageId: runId }, 202));
    await collect(await sending);
    expect(fetch.mock.calls.some(([input]) => new Request(input).url.endsWith("/cancel"))).toBe(
      true,
    );
    expect(canceled).toHaveBeenCalledOnce();
  });

  it.each([401, 403, 404])(
    "fails closed on stream HTTP %s without retry or history",
    async (status) => {
      const fetch = fixture(() => json({}, status));
      const transport = new MemoryOsChatTransport(session);
      const error = vi.fn();
      transport.callbacks.error = error;
      await expect(collect(await send(transport))).rejects.toMatchObject({ status });
      expect(error).toHaveBeenCalled();
      expect(fetch).toHaveBeenCalledTimes(2);
    },
  );

  it("rejects foreign-run events and bounds history traversal", async () => {
    fixture(() => sse(delta.replaceAll(runId, userId)));
    await expect(collect(await send(new MemoryOsChatTransport(session)))).rejects.toThrow(
      "Unexpected reply event",
    );
    const fetch = vi.fn(async (input: RequestInfo | URL) =>
      new URL(new Request(input).url).pathname.endsWith("/messages") ? json([row]) : json(session),
    );
    vi.stubGlobal("fetch", fetch);
    await expect(loadChatHistory(session.id, new AbortController().signal)).rejects.toThrow(
      "cursor did not advance",
    );
    expect(fetch).toHaveBeenCalledTimes(3);
  });
});
