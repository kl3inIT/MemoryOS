import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryOsChatTransport } from "./chat-transport";
import { initialChatTitle, loadChatHistory, toUiMessages } from "./chat-api";
import { mergedReport, type ResearchState } from "./chat-research";
import { fixtureSource } from "../../../tests/fixtures/chat-data";
import type { ChatMessage, ChatSession } from "@/lib/hey-api/types.gen";
import type { UIMessageChunk } from "ai";
import { i18n } from "@/i18n";

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
  expect(initialChatTitle(" ")).toBe(i18n.t("app:Hội thoại mới", { keySeparator: false }));
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
  artifacts: [],
  files: [],
  sources: [],
  activity: { steps: [], reasoning: [] },
  research: { clarification: false, plan: null, agents: [] },
  images: [],
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
  artifacts: ChatMessage["artifacts"] = [],
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
          : [{ ...row, status, sources, artifacts }],
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
  it("captures Web intent for one send and keeps missing intent off", async () => {
    const fetch = fixture(() => sse(delta + terminal()));
    const transport = new MemoryOsChatTransport(session);
    transport.selectWeb("auto");
    const pending = send(transport);
    transport.selectWeb("off");
    await collect(await pending);
    const requests = fetch.mock.calls.map(([input, init]) =>
      input instanceof Request ? input : new Request(input, init),
    );
    const submitted = requests.find(
      (request) => request.method === "POST" && new URL(request.url).pathname.endsWith("/messages"),
    );
    expect(submitted).toBeDefined();
    expect(await submitted!.clone().json()).toMatchObject({ webSearch: "auto" });
    expect(new MemoryOsChatTransport(session).webSearch).toBe("off");
  });
  it("loads committed artifact metadata once after outcome without a second inference and restores it on reload", async () => {
    const artifacts = [
      {
        id: crypto.randomUUID(),
        title: "Revenue",
        spec: JSON.stringify({
          root: { component: "Metric", props: { label: "September", value: "125000" } },
        }),
      },
    ];
    const fetch = fixture(
      () => sse(delta + packet(2, "outcome", { status: "COMPLETED", hasArtifacts: true })),
      "COMPLETED",
      [],
      artifacts,
    );
    const chunks = await collect(await send(new MemoryOsChatTransport(session)));
    expect(chunks.find((c) => c.type === "message-metadata")).toMatchObject({
      messageMetadata: { artifacts, serverStatus: "COMPLETED" },
    });
    expect(
      fetch.mock.calls.filter(([input]) => input instanceof Request && input.method === "POST"),
    ).toHaveLength(1);
    const reads = fetch.mock.calls
      .map(([input]) => input as Request)
      .filter((r) => r.method === "GET" && new URL(r.url).pathname.endsWith("/messages"));
    expect(reads).toHaveLength(1);
    expect(new URL(reads[0]!.url).searchParams.get("after")).toBe(userId);
    expect(new URL(reads[0]!.url).searchParams.get("limit")).toBe("1");
    expect(toUiMessages([{ ...row, artifacts }])[0]?.metadata?.artifacts).toEqual(artifacts);
  });

  it("streams one server-executed tool part whose input carries progress and fails it on Stop", async () => {
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
    const tool = { toolCallId: "s1", toolName: "search_knowledge", source: null, durationMs: null };
    const plan = packet(1, "tool", { ...tool, stage: "SEARCHING", search, documents: [] });
    fixture(() =>
      sse(
        plan +
          plan +
          packet(2, "tool", { ...tool, stage: "EXPANDING", search: null, documents }) +
          packet(3, "outcome", { status: "CANCELED" }),
      ),
    );
    const chunks = await collect(await send(new MemoryOsChatTransport(session)));
    expect(chunks.filter((chunk) => chunk.type === "tool-input-start")).toEqual([
      {
        type: "tool-input-start",
        toolCallId: "s1",
        toolName: "search_knowledge",
        providerExecuted: true,
        dynamic: true,
      },
    ]);
    const inputs = chunks.filter((chunk) => chunk.type === "tool-input-available");
    expect(inputs).toHaveLength(2);
    expect(inputs[1]).toMatchObject({
      input: { stage: "EXPANDING", queries: ["HR-2026"], filters: search.filters, documents },
    });
    expect(chunks.find((chunk) => chunk.type === "tool-output-error")).toMatchObject({
      toolCallId: "s1",
      errorText: "TOOL_FAILED",
    });
    expect(chunks.some((chunk) => chunk.type === "text-start")).toBe(false);
    expect(chunks.filter((chunk) => chunk.type === "message-metadata").at(-1)).toMatchObject({
      messageMetadata: { serverStatus: "CANCELED" },
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

  it("sends a composer quote as a leading blockquote of the question", async () => {
    const fetch = fixture(() => sse(delta + terminal()));
    await collect(
      await new MemoryOsChatTransport(session).sendMessages({
        chatId: session.id,
        messageId: undefined,
        abortSignal: undefined,
        trigger: "submit-message",
        messages: [
          {
            id: requestId,
            role: "user",
            metadata: { custom: { quote: { text: "First line\nSecond", messageId: runId } } },
            parts: [{ type: "text", text: "Question" }],
          },
        ],
      }),
    );
    const request = new Request(fetch.mock.calls[0]![0], fetch.mock.calls[0]![1]);
    expect((await request.json()).text).toBe("> First line\n> Second\n\nQuestion");
  });

  it("feeds sequenced sources into native message state once and retains them on Stop", async () => {
    const source = packet(2, "tool", {
      toolCallId: "s1",
      toolName: "search_knowledge",
      stage: "SOURCE",
      source: fixtureSource,
    });
    fixture(() => sse(delta + source + source + packet(3, "outcome", { status: "CANCELED" })));
    const chunks = await collect(await send(new MemoryOsChatTransport(session)));
    expect(chunks[0]).toMatchObject({
      type: "start",
      messageMetadata: { serverStatus: "RUNNING", createdAt: expect.any(String) },
    });
    expect(chunks.filter((chunk) => chunk.type === "message-metadata")).toEqual([
      {
        type: "message-metadata",
        messageMetadata: { sources: [fixtureSource] },
      },
      {
        type: "message-metadata",
        messageMetadata: {
          sources: [fixtureSource],
          artifacts: [],
          images: [],
          imageGenerating: false,
          serverStatus: "CANCELED",
        },
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
    expect(toUiMessages([row])[0]?.metadata?.createdAt).toBe(row.createdAt);
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
        packet(1, "tool", {
          toolCallId: "search-1",
          toolName: "search_knowledge",
          stage: "STARTED",
          source: null,
        }) +
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

  it("orders reasoning, tool steps and answer text into separate parts and skips unknown events", async () => {
    const call = {
      toolCallId: "call_1",
      toolName: "web_search",
      source: null,
      search: null,
      documents: [],
      durationMs: null,
    };
    fixture(() =>
      sse(
        packet(1, "reasoning", { text: "Checking " }) +
          packet(2, "reasoning", { text: "sources" }) +
          packet(3, "tool", { ...call, stage: "STARTED" }) +
          packet(4, "future-event", { anything: true }) +
          packet(5, "tool", { ...call, stage: "COMPLETED", durationMs: 1200 }) +
          packet(6, "text-delta", { text: "Answer" }) +
          packet(7, "outcome", { status: "COMPLETED", failureCode: null }),
      ),
    );
    const chunks = await collect(await send(new MemoryOsChatTransport(session)));
    expect(chunks.map((chunk) => chunk.type).filter((type) => type !== "message-metadata")).toEqual(
      [
        "start",
        "reasoning-start",
        "reasoning-delta",
        "reasoning-delta",
        "reasoning-end",
        "tool-input-start",
        "tool-input-available",
        "tool-input-available",
        "tool-output-available",
        "text-start",
        "text-delta",
        "text-end",
        "finish",
      ],
    );
    expect(chunks.find((chunk) => chunk.type === "tool-output-available")).toMatchObject({
      toolCallId: "call_1",
      output: { durationMs: 1200 },
    });
  });

  it("routes deep research progress into one data part and keeps agent steps off the timeline", async () => {
    const tool = {
      source: null,
      search: null,
      documents: [],
      durationMs: null,
      parentToolCallId: null,
      tabIndex: null,
    };
    const agent = { ...tool, toolCallId: "call_a", toolName: "research_agent", tabIndex: 0 };
    const step = {
      ...tool,
      toolCallId: "call_s",
      toolName: "search_knowledge",
      parentToolCallId: "call_a",
    };
    fixture(() =>
      sse(
        packet(1, "research-plan", { text: "1. Revenue" }) +
          packet(2, "tool", { ...agent, stage: "STARTED" }) +
          packet(3, "research-agent-start", {
            toolCallId: "call_a",
            tabIndex: 0,
            task: "Revenue in 2025",
          }) +
          packet(4, "tool", { ...step, stage: "STARTED" }) +
          packet(5, "tool", {
            ...step,
            stage: "SEARCHING",
            search: {
              queries: ["revenue"],
              filters: { sources: [], created: null, updated: null },
            },
          }) +
          packet(6, "tool", { ...step, stage: "COMPLETED", durationMs: 40 }) +
          packet(7, "reasoning", { text: "Check margins", parentToolCallId: "call_a" }) +
          packet(8, "intermediate-report", { toolCallId: "call_a", text: "Revenue grew [1]." }) +
          packet(9, "intermediate-report-citations", {
            toolCallId: "call_a",
            citations: [{ marker: 1, citationId: 3 }],
          }) +
          packet(10, "tool", { ...agent, stage: "COMPLETED", durationMs: 900 }) +
          packet(11, "text-delta", { text: "Report [3]" }) +
          packet(12, "outcome", { status: "COMPLETED", failureCode: null }),
      ),
    );
    const chunks = await collect(await send(new MemoryOsChatTransport(session)));
    expect(
      chunks.some((chunk) => chunk.type.startsWith("tool-") || chunk.type.startsWith("reasoning-")),
    ).toBe(false);
    const parts = chunks.filter((chunk) => chunk.type === "data-research") as unknown as {
      id: string;
      data: ResearchState;
    }[];
    const last = parts.at(-1)!;
    expect(last.id).toBe(`${runId}:research`);
    expect(last.data.plan).toBe("1. Revenue");
    expect(last.data.agents).toMatchObject([
      {
        toolCallId: "call_a",
        cycle: 0,
        tabIndex: 0,
        task: "Revenue in 2025",
        status: "COMPLETED",
        durationMs: 900,
        report: "Revenue grew [1].",
      },
    ]);
    expect(last.data.agents[0]!.activity.steps[0]).toMatchObject({
      toolName: "search_knowledge",
      status: "COMPLETED",
      queries: ["revenue"],
    });
    expect(last.data.agents[0]!.activity.reasoning[0]!.text).toBe("Check margins");
    expect(mergedReport(last.data.agents[0]!)).toBe("Revenue grew [3].");
    expect(chunks.findIndex((chunk) => chunk.type === "data-research")).toBeLessThan(
      chunks.findIndex((chunk) => chunk.type === "text-start"),
    );
  });

  it("restores saved research as the first part of the answer", () => {
    const [message] = toUiMessages([
      {
        ...row,
        research: {
          clarification: false,
          plan: "1. Revenue",
          agents: [
            {
              toolCallId: "call_a",
              cycle: 0,
              tabIndex: 1,
              task: "Revenue",
              status: "FAILED",
              durationMs: 5,
              report: null,
              citations: [],
              activity: { steps: [], reasoning: [] },
            },
          ],
        },
      },
    ]);
    expect(message!.parts[0]).toMatchObject({
      type: "data-research",
      id: `${runId}:research`,
      data: { plan: "1. Revenue", agents: [{ tabIndex: 1, status: "FAILED" }] },
    });
    expect(toUiMessages([row])[0]!.parts[0]).toMatchObject({ type: "text" });
  });

  it("keeps hosted-search citations that arrive after the step finished in message metadata", async () => {
    const call = { toolCallId: "ws_1", toolName: "web_search", search: null, documents: [] };
    fixture(() =>
      sse(
        packet(1, "tool", { ...call, stage: "STARTED", source: null, durationMs: null }) +
          packet(2, "tool", { ...call, stage: "COMPLETED", source: null, durationMs: 900 }) +
          packet(3, "tool", { ...call, stage: "SOURCE", source: fixtureSource, durationMs: null }) +
          packet(4, "outcome", { status: "COMPLETED", failureCode: null }),
      ),
    );
    const chunks = await collect(await send(new MemoryOsChatTransport(session)));
    expect(chunks.filter((chunk) => chunk.type === "tool-output-available")).toHaveLength(1);
    expect(chunks.filter((chunk) => chunk.type === "tool-input-available")).toHaveLength(2);
    expect(chunks.find((chunk) => chunk.type === "message-metadata")).toMatchObject({
      messageMetadata: { sources: [fixtureSource], toolCitations: { ws_1: [1] } },
    });
  });

  it("rebuilds saved reasoning, tool steps and text in streamed order", () => {
    const [message] = toUiMessages([
      {
        ...row,
        content: "Before. After.",
        activity: {
          steps: [
            {
              position: 1,
              toolCallId: "call_1",
              toolName: "search_knowledge",
              status: "COMPLETED",
              startedAt: row.createdAt,
              durationMs: 800,
              textOffset: 7,
              queries: ["leave"],
              documents: [],
              citations: [1],
            },
            {
              position: 2,
              toolCallId: "call_2",
              toolName: "read_file",
              status: "FAILED",
              startedAt: row.createdAt,
              durationMs: 20,
              textOffset: 14,
              queries: [],
              documents: [],
              citations: [],
            },
          ],
          reasoning: [{ position: 0, textOffset: 0, text: "Plan" }],
        },
      },
    ]);
    expect(message!.parts.map((part) => part.type)).toEqual([
      "reasoning",
      "text",
      "dynamic-tool",
      "text",
      "dynamic-tool",
      "text",
    ]);
    expect(message!.parts[1]).toEqual({ type: "text", text: "Before." });
    expect(message!.parts[2]).toMatchObject({
      state: "output-available",
      input: { queries: ["leave"], citations: [1], durationMs: 800 },
    });
    expect(message!.parts[3]).toEqual({ type: "text", text: " After." });
    expect(message!.parts[4]).toMatchObject({ state: "output-error", errorText: "TOOL_FAILED" });
    expect(toUiMessages([row])[0]!.parts.map((part) => part.type)).toEqual(["text"]);
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

  it("resumes a research-length stream past three disconnects while it keeps producing events", async () => {
    // Deep research runs for minutes and each connection is capped, so only a silent stream may fall back to history.
    let call = 0;
    const fetch = fixture(() => {
      call += 1;
      return call <= 5
        ? sse(packet(call, "text-delta", { text: `part ${call} ` }))
        : sse(packet(6, "outcome", { status: "COMPLETED", failureCode: null }));
    });
    const chunks = await collect(await send(new MemoryOsChatTransport(session)));
    expect(chunks.filter((chunk) => chunk.type === "text-delta")).toHaveLength(5);
    expect(chunks.at(-1)?.type).toBe("finish");
    expect(
      fetch.mock.calls.filter(([input, init]) =>
        new URL(new Request(input, init).url).pathname.endsWith("/events"),
      ),
    ).toHaveLength(6);
  });

  it("keeps resuming connections that deliver nothing until the outcome, without polling history or showing recovery", async () => {
    // A research agent can work for minutes without an event; the server ends every live stream with an outcome or a reset.
    let call = 0;
    const fetch = fixture(() => {
      call += 1;
      return call <= 4 ? sse(": heartbeat\n\n") : sse(delta + terminal());
    });
    const transport = new MemoryOsChatTransport(session);
    const states: string[] = [];
    transport.callbacks.state = (state) => states.push(state);
    const chunks = await collect(await send(transport));
    expect(chunks.at(-1)?.type).toBe("finish");
    const requests = fetch.mock.calls.map(([input, init]) => new Request(input, init));
    expect(
      requests.filter((request) => new URL(request.url).pathname.endsWith("/events")),
    ).toHaveLength(5);
    expect(
      requests.filter(
        (request) =>
          request.method === "GET" && new URL(request.url).pathname.endsWith("/messages"),
      ),
    ).toHaveLength(0);
    expect(states).not.toContain("recovering");
  });

  it("backs off failed connections, shows recovery and returns to streaming on the next event", async () => {
    let call = 0;
    fixture(() => {
      call += 1;
      return call <= 2 ? json({}, 503) : sse(delta + terminal());
    });
    const transport = new MemoryOsChatTransport(session);
    const states: string[] = [];
    transport.callbacks.state = (state) => states.push(state);
    const chunks = await collect(await send(transport));
    expect(chunks.at(-1)?.type).toBe("finish");
    expect(call).toBe(3);
    expect(states.slice(states.indexOf("recovering"))).toEqual([
      "recovering",
      "recovering",
      "streaming",
      "ready",
    ]);
  });

  it("waits for the browser to come back online before reconnecting", async () => {
    let call = 0;
    const onLine = vi.spyOn(globalThis.navigator, "onLine", "get").mockReturnValue(false);
    fixture(() => {
      call += 1;
      return call === 1 ? json({}, 503) : sse(delta + terminal());
    });
    const transport = new MemoryOsChatTransport(session);
    const collected = send(transport).then(collect);
    await new Promise((resolve) => setTimeout(resolve, 1_000));
    expect(call).toBe(1);
    onLine.mockReturnValue(true);
    globalThis.dispatchEvent(new Event("online"));
    expect((await collected).at(-1)?.type).toBe("finish");
    expect(call).toBe(2);
    onLine.mockRestore();
  });

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
