import { useMemo, useState, type ReactNode } from "react";
import { useAuiState } from "@assistant-ui/react";
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
import { DocumentSourceIcon } from "@/features/documents/document-source-icon";
import { spokenDuration } from "./chat-duration";
import type { ChatSource } from "@/features/chat/sources/chat-evidence";
import { parseMcpToolName } from "@/features/mcp/mcp-connections";
import { ChatMcpToolStep } from "@/features/chat/mcp/chat-mcp-step";
import {
  isInternalSearch,
  liveTitle,
  stepTitle,
  toolProgress,
  toolState,
  type ToolPart,
  type ToolState,
} from "./chat-activity-labels";
import { ChatCodeStep } from "./chat-code-step";

const emptySources: ChatSource[] = [];

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
        <div className="flex flex-col gap-1.5 text-xs">
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
