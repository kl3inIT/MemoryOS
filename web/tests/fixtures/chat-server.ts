import { fixtureModels, fixtureSource } from "./chat-data.ts";
import { randomUUID } from "node:crypto";
import { generatedFileMessages, handleGeneratedFile } from "./generated-files.ts";
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
/** A saved Deep research answer: plan, two parallel agents in the first cycle and one in the second. */
function seedResearch(value: Session) {
  const now = new Date().toISOString();
  const userId = randomUUID();
  const assistantId = randomUUID();
  const step = (toolCallId: string, toolName: string, queries: string[], position: number) => ({
    position,
    toolCallId,
    toolName,
    status: "COMPLETED" as const,
    durationMs: 4200,
    textOffset: 0,
    queries,
    documents: [],
    citations: [],
  });
  const agent = (
    toolCallId: string,
    cycle: number,
    tabIndex: number,
    task: string,
    report: string,
    steps: ReturnType<typeof step>[],
    durationMs: number,
  ) => ({
    toolCallId,
    cycle,
    tabIndex,
    task,
    status: "COMPLETED" as const,
    durationMs,
    report,
    citations: [{ marker: 1, citationId: tabIndex + 1 }],
    activity: {
      steps,
      reasoning: [
        {
          position: steps.length,
          textOffset: 0,
          text: "Sổ tay nhân sự trả lời phần ngày phép, nhưng chưa nói quy trình duyệt, nên tôi đọc thêm quy chế nội bộ.",
        },
      ],
    },
  });
  const user: ChatMessage = {
    id: userId,
    sessionId: value.session.id,
    parentMessageId: value.session.rootMessageId,
    latestChildMessageId: assistantId,
    role: "USER",
    content: "Chính sách nghỉ phép hằng năm và quy trình duyệt của công ty thế nào?",
    status: "COMPLETED",
    createdAt: now,
    finishedAt: now,
    sources: [],
    artifacts: [],
    files: [],
    images: [],
    activity: { steps: [], reasoning: [] },
    generatedFiles: [],
    research: { clarification: false, plan: null, agents: [] },
  };
  const assistant: ChatMessage = {
    id: assistantId,
    sessionId: value.session.id,
    parentMessageId: userId,
    latestChildMessageId: null,
    role: "ASSISTANT",
    content:
      "## Nghỉ phép hằng năm\n\nNhân viên chính thức có **12 ngày phép** mỗi năm, cộng thêm một ngày cho mỗi 5 năm làm việc [1].\n\n## Quy trình duyệt\n\nĐơn phải gửi trước **3 ngày làm việc** và do quản lý trực tiếp duyệt; nghỉ liên tục quá 5 ngày cần thêm duyệt của trưởng bộ phận [2].",
    status: "COMPLETED",
    createdAt: now,
    finishedAt: now,
    sources: [fixtureSource, { ...fixtureSource, citationId: 2, title: "Quy chế nội bộ 2026" }],
    artifacts: [],
    files: [],
    images: [],
    activity: { steps: [], reasoning: [] },
    generatedFiles: [],
    research: {
      clarification: false,
      plan: "1. Xác định số ngày phép hằng năm theo thâm niên trong sổ tay nhân sự.\n2. Tìm quy trình duyệt đơn, ai duyệt và thời hạn báo trước.\n3. Đối chiếu với quy chế nội bộ mới nhất và ghi rõ khác biệt.\n4. Kiểm tra cách tính phép chưa dùng khi chuyển sang năm sau.\n5. Xem quy định nghỉ phép nửa ngày và nghỉ gộp nhiều ngày.\n6. Ghi lại các trường hợp ngoại lệ cần trưởng bộ phận phê duyệt.",
      agents: [
        agent(
          "agent-1",
          0,
          0,
          "Tra sổ tay nhân sự để xác định số ngày phép hằng năm và cách cộng thêm theo thâm niên.",
          "Sổ tay nhân sự ghi **12 ngày phép** mỗi năm cho nhân viên chính thức, cộng một ngày cho mỗi 5 năm làm việc [1].\n\nNăm đầu tiên tính theo số tháng làm việc thực tế, nên người vào giữa năm nhận số ngày theo tỷ lệ [1].\n\nPhép chưa dùng được chuyển tối đa **5 ngày** sang quý I năm sau, phần còn lại hết hiệu lực [1].",
          [
            step("s1", "search_knowledge", ["nghỉ phép hằng năm", "ngày phép thâm niên"], 0),
            step("s2", "read_file", [], 1),
          ],
          92_000,
        ),
        agent(
          "agent-2",
          0,
          1,
          "Tìm quy trình duyệt đơn nghỉ phép, ai duyệt và cần báo trước bao lâu.",
          "Đơn nghỉ phép gửi trước **3 ngày làm việc**, quản lý trực tiếp duyệt [1].",
          [step("s3", "search_knowledge", ["quy trình duyệt nghỉ phép"], 0)],
          64_000,
        ),
        agent(
          "agent-3",
          1,
          0,
          "Đối chiếu quy chế nội bộ 2026 xem có thay đổi nào so với sổ tay nhân sự.",
          "Quy chế 2026 giữ nguyên 12 ngày phép nhưng yêu cầu **trưởng bộ phận duyệt** khi nghỉ liên tục quá 5 ngày [1].",
          [
            step("s4", "search_knowledge", ["quy chế nội bộ 2026 nghỉ phép"], 0),
            step("s5", "web_search", ["luật lao động ngày nghỉ hằng năm 2026"], 1),
          ],
          38_000,
        ),
      ],
    },
  };
  value.messages.push(user, assistant);
  value.allMessages.set(user.id, user);
  value.allMessages.set(assistant.id, assistant);
}

