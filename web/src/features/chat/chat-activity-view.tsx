import { useMemo, useState, type ReactNode } from "react";
import { useAuiState, type EnrichedPartState } from "@assistant-ui/react";
import {
  Brain,
  FileText,
  Globe,
  ImageIcon,
  LayoutDashboard,
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
import { toolProgressSchema, type ToolProgress } from "./chat-activity";
import type { CodeRun } from "./chat-code";
import type { ChatSource } from "./chat-evidence";

type ToolPart = Extract<EnrichedPartState, { type: "tool-call" }>;
type ToolState = "running" | "done" | "failed";
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
  const sources = filters.sources
    .map((source) => (source === "FILE" ? ui("Tệp tải lên") : "Google Drive"))
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
    case "searchKnowledge": {
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
    case "render_gui":
      return running ? ui("Đang tạo thẻ trình bày…") : ui("Đã tạo thẻ trình bày");
    case "generate_image":
      return running ? ui("Đang tạo ảnh…") : ui("Đã tạo ảnh");
    case "edit_image":
      return running ? ui("Đang sửa ảnh…") : ui("Đã sửa ảnh");
    case "run_python":
      return running ? ui("Đang chạy Python…") : ui("Đã chạy Python");
    default:
      return running ? ui("Đang dùng công cụ…") : ui("Đã dùng công cụ");
  }
}

/** The group header names the live phase while the assistant works. */
function liveTitle(ui: Translate, tool: { toolName: string; args: unknown }) {
  const stage = toolProgress(tool.args).stage;
  if (tool.toolName === "searchKnowledge") {
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
    case "render_gui":
      return ui("Đang tạo thẻ trình bày…");
    case "generate_image":
      return ui("Đang tạo ảnh…");
    case "edit_image":
      return ui("Đang sửa ảnh…");
    case "run_python":
      return ui("Đang chạy Python…");
    default:
      return ui("Đang dùng công cụ…");
  }
}

function toolIcon(name: string) {
  switch (name) {
    case "searchKnowledge":
      return <Search />;
    case "web_search":
    case "open_url":
      return <Globe />;
    case "read_file":
    case "search_files":
      return <FileText />;
    case "render_gui":
      return <LayoutDashboard />;
    case "generate_image":
    case "edit_image":
      return <ImageIcon />;
    case "run_python":
      return <SquareTerminal />;
    default:
      return <Wrench />;
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
  return (
    <div className="space-y-2 text-xs">
      <p>{ui("Mã đã chạy")}</p>
      <pre className="max-h-64 overflow-auto rounded-lg border border-border-subtle bg-surface-sunken p-2.5 font-mono text-[11px] leading-relaxed">
        <code>{run.code}</code>
      </pre>
      {run.output && (
        <>
          <p>{state === "running" ? ui("Kết quả đang chạy") : ui("Kết quả")}</p>
          <pre className="max-h-64 overflow-auto rounded-lg border border-border-subtle bg-surface-subtle p-2.5 font-mono text-[11px] leading-relaxed text-content-secondary">
            <code>{run.output}</code>
          </pre>
        </>
      )}
      {run.status === "failed" && <p role="status">{ui("Chạy Python không thành công")}</p>}
    </div>
  );
}

/** Spoken duration for the collapsed header, e.g. "14 giây" / "14 seconds". */
function spokenDuration(ms: number) {
  const seconds = Math.max(1, Math.round(ms / 1000));
  const format = (value: number, unit: "second" | "minute") =>
    new Intl.NumberFormat(uiLocale(), { style: "unit", unit, unitDisplay: "long" }).format(value);
  if (seconds < 60) return format(seconds, "second");
  const rest = seconds % 60;
  return [format(Math.floor(seconds / 60), "minute"), rest ? format(rest, "second") : ""]
    .filter(Boolean)
    .join(" ");
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
  const [manual, setManual] = useState<boolean>();
  const group = useMemo(
    () => indices.flatMap((index) => (parts[index] ? [parts[index]] : [])),
    [parts, indices],
  );
  const tools = group.flatMap((part) => (part.type === "tool-call" ? [part] : []));
  const open = manual ?? (running && !answerStarted);
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
  if (running) {
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
      <ActivityGroupTrigger label={label} active={running} steps={running ? undefined : steps} />
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
      title={running ? ui("Đang suy nghĩ…") : ui("Suy nghĩ")}
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
  const searching = ["searchKnowledge", "web_search", "search_files"].includes(part.toolName);
  // Reading candidates while searching; the evidence the step actually returned once it finished.
  const reading =
    part.toolName === "searchKnowledge" && progress.documents.length > 0 && state === "running"
      ? progress.documents.map((document) => ({
          key: `${document.documentId}:${document.startOrdinal}`,
          icon: <FileText />,
          label: document.title,
        }))
      : part.toolName === "read_file"
        ? []
        : cited.map((source) => ({
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
