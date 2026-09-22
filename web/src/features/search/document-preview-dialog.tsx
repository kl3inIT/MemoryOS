import { Download, Maximize2, Minimize2, X } from "lucide-react";
import { Dialog } from "radix-ui";
import { lazy, Suspense, useState, type RefObject } from "react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import type { CitationConfidence } from "@/features/preview/original-view";
import { previewKind, previewSize } from "@/features/preview/preview-kind";
import { PreviewCanvas, PreviewSkeleton } from "@/features/preview/preview-surface";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { CitationRail } from "./citation-rail";
import { DocumentPreviewContent } from "./document-preview-content";
import { documentOriginalReader } from "./document-original-reader";
import { useDocumentReading } from "./document-reading";
import { DocumentSourceIcon } from "./document-source-icon";
import type { DocumentSourceType } from "./document-source-presentation";
import type { EvidenceView } from "./evidence-order";
import { DocumentMeta } from "./provider-link";
import { readSourceLocation } from "./source-provenance";

// The readers pull in pdf.js and docx-preview, so they load only when a reader opens a document.
const OriginalView = lazy(() =>
  import("@/features/preview/original-view").then((module) => ({ default: module.OriginalView })),
);

export type DocumentSelection = {
  documentId: string;
  generation: string;
  title: string;
  mediaType?: string | null;
  sourceTypes?: readonly DocumentSourceType[];
  providerUrl?: string | null;
  matches: Array<{
    from: number;
    matchingOrdinal: number;
    matchingEndOrdinal?: number;
    /** Provenance of this citation, carrying its page, region or sheet when one was recorded. */
    provenance?: readonly string[];
  }>;
  activeMatchIndex: number;
};

/** Kinds whose rendered text a citation can be searched in; elsewhere provenance is the only anchor. */
const SEARCHABLE = new Set(["docx", "xlsx", "csv", "markdown", "text", "code"]);

const SIZES = {
  full: "sm:h-[calc(100dvh-3rem)] sm:w-[calc(100vw-3rem)] sm:max-w-[96rem]",
  large: "sm:h-[min(48rem,calc(100dvh-3rem))] sm:w-[min(64rem,calc(100vw-3rem))]",
  tall: "sm:h-[calc(100dvh-3rem)] sm:w-[min(64rem,calc(100vw-3rem))]",
} as const;

type DocumentPreviewDialogProps = {
  selection: DocumentSelection;
  returnFocusRef: RefObject<HTMLElement | null>;
  fallbackFocusRef: RefObject<HTMLElement | null>;
  onClose: () => void;
  /** Chat citations read passages and originals with Chat authority; the Search page keeps Search authority. */
  variant?: "search" | "chat";
  /** Owner-private Chat file whose indexed passages are cited; it has no Document original. */
  fileId?: string;
  /** Which evidence the reader was on when they expanded; it chooses what the dialog opens showing. */
  view?: EvidenceView;
};

/**
 * The reading surface: the stored original on the canvas and its cited passages beside it, so a reader sees
 * the citation in the file instead of choosing between the two. The whole extraction stays reachable for
 * paging, and a document with no original to show opens on the passages directly.
 */
