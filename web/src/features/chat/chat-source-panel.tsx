import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Dialog } from "radix-ui";
import { ArrowLeft, ChevronLeft, ChevronRight, Maximize2, X } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { DocumentPreviewContent } from "@/features/search/document-preview-content";
import type { ChatSource } from "./chat-evidence";
import { ChatFileReader } from "./chat-file-reader";
import { useTranslation } from "react-i18next";
import type { ChatArtifact } from "./chat-artifacts";
import { ChatArtifactView } from "./chat-artifact-view";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  EvidenceViewSwitch,
  type EvidenceView,
  type OriginalEvidence,
} from "@/features/search/evidence-view-switch";
import {
  DocumentPreviewDialog,
  type DocumentSelection,
} from "@/features/search/document-preview-dialog";
import { client } from "@/lib/hey-api/client.gen";
import { citedPdfLocation } from "./chat-source-meta";
import { ChatSourceHeader, ChatSourceRow } from "./chat-source-list";

const WORD_DOCUMENT = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

/** The stored original of an indexed document citation: its cited PDF pages, or a whole Word document. */
function citationOriginal(source: ChatSource): OriginalEvidence | undefined {
  if (!source.documentId || !source.generation) return undefined;
  const { documentId, generation } = source;
  const url = client.buildUrl({
    url: "/api/chat/documents/{documentId}/original",
    path: { documentId },
    query: { generation },
  });
  const location = citedPdfLocation(source);
  if (location)
    return {
      kind: "pdf",
      url,
      pages: location.pages,
      boxes: location.boxes,
      table: location.table,
    };
  return source.mediaType === WORD_DOCUMENT ? { kind: "docx", url } : undefined;
}

/** The cited passages of an indexed document or file citation, read with Chat authority. */
function citationSelection(source: ChatSource): DocumentSelection {
  const ordinal = source.fileLocation?.ordinal;
  return {
    documentId: source.documentId ?? source.fileId!,
    generation: source.generation ?? source.fileLocation!.generation!,
    title: source.title,
    mediaType: source.mediaType,
    sourceTypes: source.sourceTypes,
    providerUrl: source.providerUrl,
    provenance: source.provenance.map((item) => item.provenanceJson),
    matches: [
      {
        from: Math.max(0, (ordinal ?? source.startOrdinal) - 2),
        matchingOrdinal: ordinal ?? source.startOrdinal,
        matchingEndOrdinal: ordinal ?? source.endOrdinal,
      },
    ],
    activeMatchIndex: 0,
  };
}

const wideQuery = "(min-width: 1024px)";
function subscribeWidth(notify: () => void) {
  const query = window.matchMedia(wideQuery);
  query.addEventListener("change", notify);
  return () => query.removeEventListener("change", notify);
}

