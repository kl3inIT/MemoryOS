import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  createContext,
  useContext,
  useId,
  useRef,
  useState,
  type ComponentProps,
  type ReactNode,
} from "react";
import { useAuiState } from "@assistant-ui/react";
import { FileText } from "lucide-react";
import { InlineCitation } from "@/components/assistant-ui/elements/inline-citation";
import { SourceIcon } from "@/components/assistant-ui/elements/source-icon";
import { Sources } from "@/components/assistant-ui/elements/sources";
import { ThinkingIndicator } from "@/components/assistant-ui/elements/thinking-indicator";
import { WebSearch } from "@/components/assistant-ui/elements/web-search";
import { ChatSourcePanel, SourceExcerpt } from "./chat-source-panel";
import type { ChatSource, SearchProgress } from "./chat-evidence";
import { ChatPanelContext as PanelContext } from "./chat-panel-context";
import type { ChatArtifact } from "./chat-artifacts";

const emptySources: ChatSource[] = [];
const EvidenceContext = createContext<{ messageId: string; sources: ChatSource[] }>({
  messageId: "",
  sources: emptySources,
});

export function ChatSourcesWorkspace({ children }: { children: ReactNode }) {
  const panelId = useId();
  const [selection, setSelection] = useState<{
    messageId?: string;
    citationId?: number;
    file?: { id: string; filename: string };
    artifactId?: string;
  }>();
  const returnFocusRef = useRef<HTMLElement>(null);
  const fallbackFocusRef = useRef<HTMLDivElement>(null);
  // Read the selected message from the native runtime so an open panel follows
  // newly committed sources during streaming without copying message state.
  const sources = useAuiState(
    (state) =>
      (state.thread.messages.find((message) => message.id === selection?.messageId)?.metadata.custom
        .sources as ChatSource[] | undefined) ?? emptySources,
  );
  const artifact = useAuiState((state) =>
    (
      state.thread.messages.find((message) => message.id === selection?.messageId)?.metadata.custom
        .artifacts as ChatArtifact[] | undefined
    )?.find((item) => item.id === selection?.artifactId),
  );
  function restoreFocus() {
    const target = returnFocusRef.current?.isConnected
      ? returnFocusRef.current
      : fallbackFocusRef.current;
    target?.focus({ preventScroll: true });
  }
  function close() {
    setSelection(undefined);
    restoreFocus();
  }
  return (
    <PanelContext.Provider
      value={{
        panelId,
        messageId: selection?.messageId,
        fileId: selection?.file?.id,
        artifactId: selection?.artifactId,
        open: (messageId, trigger, citationId) => {
          returnFocusRef.current = trigger;
          setSelection({ messageId, citationId });
        },
        openFile: (file, trigger) => {
          returnFocusRef.current = trigger;
          setSelection({ file });
        },
        openArtifact: (messageId, artifactId, trigger) => {
          returnFocusRef.current = trigger;
          setSelection({ messageId, artifactId });
        },
        close,
      }}
    >
      <div
        ref={fallbackFocusRef}
        tabIndex={-1}
        className="flex min-h-0 min-w-0 flex-1 outline-none"
      >
        {children}
        {selection &&
          (selection.file || artifact || (!selection.artifactId && sources.length > 0)) && (
            <ChatSourcePanel
              id={panelId}
              sources={sources}
              file={selection.file}
              artifact={artifact}
              citationId={selection.citationId}
              onSelect={(citationId) => setSelection({ ...selection, citationId })}
              onClose={close}
              restoreFocus={restoreFocus}
            />
          )}
      </div>
    </PanelContext.Provider>
  );
}

export function ChatSourcesProvider({ children }: { children: ReactNode }) {
  const messageId = useAuiState((state) => state.message.id);
  const sources = useAuiState(
    (state) => (state.message.metadata.custom.sources as ChatSource[] | undefined) ?? emptySources,
  );
  return (
    <EvidenceContext.Provider value={{ messageId, sources }}>{children}</EvidenceContext.Provider>
  );
}

export function ChatSources() {
  const { messageId, sources } = useContext(EvidenceContext);
  const panel = useContext(PanelContext);
  const active = panel.messageId === messageId && !panel.artifactId;
  if (!sources.length) return null;
  return (
    <Sources
      count={sources.length}
      sources={sources.map((source) => ({ url: source.web?.url }))}
      aria-expanded={active}
      aria-controls={active ? panel.panelId : undefined}
      onClick={(event) => (active ? panel.close() : panel.open(messageId, event.currentTarget))}
    />
  );
}

function Citation({ source }: { source: ChatSource }) {
  const ui = useAppTranslation();

  const [open, setOpen] = useState(false);
  const { messageId } = useContext(EvidenceContext);
  const panel = useContext(PanelContext);
  return (
    <InlineCitation
      open={open}
      onOpenChange={setOpen}
      aria-label={ui("Mở nguồn {{v1}}: {{v2}}", { v1: source.citationId, v2: source.title })}
      onClick={(event) => {
        setOpen(false);
        panel.open(messageId, event.currentTarget, source.citationId);
      }}
      preview={
        <>
          <div className="mb-2 flex items-center gap-1.5 text-xs text-content-muted">
            <FileText className="size-3.5" aria-hidden="true" />{" "}
            {source.web ? ui("Web · Nguồn") : ui("Tài liệu · Nguồn")} {source.citationId}
          </div>
          <p className="text-sm font-medium leading-5">{source.title}</p>
          <SourceExcerpt source={source} />
        </>
      }
    >
      {source.citationId}. {source.title}
    </InlineCitation>
  );
}

