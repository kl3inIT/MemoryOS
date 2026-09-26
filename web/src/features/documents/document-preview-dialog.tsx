import { Download, Maximize2, Minimize2, X } from "lucide-react";
import { lazy, Suspense, useCallback, useState, type RefObject } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/components/ui/dialog";
import { IconButton } from "@/components/ui/icon-button";
import type { CitationConfidence } from "@/features/preview/file-preview";
import { previewKind, previewSize } from "@/features/preview/preview-kind";
import { PreviewCanvas, PreviewSkeleton } from "@/features/preview/preview-surface";
import { useAppTranslation } from "@/i18n/use-app-translation";
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
  const size = previewSize(kind);
  const reader = fileId
    ? undefined
    : documentOriginalReader(variant, selection.documentId, selection.generation);
  const cited = readSourceLocation(reading.activeMatch?.provenance ?? []);
  const places = selection.matches.map((match) => readSourceLocation(match.provenance ?? []));
  const [placed, setPlaced] = useState<readonly CitationConfidence[]>([]);
  // A reader reports what it found on every repaint; only a different answer is worth another render.
  const onPlaced = useCallback((found: readonly CitationConfidence[]) => {
    setPlaced((known) =>
      known.length === found.length && known.every((one, index) => one === found[index])
        ? known
        : found,
    );
  }, []);
  const [full, setFull] = useState(false);
  const [passages, setPassages] = useState(!reader || view === "passages");

  // A PDF citation is drawn from its recorded region, so its rail entry reports what provenance recorded.
  const confidence: readonly CitationConfidence[] =
    kind === "pdf" ? places.map((place) => (place.boxes.length ? "exact" : "none")) : placed;
  const located = kind === "pdf" ? cited.boxes.length > 0 : SEARCHABLE.has(kind);

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogContent
        showCloseButton={false}
        layout="flush"
        phone="sheet"
        // Full screen leaves no margin, so the expand control changes something for the formats that already open
        // at the largest windowed size — a PDF, a Word file, a wide workbook.
        size={full ? "screen" : size}
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
        <DocumentPreviewHeader
          selection={selection}
          download={reader?.url}
          full={full}
          onFull={() => setFull((open) => !open)}
        />

        <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
          {/* min-w-0 keeps a wide original — a workbook with many columns — from pushing the rail out. */}
          <div className="flex min-h-0 min-w-0 flex-1 flex-col">
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
                  rows={places.map((place) =>
                    place.row === undefined ? undefined : { sheet: place.sheet, row: place.row },
                  )}
                  texts={reading.citations}
                  sections={reading.sections}
                  active={reading.activeMatchIndex}
                  onPlaced={onPlaced}
                  onActive={reading.select}
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
              variant={variant}
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
      </DialogContent>
    </Dialog>
  );
}

/** What the document is and where it lives, and the commands on the whole dialog. */
function DocumentPreviewHeader({
  selection,
  download,
  full,
  onFull,
}: {
  selection: DocumentSelection;
  /** Where the stored original is downloaded from; absent, there is no original. */
  download?: string;
  full: boolean;
  onFull: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <header className="flex shrink-0 items-start justify-between gap-4 border-b border-border-subtle px-5 py-4 sm:px-6">
      <div className="flex min-w-0 items-start gap-3">
        <DocumentSourceIcon mediaType={selection.mediaType} sourceTypes={selection.sourceTypes} />
        <div className="min-w-0">
          <DialogTitle asChild>
            <h2 className="line-clamp-2 break-words font-heading-h3 text-content-primary">
              {selection.title}
            </h2>
          </DialogTitle>
          <DialogDescription asChild>
            <div className="mt-0.5 font-secondary-body text-content-muted">
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
            </div>
          </DialogDescription>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        {download ? (
          <IconButton prominence="internal" size="sm" aria-label={ui("Tải xuống")} asChild>
            <a href={download} download={selection.title}>
              <Download />
            </a>
          </IconButton>
        ) : null}
        <IconButton
          prominence="internal"
          size="sm"
          aria-label={full ? ui("Thu nhỏ cửa sổ") : ui("Mở toàn màn hình")}
          className="hidden sm:inline-flex"
          onClick={onFull}
        >
          {full ? <Minimize2 /> : <Maximize2 />}
        </IconButton>
        <DialogClose asChild>
          <IconButton prominence="internal" size="sm" aria-label={ui("Close document preview")}>
            <X />
          </IconButton>
        </DialogClose>
      </div>
    </header>
  );
}
