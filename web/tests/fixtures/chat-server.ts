import { fixtureModels, fixtureSource } from "./chat-data.ts";
import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { ChatMessage, ChatSession, ProjectView } from "../../src/lib/hey-api/types.gen.ts";

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
  allMessages: Map<string, ChatMessage>;
  sharing: { enabled: boolean; revision: number };
  feedback: Map<string, object>;
};
const sessions = new Map<string, Session>();
const projects = new Map<string, Required<ProjectView>>();
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
    projectId: null,
    title,
    createdAt: now,
    updatedAt: now,
  };
  const value = {
    session,
    messages: [],
    runs: new Map(),
    sends: 0,
    mode,
    selectedModels: [],
    allMessages: new Map(),
    sharing: { enabled: false, revision: 0 },
    feedback: new Map(),
  };
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
  const message = state.allMessages.get(run.id)!;
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
  if (["/api/chat/personas", "/api/chat/personas/sources"].includes(url.pathname)) {
    json(response, []);
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
  if (segments[3] === "projects") {
    const project = projects.get(segments[4]!);
    if (segments.length === 4) {
      if (request.method === "POST") {
        const input = await body(request);
        const created = {
          ...input,
          id: randomUUID(),
          revision: 0,
          updatedAt: new Date().toISOString(),
        };
        projects.set(created.id, created);
        json(response, created, 201);
      } else {
        const offset = Number(url.searchParams.get("offset") ?? 0);
        json(
          response,
          [...projects.values()].slice(
            offset,
            offset + Number(url.searchParams.get("limit") ?? 100),
          ),
        );
      }
    } else if (!project) json(response, {}, 404);
    else if (segments[5] === "sessions") {
      if (request.method === "POST") {
        const input = await body(request);
        const created = create(input.title).session;
        created.projectId = project.id;
        json(response, created, 201);
      } else if (request.method === "GET") {
        const offset = Number(url.searchParams.get("offset") ?? 0);
        json(
          response,
          [...sessions.values()]
            .map((item) => item.session)
            .filter((item) => item.projectId === project.id)
            .slice(offset, offset + Number(url.searchParams.get("limit") ?? 30)),
        );
      } else json(response, {}, 405);
    } else if (request.method === "PUT" || request.method === "DELETE") {
      if (Number(url.searchParams.get("revision")) !== project.revision) json(response, {}, 409);
      else if (request.method === "DELETE") {
        projects.delete(project.id);
        for (const item of sessions.values())
          if (item.session.projectId === project.id) item.session.projectId = null;
        json(response, null, 204);
      } else {
        const updated = {
          ...project,
          ...(await body(request)),
          revision: project.revision + 1,
          updatedAt: new Date().toISOString(),
        };
        projects.set(project.id, updated);
        json(response, updated);
      }
    } else json(response, project);
    return true;
  }
  if (segments.length === 4 && segments[3] === "sessions") {
    if (request.method === "POST") {
      const input = await body(request);
      if (input.projectId && !projects.has(input.projectId)) json(response, {}, 404);
      else {
        const created = create(input.title).session;
        created.projectId = input.projectId ?? null;
        json(response, created, 201);
      }
    } else
      json(
        response,
        [...sessions.values()]
          .reverse()
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
  if (segments[3] === "shared") {
    if (!state.sharing.enabled) json(response, {}, 404);
    else if (segments.length === 5)
      json(response, {
        id: state.session.id,
        title: state.session.title,
        rootMessageId: state.session.rootMessageId,
      });
    else {
      const after = url.searchParams.get("after");
      const offset = after ? state.messages.findIndex((m) => m.id === after) + 1 : 0;
      json(
        response,
        state.messages.slice(offset, offset + 100).filter((m) => m.status !== "RUNNING"),
      );
    }
    return true;
  }
  if (segments[5] === "branches") {
    json(response, [
      {
        id: state.session.rootMessageId,
        parentMessageId: null,
        latestChildMessageId: state.messages[0]?.id ?? null,
      },
      ...state.allMessages.values(),
    ]);
    return true;
  }
  if (segments[5] === "feedback") {
    json(response, [...state.feedback.values()]);
    return true;
  }
  if (segments[5] === "sharing") {
    if (request.method === "PUT") {
      const input = await body(request);
      if (input.revision !== state.sharing.revision) {
        json(response, {}, 409);
        return true;
      }
      state.sharing = { enabled: input.enabled, revision: state.sharing.revision + 1 };
    }
    json(response, state.sharing);
    return true;
  }
  if (segments[7] === "feedback") {
    if (request.method === "DELETE") {
      state.feedback.delete(segments[6]!);
      response.writeHead(204).end();
    } else {
      const input = await body(request);
      const value = { assistantMessageId: segments[6], ...input };
      state.feedback.set(segments[6]!, value);
      json(response, value);
    }
    return true;
  }
  if (segments[5] === "title") {
    state.session.title = (await body(request)).title;
    json(response, state.session);
    return true;
  }
  if (segments[5] === "project") {
    const input = await body(request);
    if (input.projectId && !projects.has(input.projectId)) json(response, {}, 404);
    else {
      state.session.projectId = input.projectId ?? null;
      json(response, state.session);
    }
    return true;
  }
  if (segments[5] === "branch") {
    const input = await body(request);
    const target = state.allMessages.get(input.messageId);
    if (!target) {
      json(response, {}, 404);
      return true;
    }
    const parent = state.allMessages.get(target.parentMessageId!);
    const selectedChild =
      target.parentMessageId === state.session.rootMessageId
        ? state.messages[0]?.id
        : parent?.latestChildMessageId;
    if ((selectedChild ?? null) !== input.expectedChildId) {
      json(response, {}, 409);
      return true;
    }
    if (parent) parent.latestChildMessageId = target.id;
    const firstId =
      target.parentMessageId === state.session.rootMessageId ? target.id : state.messages[0]?.id;
    state.messages = [];
    let cursor: string | undefined = firstId;
    while (cursor) {
      const node: ChatMessage = state.allMessages.get(cursor)!;
      state.messages.push(node);
      cursor = node.latestChildMessageId ?? undefined;
    }
    response.writeHead(204).end();
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
    if (request.method === "DELETE") {
      for (const run of state.runs.values()) finish(state, run, "CANCELED");
      sessions.delete(state.session.id);
      response.writeHead(204).end();
      return true;
    }
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
  if (
    (segments.length === 6 || segments[7] === "edit" || segments[7] === "regenerate") &&
    request.method === "POST"
  ) {
    const input = await body(request);
    if (state.messages.some((message) => message.status === "RUNNING")) {
      json(response, {}, 409);
      return true;
    }
    const regeneration = segments[7] === "regenerate";
    if (segments[7] === "edit" || regeneration) {
      const index = state.messages.findIndex((m) => m.id === segments[6]);
      const target = state.messages[index];
      if (!target || target.role !== "USER") {
        json(response, {}, 404);
        return true;
      }
      input.text ??= target.content;
      state.messages = state.messages.slice(0, index);
      input.parentMessageId = target.parentMessageId;
    }
    if (
      input.parentMessageId !== (state.messages.at(-1)?.id ?? state.session.rootMessageId) ||
      !/^[0-9a-f-]{36}$/.test(input.clientRequestId)
    ) {
      json(response, {}, 400);
      return true;
    }
    const userId = regeneration ? segments[6]! : randomUUID();
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
    if (regeneration) {
      const original = state.allMessages.get(userId)!;
      original.latestChildMessageId = assistantId;
      state.messages[state.messages.length - 2] = original;
    }
    for (const message of state.messages) state.allMessages.set(message.id, message);
    const parent = state.allMessages.get(input.parentMessageId);
    if (parent) parent.latestChildMessageId = userId;
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
    if (state.mode === "grounded-progress") {
      emit(run, "search", {
        toolCallId: "search-1",
        stage: "SEARCHING",
        source: null,
        documents: [],
        search: {
          queries: ["annual leave policy", "HR-2026"],
          filters: {
            sources: ["FILE"],
            created: null,
            updated: { from: "2026-09-01T00:00:00Z", to: null },
          },
        },
      });
      emit(run, "search", {
        toolCallId: "search-1",
        stage: "EXPANDING",
        source: null,
        search: null,
        documents: [
          {
            documentId: fixtureSource.documentId,
            generation: fixtureSource.generation,
            title: fixtureSource.title,
            startOrdinal: fixtureSource.startOrdinal,
            endOrdinal: fixtureSource.endOrdinal,
          },
        ],
      });
    }
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
      state.mode === "waiting" ||
        state.mode === "grounded-waiting" ||
        state.mode === "grounded-progress"
        ? 20_000
        : grounded
          ? 750
          : 250,
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
