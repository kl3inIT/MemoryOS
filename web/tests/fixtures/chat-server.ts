import { fixtureModels, fixtureSource } from "./chat-data.ts";
import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { ChatMessage, ChatSession } from "../../src/lib/hey-api/types.gen.ts";

type Run = {
  id: string;
  packets: string[];
  listeners: Set<ServerResponse>;
  timer?: ReturnType<typeof setTimeout>;
};
type Session = {
  session: ChatSession;
  messages: ChatMessage[];
  runs: Map<string, Run>;
  sends: number;
  mode: string;
  selectedModels: Array<string | undefined>;
};
const sessions = new Map<string, Session>();
const answer =
  'Hello 👋\n\nHere is an example:\n\n```java\nSystem.out.println("Hello");\n```\n\n[Reference](https://example.com)';
function json(response: ServerResponse, data: unknown, status = 200) {
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify(data));
}
async function body(request: IncomingMessage) {
  let text = "";
  for await (const chunk of request) text += chunk;
  return JSON.parse(text || "{}");
}
function create(title = "Browser conversation", mode = "normal"): Session {
  const now = new Date().toISOString();
  const session = {
    id: randomUUID(),
    rootMessageId: randomUUID(),
    personaId: randomUUID(),
    title,
    createdAt: now,
    updatedAt: now,
  };
  const value = { session, messages: [], runs: new Map(), sends: 0, mode, selectedModels: [] };
  sessions.set(session.id, value);
  return value;
}
function emit(run: Run, event: string, data: object) {
  const sequence = run.packets.length + 1;
  const packet = `id: ${run.id}:${sequence}\nevent: ${event}\ndata: ${JSON.stringify({ assistantMessageId: run.id, sequence, ...data })}\n\n`;
  run.packets.push(packet);
  for (const listener of run.listeners) listener.write(packet);
}
function finish(state: Session, run: Run, status: "COMPLETED" | "CANCELED" | "FAILED") {
  const message = state.messages.find((item) => item.id === run.id)!;
  if (message.status !== "RUNNING") return;
  clearTimeout(run.timer);
  message.status = status;
  message.finishedAt = new Date().toISOString();
  emit(run, "outcome", {
    status,
    failureCode: status === "FAILED" ? "CHAT_PROVIDER_FAILED" : null,
  });
  for (const listener of run.listeners) listener.end();
  run.listeners.clear();
}

