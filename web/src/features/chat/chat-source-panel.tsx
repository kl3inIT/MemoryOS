import { useApplicationSession } from "@/features/identity/application-session-context";
import { useEffect, useRef, useSyncExternalStore, type ReactNode } from "react";
import { Dialog } from "radix-ui";
import { ArrowLeft, X } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { DocumentPreviewContent } from "@/features/search/document-preview-content";
import type { ChatSource } from "./chat-evidence";
import { ChatFileReader } from "./chat-file-reader";
import { useTranslation } from "react-i18next";
import type { ChatArtifact } from "./chat-artifacts";
import { ChatArtifactView } from "./chat-artifact-view";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { EvidenceViewSwitch, type PdfEvidence } from "@/features/search/evidence-view-switch";
import { readChatDocumentOriginal } from "@/lib/hey-api/sdk.gen";
import { citedPdfLocation } from "./chat-source-meta";
import { ChatSourceHeader, ChatSourceRow } from "./chat-source-list";

/** PDF page view for an indexed document citation whose provenance records pages. */
function citationPdf(
  source: ChatSource,
  session: { actorId?: string | null; authorizationVersion?: unknown },
): PdfEvidence | undefined {
  const location = citedPdfLocation(source);
  if (!location || !source.documentId || !source.generation) return undefined;
  const { documentId, generation } = source;
  return {
    queryKey: ["chat", session.actorId, session.authorizationVersion, documentId, generation],
    load: async (signal) =>
      (
        await readChatDocumentOriginal({
          path: { documentId },
          query: { generation },
          parseAs: "blob",
          signal,
          throwOnError: true,
        })
      ).data as Blob,
    pages: location.pages,
    boxes: location.boxes,
  };
}

/** Reads session identity only where a PDF view can exist, so artifact and file panels need no session. */
function CitationEvidence({ source, children }: { source: ChatSource; children: ReactNode }) {
  const session = useApplicationSession();
  return <EvidenceViewSwitch pdf={citationPdf(source, session)}>{children}</EvidenceViewSwitch>;
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
  file,
  artifact,
  citationId,
  onSelect,
  onClose,
  restoreFocus,
}: {
  id: string;
  sources: ChatSource[];
  file?: { id: string; filename: string };
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
  const selected = sources.find((source) => source.citationId === citationId);
  useEffect(() => {
    titleRef.current?.focus({ preventScroll: true });
  }, [citationId, file?.id, artifact?.id, wide]);

  useEffect(() => {
    if (!wide) return undefined;
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !event.defaultPrevented) onClose();
    };
    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [wide, onClose]);

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
          ) : file ? (
            <span title={file.filename}>{file.filename}</span>
          ) : selected?.web ? (
            ui("Nội dung trang Web")
          ) : selected ? (
            t("content")
          ) : (
            t("sourcesCount", {
              total: new Intl.NumberFormat(i18n.resolvedLanguage).format(sources.length),
            })
          )}
        </h2>
        <IconButton
          prominence="internal"
          size="sm"
          aria-label={
            artifact ? rendererText("closeArtifact") : file ? t("closeFile") : t("closeSources")
          }
          onClick={onClose}
        >
          <X />
        </IconButton>
      </header>
      {artifact ? (
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-4">
          <ChatArtifactView artifact={artifact} />
        </div>
      ) : file ? (
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-4">
          <ChatFileReader key={file.id} fileId={file.id} />
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
            {/* The PDF tabs name their own view; the passage hint would be false on the page tab. */}
            {citedPdfLocation(selected) ? null : (
              <p className="mt-3 text-xs leading-5 text-content-muted">
                {selected.fileId ? t("fileCitation") : t("highlighted")}
              </p>
            )}
          </ChatSourceHeader>
          {selected.fileId != null && !selected.fileLocation?.generation ? (
            <div className="min-h-0 flex-1 overflow-y-auto p-4">
              <ChatFileReader
                key={`${selected.fileId}:${selected.citationId}`}
                fileId={selected.fileId}
                initialOffset={selected.fileLocation?.offset ?? 0}
                citationCount={selected.fileLocation?.count ?? undefined}
              />
            </div>
          ) : (
            <CitationEvidence
              key={`view:${selected.documentId}:${selected.citationId}`}
              source={selected}
            >
              <DocumentPreviewContent
                key={`${selected.documentId}:${selected.generation}:${selected.citationId}`}
                variant="chat"
                fileId={selected.fileId ?? undefined}
                selection={{
                  documentId: selected.documentId ?? selected.fileId!,
                  generation: selected.generation ?? selected.fileLocation!.generation!,
                  title: selected.title,
                  matches: [
                    {
                      from: Math.max(
                        0,
                        (selected.fileLocation?.ordinal ?? selected.startOrdinal) - 2,
                      ),
                      matchingOrdinal: selected.fileLocation?.ordinal ?? selected.startOrdinal,
                      matchingEndOrdinal: selected.fileLocation?.ordinal ?? selected.endOrdinal,
                    },
                  ],
                  activeMatchIndex: 0,
                }}
              />
            </CitationEvidence>
          )}
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
      aria-label={artifact ? rendererText("artifact") : file ? t("content") : t("sources")}
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
        <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/25" />
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
              : file
                ? file.filename
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
