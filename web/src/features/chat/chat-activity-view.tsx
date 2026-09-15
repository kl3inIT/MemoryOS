import { useMemo, useState, type ReactNode } from "react";
import { useAuiState, type EnrichedPartState } from "@assistant-ui/react";
import { Brain, FileText, Globe, LayoutDashboard, Search, Wrench } from "lucide-react";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  ActivityGroupContent,
  ActivityGroupRoot,
  ActivityGroupTrigger,
  ActivityStep,
} from "@/components/assistant-ui/elements/activity-group";
import { MarkdownText } from "@/components/assistant-ui/elements/markdown-text";
import { SourceIcon } from "@/components/assistant-ui/elements/source-icon";
import { field } from "@/components/assistant-ui/elements/surfaces";
import { WebSearch } from "@/components/assistant-ui/elements/web-search";
import { DocumentSourceIcon } from "@/features/search/document-source-icon";
import { cn } from "@/lib/utils";
import { toolProgressSchema, type ToolProgress } from "./chat-activity";
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

function toolState(part: {
  isError?: boolean;
  result?: unknown;
  status: { type: string };
}): ToolState {
  if (part.isError) return "failed";
  if (part.result !== undefined) return "done";
  return part.status.type === "incomplete" ? "failed" : "running";
}

function formatDuration(ms: number | null | undefined) {
  if (ms === null || ms === undefined) return undefined;
  if (ms < 1000) return "<1s";
  const seconds = Math.round(ms / 1000);
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

function toolTitle(ui: Translate, name: string, stage: ToolProgress["stage"], state: ToolState) {
  const running = state === "running";
  switch (name) {
    case "searchKnowledge":
      if (!running) return ui("Đã tìm trong tài liệu");
      if (stage === "SELECTING") return ui("Đang chọn đoạn liên quan…");
      if (stage === "EXPANDING" || stage === "SOURCE") return ui("Đang đọc ngữ cảnh tài liệu…");
      return ui("Đang tìm trong tài liệu…");
    case "web_search":
      return running ? ui("Đang tìm trên Web…") : ui("Đã tìm trên Web");
    case "open_url":
      return running ? ui("Đang đọc trang Web…") : ui("Đã đọc trang Web");
    case "read_file":
      return running ? ui("Đang đọc tệp…") : ui("Đã đọc tệp");
    case "search_files":
      return running ? ui("Đang tìm trong tệp…") : ui("Đã tìm trong tệp");
    case "render_gui":
      return running ? ui("Đang tạo thẻ trình bày…") : ui("Đã tạo thẻ trình bày");
    default:
      return running ? ui("Đang dùng công cụ…") : ui("Đã dùng công cụ");
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
    default:
      return <Wrench />;
  }
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
    const current = tools.findLast((tool) => toolState(tool) === "running");
    label = current
      ? toolTitle(ui, current.toolName, toolProgress(current.args).stage, "running")
      : ui("Đang suy luận…");
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
      title={running ? ui("Đang suy luận…") : ui("Suy luận")}
    >
      <div className="text-sm [&_.aui-md]:text-sm [&_.aui-md]:leading-6 [&_.aui-md]:text-content-muted">
        <MarkdownText />
      </div>
    </ActivityStep>
  );
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
  const state = toolState(part);
  const cited = citationIds.flatMap((id) => {
    const source = sources.find((candidate) => candidate.citationId === id);
    return source ? [source] : [];
  });
  const title = toolTitle(ui, part.toolName, progress.stage, state);
  const web = part.toolName === "web_search" || part.toolName === "open_url";
  const filters = progress.filters;
  const details =
    progress.queries.length > 0 ||
    !!filters?.sources.length ||
    !!filters?.created ||
    !!filters?.updated ||
    progress.documents.length > 0 ||
    cited.length > 0;
  return (
    <ActivityStep
      icon={toolIcon(part.toolName)}
      status={state}
      title={state === "failed" ? ui("{{step}} · không hoàn tất", { step: title }) : title}
      meta={state === "running" ? undefined : formatDuration(progress.durationMs)}
    >
      {web && details ? (
        <WebSearch
          query={progress.queries.join(" · ")}
          searching={state === "running"}
          label={
            state === "failed"
              ? ui("Không truy cập được nguồn Web.")
              : ui("Nguồn Web: {{count}}", { count: cited.length })
          }
          results={cited.flatMap((source) =>
            source.web
              ? [
                  {
                    title: source.title,
                    url: source.web.url,
                    domain: new URL(source.web.url).hostname,
                  },
                ]
              : [],
          )}
        />
      ) : details ? (
        <ToolDetails progress={progress} running={state === "running"} cited={cited} />
      ) : null}
    </ActivityStep>
  );
}

function ToolDetails({
  progress,
  running,
  cited,
}: {
  progress: ToolProgress;
  running: boolean;
  cited: ChatSource[];
}) {
  const ui = useAppTranslation();
  const filters = progress.filters;
  const bound = (value: string | null) => (value ? displayDate(value) : ui("Không giới hạn"));
  return (
    <div className="space-y-2 text-xs leading-5">
      {progress.queries.length > 0 && (
        <ul aria-label={ui("Truy vấn tìm kiếm")} className="flex flex-wrap gap-1.5">
          {progress.queries.map((query) => (
            <li
              key={query}
              className={cn(
                field,
                "inline-flex max-w-full items-center gap-1.5 rounded-full px-2.5 py-1",
              )}
            >
              <Search aria-hidden="true" className="size-3 shrink-0" />
              <span className="truncate" title={query}>
                {query}
              </span>
            </li>
          ))}
        </ul>
      )}
      {!!filters?.sources.length && (
        <p>
          {ui("Nguồn:")}{" "}
          {filters.sources
            .map((source) => (source === "FILE" ? ui("Tệp tải lên") : "Google Drive"))
            .join(", ")}
        </p>
      )}
      {filters?.created && (
        <p>
          {ui("Ngày tạo:")} {bound(filters.created.from)} – {bound(filters.created.to)}
        </p>
      )}
      {filters?.updated && (
        <p>
          {ui("Ngày cập nhật:")} {bound(filters.updated.from)} – {bound(filters.updated.to)}
        </p>
      )}
      {progress.documents.length > 0 && (
        <div>
          <p>{running ? ui("Đang đọc tài liệu") : ui("Tài liệu đã đọc")}</p>
          <ul className="mt-1 space-y-0.5">
            {progress.documents.map((document) => (
              <li
                key={`${document.documentId}:${document.startOrdinal}`}
                className="flex min-w-0 items-center gap-1.5"
              >
                <FileText aria-hidden="true" className="size-3 shrink-0" />
                <span className="truncate" title={document.title}>
                  {document.title}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {cited.length > 0 && (
        <ul aria-label={ui("Nguồn được trích dẫn")} className="space-y-0.5">
          {cited.map((source) => (
            <li key={source.citationId} className="flex min-w-0 items-center gap-1.5">
              {source.web ? (
                <SourceIcon
                  domain={new URL(source.web.url).hostname.replace(/^www\./, "")}
                  fallback="globe"
                />
              ) : (
                <DocumentSourceIcon
                  size="xs"
                  mediaType={source.mediaType}
                  sourceTypes={source.sourceTypes}
                />
              )}
              <span className="truncate" title={source.title}>
                {source.title}
              </span>
              <span className="shrink-0 tabular-nums">[{source.citationId}]</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function displayDate(value: string) {
  return (
    new Intl.DateTimeFormat(uiLocale(), {
      dateStyle: "medium",
      timeStyle: "short",
      timeZone: "UTC",
    }).format(new Date(value)) + " UTC"
  );
}