/** Test-only HTTP fixture: real incremental streams; no claims about model/IAM acceptance. */
export async function handleChatFixture(
  request: IncomingMessage,
  response: ServerResponse,
): Promise<boolean> {
  const url = new URL(request.url!, "http://localhost");
  if (url.pathname === "/api/chat/models") {
    json(response, fixtureModels);
    return true;
  }
  if (url.pathname === "/api/chat/test-fixture" && request.method === "POST") {
    const input = await body(request);
    const value = create(input.title, input.mode);
    json(response, value.session);
    return true;
  }
  if (!url.pathname.startsWith("/api/chat/")) return false;
  const segments = url.pathname.split("/");
  if (segments.length === 4) {
    if (request.method === "POST") {
      const input = await body(request);
      json(response, create(input.title).session, 201);
    } else
      json(
        response,
        [...sessions.values()]
          .map((item) => item.session)
          .slice(
            Number(url.searchParams.get("offset") ?? 0),
            Number(url.searchParams.get("offset") ?? 0) +
              Number(url.searchParams.get("limit") ?? 30),
          ),
      );
    return true;
  }
  const state = sessions.get(segments[4]!);
  if (!state) {
    json(response, {}, 404);
    return true;
  }
  if (segments[5] === "stats") {
    json(response, {
      sends: state.sends,
      selectedModels: state.selectedModels,
      readers: [...state.runs.values()].reduce((n, run) => n + run.listeners.size, 0),
    });
    return true;
  }
  if (segments.length === 5) {
    json(response, state.session);
    return true;
  }
  if (segments.length === 6 && request.method === "GET") {
    const after = url.searchParams.get("after");
    const offset = after ? state.messages.findIndex((message) => message.id === after) + 1 : 0;
    json(
      response,
      state.messages.slice(offset, offset + Number(url.searchParams.get("limit") ?? 50)),
    );
    return true;
  }
  if (segments.length === 6 && request.method === "POST") {
    const input = await body(request);
    if (state.messages.some((message) => message.status === "RUNNING")) {
      json(response, {}, 409);
      return true;
    }
    if (
      input.parentMessageId !== (state.messages.at(-1)?.id ?? state.session.rootMessageId) ||
      !/^[0-9a-f-]{36}$/.test(input.clientRequestId)
    ) {
      json(response, {}, 400);
      return true;
    }
    const userId = randomUUID();
    const assistantId = randomUUID();
    const createdAt = new Date().toISOString();
    state.messages.push(
      {
        id: userId,
        sources: [],
        sessionId: state.session.id,
        role: "USER",
        content: input.text,
        parentMessageId: input.parentMessageId,
        latestChildMessageId: assistantId,
        status: "COMPLETED",
        createdAt,
        finishedAt: createdAt,
      },
      {
        id: assistantId,
        sources: [],
        sessionId: state.session.id,
        role: "ASSISTANT",
        content: "",
        parentMessageId: userId,
        latestChildMessageId: null,
        status: "RUNNING",
        createdAt,
        finishedAt: null,
      },
    );
    const run: Run = { id: assistantId, packets: [], listeners: new Set() };
    state.runs.set(assistantId, run);
    state.sends++;
    state.selectedModels.push(input.modelConfigurationId);
    const selected =
      fixtureModels.find((model) => model.id === input.modelConfigurationId) ?? fixtureModels[0]!;
    json(
      response,
      {
        userMessageId: userId,
        assistantMessageId: assistantId,
        modelConfigurationId: selected.id,
        ...(input.modelConfigurationId && selected.id !== input.modelConfigurationId
          ? { fallbackReason: "SELECTION_UNAVAILABLE" }
          : {}),
      },
      202,
    );
    const grounded = state.mode.startsWith("grounded");
    if (grounded) emit(run, "search", { toolCallId: "search-1", stage: "STARTED", source: null });
    run.timer = setTimeout(
      () => {
        const content =
          state.mode === "long"
            ? Array.from(
                { length: 50 },
                (_, index) =>
                  `Paragraph ${index + 1}: This is a longer reply for reading while the assistant continues working.`,
              ).join("\n\n")
            : grounded
              ? "## Annual leave\n\nEmployees receive **17 days** of annual leave [1].\n\nUnverified [99] remains plain text. Inline code `[1]` is not a citation.\n\n```text\n[1] in a code block\n```"
              : answer;
        if (grounded) {
          state.messages.at(-1)!.sources = [fixtureSource];
          emit(run, "search", { toolCallId: "search-1", stage: "SOURCE", source: fixtureSource });
          emit(run, "search", { toolCallId: "search-1", stage: "COMPLETED", source: null });
        }
        state.messages.at(-1)!.content = content;
        if (state.mode === "grounded-split") {
          const split = content.indexOf("[1]") + 2;
          emit(run, "text-delta", { text: content.slice(0, split) });
          emit(run, "text-delta", { text: content.slice(split) });
        } else emit(run, "text-delta", { text: content });
        if (state.mode === "long") {
          run.timer = setTimeout(() => {
            state.messages.at(-1)!.content += "\n\nThe final paragraph arrived.";
            emit(run, "text-delta", { text: "\n\nThe final paragraph arrived." });
            finish(state, run, "COMPLETED");
          }, 3000);
          return;
        }
        if (state.mode === "disconnect" || state.mode === "grounded-disconnect") {
          for (const listener of run.listeners) listener.end();
          run.listeners.clear();
        }
        run.timer = setTimeout(
          () =>
            finish(
              state,
              run,
              state.mode === "failed" || state.mode === "grounded-failed" ? "FAILED" : "COMPLETED",
            ),
          state.mode === "slow" || state.mode === "grounded-slow" ? 20_000 : 1000,
        );
      },
      grounded ? 750 : 250,
    );
    return true;
  }
  const run = state.runs.get(segments[6]!);
  if (!run) {
    json(response, {}, 404);
    return true;
  }
  if (segments[7] === "cancel") {
    json(
      response,
      {
        assistantMessageId: run.id,
        status: state.messages.find((message) => message.id === run.id)!.status,
      },
      202,
    );
    setTimeout(() => finish(state, run, "CANCELED"), 500);
    return true;
  }
  if (segments[7] === "events") {
    response.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
    response.flushHeaders();
    if (state.mode === "gap" || state.mode === "grounded-gap") {
      response.end(
        `event: reset\ndata: ${JSON.stringify({ assistantMessageId: run.id, reason: "BUFFER_MISSING" })}\n\n`,
      );
      return true;
    }
    const cursor = url.searchParams.get("after") ?? request.headers["last-event-id"];
    const sequence = typeof cursor === "string" ? Number(cursor.split(":").at(-1)) : 0;
    for (const packet of run.packets.slice(sequence)) response.write(packet);
    if (state.messages.find((message) => message.id === run.id)!.status !== "RUNNING")
      response.end();
    else {
      run.listeners.add(response);
      response.on("close", () => run.listeners.delete(response));
    }
    return true;
  }
  json(response, {}, 404);
  return true;
}