export function ChatSourcePanel({
  id,
  sources,
  artifact,
  citationId,
  onSelect,
  onClose,
  restoreFocus,
}: {
  id: string;
  sources: ChatSource[];
  artifact?: ChatArtifact;
  citationId?: number;
  onSelect: (citationId?: number) => void;
  onClose: () => void;
  restoreFocus: () => void;
}) {
  const ui = useAppTranslation();
  const { t, i18n } = useTranslation("reader");
  const { t: rendererText } = useTranslation("renderers");
  const wide = useSyncExternalStore(
    subscribeWidth,
    () => window.matchMedia(wideQuery).matches,
    () => false,
  );
  const titleRef = useRef<HTMLHeadingElement>(null);
  const expandRef = useRef<HTMLButtonElement>(null);
  // Stepping between sources keeps focus on the pressed arrow instead of moving it to the title.
  const stepping = useRef(false);
  const [expanded, setExpanded] = useState(false);
  // The evidence tab chosen for each source, shared by the panel and its expanded dialog.
  const [views, setViews] = useState<Record<number, EvidenceView>>({});
  const selected = sources.find((source) => source.citationId === citationId);
  const index = selected ? sources.indexOf(selected) : -1;
  const format = (value: number) => new Intl.NumberFormat(i18n.resolvedLanguage).format(value);

  useEffect(() => {
    if (stepping.current) {
      stepping.current = false;
      if (document.activeElement instanceof HTMLButtonElement && !document.activeElement.disabled)
        return;
    }
    titleRef.current?.focus({ preventScroll: true });
  }, [citationId, artifact?.id, wide]);

  useEffect(() => {
    if (!wide || expanded) return undefined;
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !event.defaultPrevented) onClose();
    };
    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [wide, expanded, onClose]);

  const step = (offset: number) => {
    const next = sources[index + offset];
    if (!next) return;
    stepping.current = true;
    onSelect(next.citationId);
  };
  const documentCitation =
    selected && !selected.web && !(selected.fileId != null && !selected.fileLocation?.generation);
  const original = documentCitation ? citationOriginal(selected) : undefined;
  const view = selected
    ? (views[selected.citationId] ??
      (original?.kind === "pdf" && original.table ? "original" : "passages"))
    : undefined;
  const changeView = (next: EvidenceView) => {
    if (selected) setViews((current) => ({ ...current, [selected.citationId]: next }));
  };

  const content = (
    <>
      <header className="flex shrink-0 items-center gap-2 border-b border-border-subtle px-4 py-3">
        {selected && (
          <IconButton
            prominence="internal"
            size="sm"
            aria-label={t("back")}
            onClick={() => onSelect()}
          >
            <ArrowLeft />
          </IconButton>
        )}
        <h2
          ref={titleRef}
          tabIndex={-1}
          className="min-w-0 flex-1 truncate font-main-ui-action outline-none"
        >
          {artifact ? (
            <span title={artifact.title}>{artifact.title}</span>
          ) : selected?.web ? (
            ui("Nội dung trang Web")
          ) : selected ? (
            t("content")
          ) : (
            t("sourcesCount", { total: format(sources.length) })
          )}
        </h2>
        {selected && !artifact && sources.length > 1 ? (
          <div className="flex shrink-0 items-center gap-0.5">
            <span className="px-1 font-secondary-body whitespace-nowrap tabular-nums text-content-muted">
              {t("sourcePosition", { index: format(index + 1), total: format(sources.length) })}
            </span>
            <IconButton
              prominence="internal"
              size="sm"
              aria-label={t("previousSource")}
              disabled={index <= 0}
              onClick={() => step(-1)}
            >
              <ChevronLeft />
            </IconButton>
            <IconButton
              prominence="internal"
              size="sm"
              aria-label={t("nextSource")}
              disabled={index >= sources.length - 1}
              onClick={() => step(1)}
            >
              <ChevronRight />
            </IconButton>
          </div>
        ) : null}
        {wide && documentCitation && !artifact ? (
          <IconButton
            ref={expandRef}
            prominence="internal"
            size="sm"
            aria-label={t("expand")}
            onClick={() => setExpanded(true)}
          >
            <Maximize2 />
          </IconButton>
        ) : null}
        <IconButton
          prominence="internal"
          size="sm"
          aria-label={artifact ? rendererText("closeArtifact") : t("closeSources")}
          onClick={onClose}
        >
          <X />
        </IconButton>
      </header>
      {artifact ? (
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-4">
          <ChatArtifactView artifact={artifact} />
        </div>
      ) : selected?.web ? (
        <>
          <ChatSourceHeader source={selected} />
          <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-4">
            <blockquote className="whitespace-pre-wrap break-words border-l-2 border-evidence-highlight-border bg-evidence-highlight-surface px-4 py-3 text-sm leading-7 text-content-primary">
              {selected.web.excerpt}
            </blockquote>
          </div>
        </>
      ) : selected ? (
        <>
          <ChatSourceHeader source={selected}>
            {/* The original tabs name their own view; the passage hint would be false on the original tab. */}
            {original ? null : (
              <p className="mt-3 text-xs leading-5 text-content-muted">
                {selected.fileId ? t("fileCitation") : t("highlighted")}
              </p>
            )}
          </ChatSourceHeader>
          {!documentCitation ? (
            <div className="min-h-0 flex-1 overflow-y-auto p-4">
              <ChatFileReader
                key={`${selected.fileId}:${selected.citationId}`}
                fileId={selected.fileId!}
                initialOffset={selected.fileLocation?.offset ?? 0}
                citationCount={selected.fileLocation?.count ?? undefined}
              />
            </div>
          ) : expanded ? (
            // One reader at a time: the dialog owns the passages and pdf.js document while it is open.
            <p className="px-5 py-4 text-sm leading-6 text-content-muted">{t("expandedView")}</p>
          ) : (
            <EvidenceViewSwitch
              key={`view:${selected.documentId}:${selected.citationId}`}
              original={original}
              view={view}
              onViewChange={changeView}
            >
              <DocumentPreviewContent
                key={`${selected.documentId}:${selected.generation}:${selected.citationId}`}
                variant="chat"
                fileId={selected.fileId ?? undefined}
                selection={citationSelection(selected)}
              />
            </EvidenceViewSwitch>
          )}
          {expanded ? (
            <DocumentPreviewDialog
              variant="chat"
              fileId={selected.fileId ?? undefined}
              selection={citationSelection(selected)}
              view={view}
              onViewChange={changeView}
              returnFocusRef={expandRef}
              fallbackFocusRef={titleRef}
              onClose={() => setExpanded(false)}
            />
          ) : null}
        </>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-2">
          <ol className="flex flex-col gap-1">
            {sources.map((source) => (
              <li key={source.citationId}>
                <ChatSourceRow source={source} onSelect={onSelect} />
              </li>
            ))}
          </ol>
        </div>
      )}
    </>
  );

  return wide ? (
    <aside
      id={id}
      aria-label={artifact ? rendererText("artifact") : t("sources")}
      className="flex h-full min-h-0 w-100 max-w-[44%] shrink-0 flex-col border-l border-border-default bg-surface-base"
    >
      {content}
    </aside>
  ) : (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-surface-scrim" />
        <Dialog.Content
          id={id}
          aria-describedby={undefined}
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            restoreFocus();
          }}
          className="fixed inset-y-0 right-0 z-50 flex w-full max-w-md flex-col bg-surface-base pb-[env(safe-area-inset-bottom)] shadow-lg outline-none"
        >
          <Dialog.Title className="sr-only">
            {artifact
              ? artifact.title
              : selected?.web
                ? ui("Nội dung trang Web: {{title}}", { title: selected.title })
                : selected
                  ? t("documentTitle", { title: selected.title })
                  : t("sources")}
          </Dialog.Title>
          {content}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
