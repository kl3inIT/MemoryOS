import { useMemo, useState, type ReactNode } from "react";
import { useAuiState, type EnrichedPartState } from "@assistant-ui/react";
import {
  Blocks,
  Brain,
  FileText,
  Globe,
  ImageIcon,
  Search,
  SquareTerminal,
  Wrench,
} from "lucide-react";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  ActivityChips,
  ActivityGroupContent,
  ActivityGroupRoot,
  ActivityGroupTrigger,
  ActivityStep,
} from "@/components/assistant-ui/elements/activity-group";
import { MarkdownText } from "@/components/assistant-ui/elements/markdown-text";
import { SourceIcon } from "@/components/assistant-ui/elements/source-icon";
import { DocumentSourceIcon } from "@/features/search/document-source-icon";
import { documentSourceLabels } from "@/features/search/document-source-presentation";
import { toolProgressSchema, type ToolProgress } from "./chat-activity";
import type { CodeRun } from "@/features/chat/interpreter/chat-code";
import { HighlightedCode } from "@/components/assistant-ui/elements/code-renderers.aui";
import { spokenDuration } from "./chat-duration";
import type { ChatSource } from "@/features/chat/sources/chat-evidence";
import { parseMcpToolName } from "@/features/chat/mcp/chat-mcp-connections";
import { ChatMcpToolStep } from "@/features/chat/mcp/chat-mcp-step";

type ToolPart = Extract<EnrichedPartState, { type: "tool-call" }>;
type ToolState = "running" | "done" | "failed";
/** Internal search was renamed to snake case; answers saved before that still carry the old name. */
function isInternalSearch(name: string) {
  return name === "search_knowledge" || name === "searchKnowledge";
}

type Translate = ReturnType<typeof useAppTranslation>;
const emptySources: ChatSource[] = [];