export function ChatMarkdownLink({ href, children }: ComponentProps<"a">) {
  const { sources } = useContext(EvidenceContext);
  const citation = /^#citation-(\d{1,2})$/.exec(href ?? "");
  if (citation) {
    const source = sources.find((item) => item.citationId === Number(citation[1]));
    return source ? <Citation source={source} /> : <span>{children}</span>;
  }
  if (!href || !/^https?:\/\//i.test(href)) return <span>{children}</span>;
  const domain = new URL(href).hostname.replace(/^www\./, "");
  // Only hostnames of this answer's Web sources may reach the favicon service; other links,
  // possibly internal hosts written by the model, show the letter fallback.
  const webSource = sources.some(
    (source) => source.web && new URL(source.web.url).hostname.replace(/^www\./, "") === domain,
  );
  const label = typeof children === "string" && children !== href ? children : domain;
  // Inline URL source chip adapted from the assistant-ui Sources element (outline, sm).
  return (
    <a
      data-slot="source"
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      title={href}
      className="mx-0.5 inline-flex max-w-64 items-center gap-1.5 rounded-md border border-border-default px-1.5 py-0.5 align-middle text-xs text-content-secondary no-underline transition-colors hover:bg-surface-sunken hover:text-content-primary"
    >
      <SourceIcon domain={domain} favicon={webSource} />
      <span data-slot="source-title" className="truncate">
        {label}
      </span>
    </a>
  );
}

export function ChatSearchStatus() {
  const ui = useAppTranslation();

  const running = useAuiState((state) => state.message.status?.type === "running");
  const progress = useAuiState(
    (state) => state.message.metadata.custom.searchProgress as SearchProgress | undefined,
  );
  const hasText = useAuiState((state) =>
    state.message.parts.some((part) => part.type === "text" && part.text.trim().length > 0),
  );
  const sources = useAuiState(
    (state) => state.message.metadata.custom.sources as ChatSource[] | undefined,
  );
  const webEntries = Object.entries(progress ?? {}).filter(([id]) => id.startsWith("web-"));
  const webActive = webEntries.find(([, value]) => !["COMPLETED", "FAILED"].includes(value.stage));
  const webLatest = webActive ?? webEntries.at(-1);
  if (webLatest && (webActive || !running || !hasText)) {
    const [id, event] = webLatest;
    const results = (sources ?? []).flatMap((source) =>
      source.web
        ? [{ title: source.title, url: source.web.url, domain: new URL(source.web.url).hostname }]
        : [],
    );
    return (
      <WebSearch
        className="mb-3"
        query={event.search?.queries.join(" · ") ?? ""}
        results={results}
        searching={running && !!webActive}
        label={
          event.stage === "FAILED"
            ? ui("Không truy cập được nguồn Web.")
            : running && webActive
              ? id.startsWith("web-read-")
                ? ui("Đang đọc trang Web…")
                : ui("Đang tìm trên Web…")
              : ui("Nguồn Web: {{count}}", { count: results.length })
        }
      />
    );
  }
  if (!running) return null;
  const active = Object.values(progress ?? {}).find(
    (value) => !["COMPLETED", "FAILED"].includes(value.stage),
  );
  const stage = active?.stage;
  const label =
    stage === "SELECTING"
      ? "Đang chọn đoạn liên quan…"
      : stage === "EXPANDING" || stage === "SOURCE"
        ? "Đang đọc ngữ cảnh tài liệu…"
        : stage === "STARTED" || stage === "SEARCHING"
          ? "Đang tìm trong tài liệu…"
          : !hasText
            ? "Đang suy nghĩ…"
            : undefined;
  if (!label) return null;
  const filters = active?.search?.filters;
  return (
    <div className="mb-3 space-y-2 text-sm">
      <ThinkingIndicator role="status" label={ui(label)} />
      {active?.search && (
        <details className="text-muted-foreground">
          <summary className="cursor-pointer">{ui("Chi tiết tìm kiếm")}</summary>
          <ul className="mt-2 space-y-1 pl-4 list-disc">
            {active.search.queries.map((query) => (
              <li key={query} className="break-words">
                {query}
              </li>
            ))}
          </ul>
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
              {ui("Ngày tạo:")}{" "}
              {filters.created.from ? displayDate(filters.created.from) : ui("Không giới hạn")} –{" "}
              {filters.created.to ? displayDate(filters.created.to) : ui("Không giới hạn")}
            </p>
          )}
          {filters?.updated && (
            <p>
              {ui("Ngày cập nhật:")}{" "}
              {filters.updated.from ? displayDate(filters.updated.from) : ui("Không giới hạn")} –{" "}
              {filters.updated.to ? displayDate(filters.updated.to) : ui("Không giới hạn")}
            </p>
          )}
        </details>
      )}
      {!!active?.documents.length && (
        <div className="text-muted-foreground">
          <p>{ui("Đang đọc tài liệu")}</p>
          <ul className="mt-1 space-y-1 pl-4 list-disc">
            {active.documents.map((document) => (
              <li key={`${document.documentId}:${document.startOrdinal}`}>{document.title}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function displayDate(value: string | null) {
  return value
    ? new Intl.DateTimeFormat(uiLocale(), {
        dateStyle: "medium",
        timeStyle: "short",
        timeZone: "UTC",
      }).format(new Date(value)) + " UTC"
    : "Không giới hạn";
}