/** A saved answer that used a connected MCP server, then was refused by it for authorization. */
function seedMcp(value: Session) {
  const now = new Date().toISOString();
  const userId = randomUUID();
  const assistantId = randomUUID();
  const base = { textOffset: 0, queries: [], documents: [], citations: [] };
  const user: ChatMessage = {
    id: userId,
    sessionId: value.session.id,
    parentMessageId: value.session.rootMessageId,
    latestChildMessageId: assistantId,
    role: "USER",
    content: "Tìm báo cáo doanh thu quý 3 trên Drive và tóm tắt giúp tôi.",
    status: "COMPLETED",
    createdAt: now,
    finishedAt: now,
    sources: [],
    artifacts: [],
    files: [],
    images: [],
    generatedFiles: [],
    activity: { steps: [], reasoning: [] },
    research: { clarification: false, plan: null, agents: [] },
  };
  const assistant: ChatMessage = {
    id: assistantId,
    sessionId: value.session.id,
    parentMessageId: userId,
    latestChildMessageId: null,
    role: "ASSISTANT",
    content:
      "Tôi tìm thấy **Báo cáo doanh thu Q3 2026** trên Drive, nhưng Google Drive đã từ chối khi tôi mở nội dung tệp. Bạn kết nối lại Google Drive rồi hỏi lại để tôi tóm tắt.",
    status: "COMPLETED",
    createdAt: now,
    finishedAt: now,
    sources: [],
    artifacts: [],
    files: [],
    images: [],
    generatedFiles: [],
    activity: {
      steps: [
        {
          ...base,
          position: 0,
          toolCallId: "mcp-1",
          toolName: "mcp_drive_search_files",
          status: "COMPLETED",
          startedAt: now,
          durationMs: 1800,
        },
        {
          ...base,
          position: 1,
          toolCallId: "mcp-2",
          toolName: "mcp_drive_read_file_content",
          status: "FAILED",
          startedAt: now,
          durationMs: 420,
          failure: "AUTHORIZATION_REQUIRED",
        },
      ],
      reasoning: [],
    },
    research: { clarification: false, plan: null, agents: [] },
  };
  value.messages.push(user, assistant);
  value.allMessages.set(user.id, user);
  value.allMessages.set(assistant.id, assistant);
}

// Like the real backend, every conversation starts with the builtin agent, which allows every tool.
const builtinPersonaId = "00000000-0000-4000-8000-00000000b017";
const builtinPersona = {
  id: builtinPersonaId,
  builtin: true,
  permissions: {},
  revision: 0,
  name: "MemoryOS",
  description: "",
  instructions: "",
  starterPrompts: [],
  sourceIds: [],
  tools: ["search", "web_search", "image_generation", "code_interpreter"],
  mcpServers: [],
};

function create(title = "Browser conversation", mode = "normal"): Session {
  const now = new Date().toISOString();
  const session = {
    id: randomUUID(),
    rootMessageId: randomUUID(),
    personaId: builtinPersonaId,
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
  if (mode === "research") seedResearch(value);
  if (mode === "mcp") seedMcp(value);
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
    hasArtifacts: message.artifacts.length > 0,
    failureCode: status === "FAILED" ? "CHAT_PROVIDER_FAILED" : null,
  });
  for (const listener of run.listeners) listener.end();
  run.listeners.clear();
}