function toolProgress(args: unknown): ToolProgress {
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
function toolState(
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
function stepTitle(
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
function liveTitle(ui: Translate, tool: { toolName: string; args: unknown }) {
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

/** A research agent's step, titled from its tool name alone: its history keeps no filters or evidence. */
export function ChatResearchToolStep({
  toolName,
  status,
  children,
}: {
  toolName: string;
  status: ToolState;
  children?: ReactNode;
}) {
  const ui = useAppTranslation();
  return (
    <ActivityStep
      icon={toolIcon(toolName)}
      status={status}
      title={stepTitle(ui, { toolName } as ToolPart, toolProgress(undefined), [], status)}
    >
      {children}
    </ActivityStep>
  );
}

function toolIcon(name: string) {
  switch (name) {
    case "searchKnowledge":
    case "search_knowledge":
      return <Search />;
    case "web_search":
    case "open_url":
      return <Globe />;
    case "read_file":
    case "search_files":
      return <FileText />;
    case "generate_image":
    case "edit_image":
      return <ImageIcon />;
    case "run_python":
      return <SquareTerminal />;
    default:
      return parseMcpToolName(name) ? <Blocks /> : <Wrench />;
  }
}

/** The code run_python executed and the output it produced, streamed while it runs. */
function ChatCodeStep({ toolCallId, state }: { toolCallId: string; state: ToolState }) {
  const ui = useAppTranslation();
  const run = useAuiState(
    (aui) =>
      (aui.message.metadata.custom.codeRuns as Record<string, CodeRun> | undefined)?.[toolCallId],
  );
  if (!run?.code) return null;
  // Onyx PythonToolRenderer: code, then Output, then Error, the generated file count, and a no-output note.
  return (
    <div className="space-y-2 text-xs">
      <div className="text-[12px] [&_pre]:max-h-64 [&_pre]:rounded-lg! [&_pre]:border-t!">
        <HighlightedCode code={run.code.trim()} language="python" />
      </div>
      {run.stdout && (
        <section aria-label={ui("Kết quả")} className="rounded-lg bg-surface-subtle p-2.5">
          <p className="mb-1 font-medium text-content-muted">
            {state === "running" ? ui("Kết quả đang chạy") : ui("Kết quả")}
          </p>
          <pre className="max-h-64 overflow-auto whitespace-pre-wrap font-mono text-[11px] leading-relaxed text-content-primary">
            {run.stdout}
          </pre>
        </section>
      )}
      {run.stderr && (
        <section
          aria-label={ui("Lỗi")}
          className="rounded-lg border border-destructive/30 bg-destructive/5 p-2.5"
        >
          <p className="mb-1 font-medium text-destructive">{ui("Lỗi")}</p>
          <pre className="max-h-64 overflow-auto whitespace-pre-wrap font-mono text-[11px] leading-relaxed text-destructive">
            {run.stderr}
          </pre>
        </section>
      )}
      {run.files.length > 0 && (
        <p className="text-content-muted">
          {ui("Đã tạo {{count}} tệp", { count: run.files.length })}
        </p>
      )}
      {run.status !== "running" && !run.stdout && !run.stderr && (
        <p className="text-content-muted">{ui("Không có output")}</p>
      )}
    </div>
  );
}

function readingKey(source: {
  citationId: number;
  documentId?: string | null;
  web?: { url: string } | null;
}) {
  return source.web ? source.web.url : (source.documentId ?? String(source.citationId));
}

/** One disclosure for adjacent reasoning and tool steps; open while working, collapsed once the answer starts. */
export function ChatActivityGroup({
  indices,
  running,
  children,
}: {
  indices: readonly number[];
  running: boolean;
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const parts = useAuiState((state) => state.message.parts);
  const last = indices.at(-1) ?? -1;
  const answerStarted = useAuiState((state) =>
    state.message.parts.some(
      (part, index) => index > last && part.type === "text" && part.text.trim().length > 0,
    ),
  );
  // The last group stays live while the run continues with nothing after it: the model is writing its next call,
  // and "Thought for 3 seconds" there reads as a stalled answer.
  const pending = useAuiState(
    (state) => state.message.status?.type === "running" && state.message.parts.length - 1 === last,
  );
  const live = running || (pending && !answerStarted);
  const [manual, setManual] = useState<boolean>();
  const group = useMemo(
    () => indices.flatMap((index) => (parts[index] ? [parts[index]] : [])),
    [parts, indices],
  );
  const tools = group.flatMap((part) => (part.type === "tool-call" ? [part] : []));
  // A step the person must act on (reconnect) stays visible after the answer, instead of hiding its action.
  const actionable = tools.some(
    (tool) =>
      toolState(tool, running) === "failed" &&
      toolProgress(tool.args).failure === "AUTHORIZATION_REQUIRED",
  );
  const open = manual ?? ((live && !answerStarted) || actionable);
  const stopped = useAuiState(
    (state) =>
      state.message.status?.type === "incomplete" ||
      state.message.metadata.custom.serverStatus === "CANCELED" ||
      state.message.metadata.custom.serverStatus === "FAILED",
  );
  const duration = tools.reduce(
    (total, tool) => total + (toolProgress(tool.args).durationMs ?? 0),
    0,
  );
  let label: string;
  if (live) {
    const current = tools.findLast((tool) => toolState(tool, running) === "running");
    label = current ? liveTitle(ui, current) : ui("Đang suy nghĩ…");
  } else if (stopped) {
    label = ui("Đã dừng suy nghĩ");
  } else {
    label =
      duration > 0
        ? ui("Đã suy nghĩ trong {{duration}}", { duration: spokenDuration(duration) })
        : ui("Đã suy nghĩ");
  }
  const steps = indices.length === 1 ? ui("1 bước") : ui("{{n}} bước", { n: indices.length });
  return (
    <ActivityGroupRoot open={open} onOpenChange={setManual}>
      <ActivityGroupTrigger label={label} active={live} steps={live ? undefined : steps} />
      <ActivityGroupContent>{children}</ActivityGroupContent>
    </ActivityGroupRoot>
  );
}

export function ChatReasoningStep({ running }: { running: boolean }) {
  const ui = useAppTranslation();
  return (
    <ActivityStep
      icon={<Brain />}
      status={running ? "running" : "done"}
      // The group header already says "Thinking…" while it runs; the step spinner shows it is live.
      title={ui("Suy nghĩ")}
    >
      <div className="text-sm [&_.aui-md]:text-sm [&_.aui-md]:leading-6 [&_.aui-md]:text-content-muted">
        <MarkdownText />
      </div>
    </ActivityStep>
  );
}

function hostname(url: string) {
  return new URL(url).hostname.replace(/^www\./, "");
}

export function ChatToolStep({ part }: { part: ToolPart }) {
  const ui = useAppTranslation();
  const sources = useAuiState(
    (state) => (state.message.metadata.custom.sources as ChatSource[] | undefined) ?? emptySources,
  );
  const progress = toolProgress(part.args);
  const lateCitations = useAuiState(
    (state) =>
      (state.message.metadata.custom.toolCitations as Record<string, number[]> | undefined)?.[
        part.toolCallId
      ],
  );
  const citationIds = [...new Set([...progress.citations, ...(lateCitations ?? [])])];
  const messageRunning = useAuiState((state) => state.message.status?.type === "running");
  const state = toolState(part, messageRunning);
  const cited = citationIds.flatMap((id) => {
    const source = sources.find((candidate) => candidate.citationId === id);
    return source ? [source] : [];
  });
  const searching = ["search_knowledge", "searchKnowledge", "web_search", "search_files"].includes(
    part.toolName,
  );
  // Reading candidates while searching; the evidence the step actually returned once it finished.
  const reading =
    isInternalSearch(part.toolName) && progress.documents.length > 0 && state === "running"
      ? progress.documents.map((document) => ({
          key: `${document.documentId}:${document.startOrdinal}`,
          icon: <FileText />,
          label: document.title,
        }))
      : part.toolName === "read_file"
        ? []
        : // One chip per document or page: several passages of one file are one thing read, not twenty.
          cited
            .filter(
              (source, index) =>
                cited.findIndex((other) => readingKey(other) === readingKey(source)) === index,
            )
            .map((source) => ({
              key: String(source.citationId),
              icon: source.web ? (
                <SourceIcon domain={hostname(source.web.url)} fallback="globe" />
              ) : (
                <DocumentSourceIcon
                  size="xs"
                  mediaType={source.mediaType}
                  sourceTypes={source.sourceTypes}
                />
              ),
              label: source.web ? hostname(source.web.url) : source.title,
              title: source.title,
            }));
  const mcp = parseMcpToolName(part.toolName);
  if (mcp)
    return <ChatMcpToolStep slug={mcp.slug} tool={mcp.tool} progress={progress} state={state} />;
  const queries = progress.queries.map((query) => ({ key: query, icon: <Search />, label: query }));
  const noResults = searching && state === "done" && progress.queries.length > 0 && !reading.length;
  return (
    <ActivityStep
      icon={toolIcon(part.toolName)}
      status={state}
      title={stepTitle(ui, part, progress, cited, state)}
    >
      {part.toolName === "run_python" && (
        <ChatCodeStep toolCallId={part.toolCallId} state={state} />
      )}
      {(queries.length > 0 || reading.length > 0 || noResults) && (
        <div className="space-y-1.5 text-xs">
          {queries.length > 0 && (
            <ActivityChips items={queries} moreLabel={(n) => ui("+{{n}}", { n })} />
          )}
          {reading.length > 0 && (
            <>
              <p>{state === "running" ? ui("Đang đọc") : ui("Đã đọc")}</p>
              <ActivityChips items={reading} moreLabel={(n) => ui("+{{n}}", { n })} />
            </>
          )}
          {noResults && <p>{ui("Không tìm thấy kết quả")}</p>}
        </div>
      )}
    </ActivityStep>
  );
}