export function DocumentPreviewDialog({
  selection,
  returnFocusRef,
  fallbackFocusRef,
  onClose,
  variant = "search",
  fileId,
  view,
}: DocumentPreviewDialogProps) {
  const ui = useAppTranslation();
  const reading = useDocumentReading(selection, variant, fileId);
  const kind = previewKind(selection.title, selection.mediaType || "application/octet-stream");
  const reader = fileId
    ? undefined
    : documentOriginalReader(variant, selection.documentId, selection.generation);
  const cited = readSourceLocation(reading.activeMatch?.provenance ?? []);
  const [placed, setPlaced] = useState<readonly CitationConfidence[]>([]);
  const [full, setFull] = useState(false);
  const [passages, setPassages] = useState(!reader || view === "passages");

  // A PDF citation is drawn from its recorded region, so its rail entry reports what provenance recorded.
  const confidence: readonly CitationConfidence[] =
    kind === "pdf"
      ? selection.matches.map((match) =>
          readSourceLocation(match.provenance ?? []).boxes.length ? "exact" : "none",
        )
      : placed;
  const located = kind === "pdf" ? cited.boxes.length > 0 : SEARCHABLE.has(kind);

  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-surface-scrim backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
        <Dialog.Content
          className={cn(
            "fixed inset-x-0 bottom-0 z-50 flex max-h-[calc(100dvh-0.5rem)] min-h-[72dvh] flex-col overflow-hidden rounded-t-2xl border border-border-default bg-surface-overlay shadow-md outline-none sm:top-1/2 sm:left-1/2 sm:min-h-0 sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-2xl",
            full ? SIZES.full : SIZES[previewSize(kind)],
          )}
          onCloseAutoFocus={(event) => {
            const target = returnFocusRef.current?.isConnected
              ? returnFocusRef.current
              : fallbackFocusRef.current;
            if (target?.isConnected) {
              event.preventDefault();
              target.focus();
            }
            returnFocusRef.current = null;
          }}
        >
          <header className="flex shrink-0 items-start justify-between gap-4 border-b border-border-subtle px-5 py-4 sm:px-6">
            <div className="flex min-w-0 items-start gap-3">
              <DocumentSourceIcon
                mediaType={selection.mediaType}
                sourceTypes={selection.sourceTypes}
              />
              <div className="min-w-0">
                <Dialog.Title className="line-clamp-2 break-words font-heading-h3 text-content-primary">
                  {selection.title}
                </Dialog.Title>
                <Dialog.Description className="mt-0.5 font-secondary-body text-content-muted">
                  {selection.mediaType || selection.sourceTypes?.length ? (
                    <DocumentMeta
                      mediaType={selection.mediaType}
                      sourceTypes={selection.sourceTypes}
                      providerUrl={selection.providerUrl}
                      title={selection.title}
                    />
                  ) : (
                    ui("Extracted document text with the selected match highlighted.")
                  )}
                </Dialog.Description>
              </div>
            </div>
            <div className="flex shrink-0 items-center gap-1">
              {reader ? (
                <IconButton prominence="internal" size="sm" aria-label={ui("Tải xuống")} asChild>
                  <a href={reader.url} download={selection.title}>
                    <Download />
                  </a>
                </IconButton>
              ) : null}
              <IconButton
                prominence="internal"
                size="sm"
                aria-label={full ? ui("Thu nhỏ cửa sổ") : ui("Mở toàn màn hình")}
                className="hidden sm:inline-flex"
                onClick={() => setFull((open) => !open)}
              >
                {full ? <Minimize2 /> : <Maximize2 />}
              </IconButton>
              <Dialog.Close asChild>
                <IconButton
                  prominence="internal"
                  size="sm"
                  aria-label={ui("Close document preview")}
                >
                  <X />
                </IconButton>
              </Dialog.Close>
            </div>
          </header>

          <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
            <div className="flex min-h-0 flex-1 flex-col">
              {reader && !passages ? (
                <Suspense
                  fallback={
                    <PreviewCanvas>
                      <PreviewSkeleton />
                    </PreviewCanvas>
                  }
                >
                  <OriginalView
                    reader={reader}
                    filename={selection.title}
                    mediaType={selection.mediaType}
                    pages={cited.pages}
                    boxes={cited.boxes}
                    citations={reading.citations}
                    active={reading.activeMatchIndex}
                    onPlaced={setPlaced}
                    thumbnails
                  />
                </Suspense>
              ) : (
                <DocumentPreviewContent
                  selection={selection}
                  variant={variant}
                  reading={reading}
                  hideMatches={Boolean(reader)}
                />
              )}
            </div>
            {reader ? (
              <CitationRail
                entries={selection.matches.map((match, index) => ({
                  text: reading.citations[index] ?? "",
                  section: reading.sections[index],
                  provenance: match.provenance,
                }))}
                confidence={confidence}
                active={reading.activeMatchIndex}
                onActivate={reading.select}
                located={located}
              >
                <Button
                  size="sm"
                  prominence="internal"
                  onClick={() => setPassages((shown) => !shown)}
                >
                  {passages ? ui("Tệp gốc") : ui("Toàn bộ đoạn trích")}
                </Button>
              </CitationRail>
            ) : null}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
