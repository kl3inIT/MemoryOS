import type { EnrichedPartState } from "@assistant-ui/react";
import { uiLocale } from "@/i18n/format";
import type { useAppTranslation } from "@/i18n/use-app-translation";
import { documentSourceLabels } from "@/features/search/document-source-presentation";
import type { ChatSource } from "@/features/chat/sources/chat-evidence";
import { parseMcpToolName } from "@/features/mcp/mcp-connections";
import { toolProgressSchema, type ToolProgress } from "./chat-activity";

export type ToolPart = Extract<EnrichedPartState, { type: "tool-call" }>;
export type ToolState = "running" | "done" | "failed";
/** Internal search was renamed to snake case; answers saved before that still carry the old name. */
export function isInternalSearch(name: string) {
  return name === "search_knowledge" || name === "searchKnowledge";
}

type Translate = ReturnType<typeof useAppTranslation>;

export function toolProgress(args: unknown): ToolProgress {
  const parsed = toolProgressSchema.safeParse(args);
  return parsed.success
    ? parsed.data
    : {
        stage: "STARTED",
        queries: [],
        filters: null,
        documents: [],
        citations: [],
        durationMs: null,
        failure: null,
      };
}

/** A step without a result only runs while its message runs; a stopped or failed turn leaves it failed. */
export function toolState(
  part: { isError?: boolean; result?: unknown; status: { type: string } },
  messageRunning: boolean,
): ToolState {
  if (part.isError) return "failed";
  if (part.result !== undefined) return "done";
  return messageRunning && part.status.type !== "incomplete" ? "running" : "failed";
}

/** Date only, as filters are day bounds; the Onyx timeline uses the same since/before/from–to wording. */
function filterDate(value: string) {
  return new Intl.DateTimeFormat(uiLocale(), { dateStyle: "medium", timeZone: "UTC" }).format(
    new Date(value),
  );
}

/** "Tệp tải lên (từ 1 thg 9, 2026)" or undefined when the search had no effective filter. */
function searchScope(ui: Translate, filters: ToolProgress["filters"]) {
  if (!filters) return undefined;
  const sources = documentSourceLabels(null, filters.sources)
    .providers.map((provider) => ui(provider))
    .join(", ");
  const bounds = filters.updated ?? filters.created;
  const window =
    bounds?.from && bounds.to
      ? ui("từ {{start}} đến {{end}}", {
          start: filterDate(bounds.from),
          end: filterDate(bounds.to),
        })
      : bounds?.from
        ? ui("từ {{date}}", { date: filterDate(bounds.from) })
        : bounds?.to
          ? ui("trước {{date}}", { date: filterDate(bounds.to) })
          : undefined;
  if (!sources && !window) return undefined;
  const scope = sources || ui("tài liệu");
  return window ? ui("{{scope}} ({{window}})", { scope, window }) : scope;
}

/** The step's own label: what it did and where, never raw arguments. */
export function stepTitle(
  ui: Translate,
  part: ToolPart,
  progress: ToolProgress,
  cited: ChatSource[],
  state: ToolState,
) {
  const running = state === "running";
  switch (part.toolName) {
    case "searchKnowledge":
    case "search_knowledge": {
      const scope = searchScope(ui, progress.filters);
      if (scope)
        return running
          ? ui("Đang tìm trong {{scope}}…", { scope })
          : ui("Đã tìm trong {{scope}}", { scope });
      return running ? ui("Đang tìm trong tài liệu…") : ui("Đã tìm trong tài liệu");
    }
    case "web_search":
      return running ? ui("Đang tìm trên Web…") : ui("Đã tìm trên Web");
    case "open_url":
      return running ? ui("Đang đọc trang…") : ui("Đã đọc trang");
    case "read_file": {
      const file = cited.find((source) => source.fileId)?.title;
      if (running) return ui("Đang đọc tệp…");
      return file ? ui("Đã đọc {{file}}", { file }) : ui("Đã đọc tệp");
    }
    case "search_files":
      return running ? ui("Đang tìm trong tệp…") : ui("Đã tìm trong tệp");
    case "generate_image":
      return running ? ui("Đang tạo ảnh…") : ui("Đã tạo ảnh");
    case "edit_image":
      return running ? ui("Đang sửa ảnh…") : ui("Đã sửa ảnh");
    case "run_python":
      if (state === "failed") return ui("Chạy Python không thành công");
      return running ? ui("Đang chạy Python…") : ui("Đã chạy Python");
    default:
      return running ? ui("Đang dùng công cụ…") : ui("Đã dùng công cụ");
  }
}

/** The group header names the live phase while the assistant works. */
export function liveTitle(ui: Translate, tool: { toolName: string; args: unknown }) {
  const stage = toolProgress(tool.args).stage;
  if (isInternalSearch(tool.toolName)) {
    if (stage === "SELECTING") return ui("Đang chọn đoạn liên quan…");
    if (stage === "EXPANDING" || stage === "SOURCE") return ui("Đang đọc ngữ cảnh tài liệu…");
    return ui("Đang tìm trong tài liệu…");
  }
  switch (tool.toolName) {
    case "web_search":
      return ui("Đang tìm trên Web…");
    case "open_url":
      return ui("Đang đọc trang…");
    case "read_file":
      return ui("Đang đọc tệp…");
    case "search_files":
      return ui("Đang tìm trong tệp…");
    case "generate_image":
      return ui("Đang tạo ảnh…");
    case "edit_image":
      return ui("Đang sửa ảnh…");
    case "run_python":
      return ui("Đang chạy Python…");
    default: {
      const mcp = parseMcpToolName(tool.toolName);
      return mcp ? ui("Đang dùng {{tool}}…", { tool: mcp.tool }) : ui("Đang dùng công cụ…");
    }
  }
}