// TEMPORARY BENCH (not to be committed)
function benchContent(): { reasoning: string; answer: string } {
  const reasoning = Array.from(
    { length: 14 },
    (_, i) =>
      `**Bước ${i + 1}: Phân tích dữ liệu**\n\nMình cần đọc file doanh thu, tổng hợp theo miền Bắc, Trung, Nam và so sánh quý 2 với quý 3 trước khi viết báo cáo.`,
  ).join("\n\n");
  const section = (i: number) =>
    `## ${i}. Doanh thu miền ${["Bắc", "Trung", "Nam"][i % 3]}\n\n` +
    `Doanh thu quý 3 đạt **${(4.1 + i * 0.37).toFixed(2)} tỷ ₫**, tăng ${(8 + i).toFixed(1)}% so với quý 2 nhờ kênh bán lẻ tại Hà Nội và Hải Phòng [1]. Xem [báo cáo gốc](https://example.com/bao-cao).\n\n` +
    `| Tháng | Cửa hàng (₫) | Trực tuyến (₫) | Tổng (₫) |\n|---|---:|---:|---:|\n` +
    [7, 8, 9]
      .map((m) => `| ${m}/2026 | ${(1200 + i * 31 + m).toLocaleString("vi-VN")}.000.000 | ${(560 + i * 17).toLocaleString("vi-VN")}.000.000 | ${(1760 + i * 48).toLocaleString("vi-VN")}.000.000 |`)
      .join("\n") +
    `\n\n- Tỷ trọng kênh trực tuyến tăng đều qua các tháng.\n- Chi phí vận hành giảm ${(2 + i / 3).toFixed(1)}%.\n\n` +
    "```python\nimport pandas as pd\ndf = pd.read_excel('doanh-thu-2026-theo-mien.xlsx')\n" +
    `q3 = df[df['Tháng'].isin(['07/2026','08/2026','09/2026'])]\nprint(q3.groupby('Miền')['Doanh thu (₫)'].sum())  # phần ${i}\n` +
    "```\n\n";
  return { reasoning, answer: Array.from({ length: 12 }, (_, i) => section(i + 1)).join("") };
}
function benchStream(state: Session, run: Run) {
  const { reasoning, answer } = benchContent();
  const poll = state.mode.includes("poll");
  const tokens: Array<[string, string]> = [];
  for (let i = 0; i < reasoning.length; i += 4) tokens.push(["reasoning", reasoning.slice(i, i + 4)]);
  for (let i = 0; i < answer.length; i += 4) tokens.push(["text-delta", answer.slice(i, i + 4)]);
  let sent = 0;
  let pending = "";
  let pendingType = "reasoning";
  const quiet = (event: string, data: object) => {
    const sequence = run.packets.length + 1;
    run.packets.push(
      `id: ${run.id}:${sequence}\nevent: ${event}\ndata: ${JSON.stringify({ assistantMessageId: run.id, sequence, ...data })}\n\n`,
    );
  };
  const deliver = () => {
    for (const packet of run.packets.slice(sent)) for (const l of run.listeners) l.write(packet);
    sent = run.packets.length;
  };
  const chunk = () => {
    if (!pending) return;
    quiet(pendingType, { text: pending });
    pending = "";
    if (!poll) deliver();
  };
  let index = 0;
  const tokenTimer = setInterval(() => {
    const token = tokens[index++];
    if (!token) return;
    if (token[0] !== pendingType) {
      chunk();
      pendingType = token[0];
    }
    pending += token[1];
  }, 20);
  const flushTimer = setInterval(chunk, 25);
  const pollTimer = poll ? setInterval(deliver, 200) : undefined;
  const done = setInterval(() => {
    if (index < tokens.length) return;
    clearInterval(tokenTimer);
    clearInterval(flushTimer);
    chunk();
    if (pollTimer) clearInterval(pollTimer);
    deliver();
    clearInterval(done);
    state.messages.at(-1)!.content = answer;
    state.messages.at(-1)!.sources = [fixtureSource];
    state.messages.at(-1)!.activity = { steps: [], reasoning: [{ position: 0, textOffset: 0, text: reasoning }] };
    setTimeout(() => {
      finish(state, run, "COMPLETED");
      sent = run.packets.length;
    }, 250);
  }, 50);
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
  if (url.pathname === "/api/chat/personas" && request.method === "GET") {
    json(response, [builtinPersona]);
    return true;
  }
  if (
    [
      "/api/chat/personas/sources",
      "/api/chat/persona-pins",
      "/api/chat/persona-labels",
      "/api/chat/prompt-shortcuts",
    ].includes(url.pathname) &&
    request.method === "GET"
  ) {
    json(response, []);
    return true;
  }
  if (url.pathname === "/api/chat/prompt-shortcuts/preferences") {
    json(response, { enabled: true });
    return true;
  }
  if (url.pathname === "/api/chat/test-fixture" && request.method === "POST") {
    const input = await body(request);
    const value = create(input.title, input.mode);
    if (input.mode === "files")
      for (const message of await generatedFileMessages(
        value.session.id,
        value.session.rootMessageId,
      )) {
        value.messages.push(message);
        value.allMessages.set(message.id, message);
      }
    json(response, value.session);
    return true;
  }
  if (!url.pathname.startsWith("/api/chat/")) return false;
  if (await handleGeneratedFile(url.pathname, response)) return true;
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
    } else {
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
    }
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
    if (request.method === "POST") {
      if (state.mode === "naming") state.session.title = "Tiêu đề tự động";
      json(response, state.session);
      return true;
    }
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
        artifacts: [],
        sources: [],
        files: [],
        activity: { steps: [], reasoning: [] },
        images: [],
        generatedFiles: [],
        research: { clarification: false, plan: null, agents: [] },
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
        artifacts: [],
        sources: [],
        files: [],
        activity: { steps: [], reasoning: [] },
        images: [],
        generatedFiles: [],
        research: { clarification: false, plan: null, agents: [] },
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
    if (state.mode.startsWith("bench")) {
      benchStream(state, run);
      return true;
    }
    const grounded = state.mode.startsWith("grounded");
    if (state.mode === "grounded-progress")
      emit(run, "reasoning", { text: "Checking the latest HR policy before answering." });
    if (grounded)
      emit(run, "tool", {
        toolCallId: "search-1",
        toolName: "search_knowledge",
        stage: "STARTED",
        source: null,
      });
    if (state.mode === "grounded-progress") {
      // Keep committed activity in step with the stream, so a Stop before completion restores it too.
      state.messages.at(-1)!.activity = {
        steps: [
          {
            position: 1,
            toolCallId: "search-1",
            toolName: "search_knowledge",
            status: "FAILED",
            startedAt: new Date().toISOString(),
            durationMs: 400,
            textOffset: 0,
            queries: ["annual leave policy", "HR-2026"],
            documents: [],
            citations: [],
          },
        ],
        reasoning: [
          { position: 0, textOffset: 0, text: "Checking the latest HR policy before answering." },
        ],
      };
      emit(run, "tool", {
        toolCallId: "search-1",
        toolName: "search_knowledge",
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
      emit(run, "tool", {
        toolCallId: "search-1",
        toolName: "search_knowledge",
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
              : state.mode === "renderers"
                ? answer + "\n\n```mermaid\ngraph TD\n  Upload --> Parse\n  Parse --> Answer\n```"
                : answer;
        if (state.mode === "renderers")
          state.messages.at(-1)!.artifacts = [
            {
              id: randomUUID(),
              title: "Revenue report — September / Báo cáo doanh thu tháng 9",
              spec: JSON.stringify({
                root: {
                  component: "Card",
                  props: { title: "Revenue / Doanh thu" },
                  children: [
                    {
                      component: "Metric",
                      props: { label: "September / Tháng 9", value: "125,000" },
                    },
                    {
                      component: "Text",
                      props: { text: "<script>alert('never execute')</script>" },
                    },
                    {
                      component: "Table",
                      children: [
                        {
                          component: "Row",
                          children: [
                            { component: "Cell", props: { text: "ORION" } },
                            { component: "Cell", props: { text: "125,000" } },
                          ],
                        },
                      ],
                    },
                  ],
                },
              }),
            },
          ];
        if (grounded) {
          state.messages.at(-1)!.sources = [fixtureSource];
          state.messages.at(-1)!.activity = {
            steps: [
              {
                position: 0,
                toolCallId: "search-1",
                toolName: "search_knowledge",
                status: "COMPLETED",
                startedAt: new Date().toISOString(),
                durationMs: 1200,
                textOffset: 0,
                queries: ["annual leave policy"],
                documents: [],
                citations: [1],
              },
            ],
            reasoning: [],
          };
          const tool = { toolCallId: "search-1", toolName: "search_knowledge" };
          emit(run, "tool", { ...tool, stage: "SOURCE", source: fixtureSource });
          emit(run, "tool", { ...tool, stage: "COMPLETED", source: null, durationMs: 1200 });
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
