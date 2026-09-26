import { ChevronLeft, ChevronRight } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  clearCitations,
  paintCitations,
  placeCitations,
  type CitationPlacement,
} from "./citation-highlight";
import { CsvView } from "./csv-view";
import { DocxView } from "./docx-view";
import { DownloadView } from "./download-view";
import { ImageControls, ImageView } from "./image-view";
import { LazyPdfView } from "./lazy-pdf-view";
import { MarkdownView } from "./markdown-view";
import type { PdfHighlight } from "./pdf-pages";
import type { PreviewContent } from "./preview-content";
import type { Sheets } from "./preview-kind";
import { PreviewCanvas } from "./preview-surface";
import { PreviewToolbar, ToolbarGroup } from "./preview-toolbar";
import { placeSheetCitations, renderedRows, type SheetCitation } from "./sheet-citations";
import { SheetView } from "./sheet-view";
import { TextView } from "./text-view";

export type CitationConfidence = CitationPlacement["confidence"];

/** The cited passages to show in the file, in the order the citation rail lists them. */
export type PreviewCitations = {
  texts: readonly string[];
  /** The heading each citation was read under, which tells two identical passages apart. */
  sections?: readonly (string | undefined)[];
  /** Pages and regions the extraction recorded; PDF only, empty when none were recorded. */
  pages?: readonly number[];
  boxes?: readonly PdfHighlight[];
  /** The sheet and row recorded for each citation; workbooks only. */
  rows?: readonly (SheetCitation | undefined)[];
  active?: number;
  /**
   * Where each citation ended up, in citation order, so the rail can say which ones were located. A format
   * with no text to search reports nothing, and the rail keeps saying it does not know.
   */
  onPlaced?: (confidence: readonly CitationConfidence[]) => void;
  /** Steps the reader between citations from the toolbar. */
  onActive?: (index: number) => void;
};

/** An image transform the surface controls, because its own tools (a crop) must agree with it. */
export type ImageTransform = {
  zoom: number;
  rotation: number;
  /** Wheel over the picture; the same callback on every render, so its listener is attached once. */
  onZoom?: (deltaY: number) => void;
};

/** What a view reports once it has rendered; `DocxView` measures its own text while it does. */
type Rendered = (result: { words: number; text: string }) => void;

// Shared empties: a fresh array per render would re-run the highlight and re-scroll to the citation each time.
const NONE: readonly never[] = [];
const NO_CITATIONS: PreviewCitations = { texts: NONE };

/**
 * The one file preview every surface renders through — the library, Search and Chat. It picks the view by the
 * file's kind, loads pdf.js only when a PDF is shown, and, given citations, paints them where the locator finds
 * them. A passage the locator will not commit to is left undrawn: an unlocated citation is never drawn
 * somewhere merely plausible. The surface keeps its own chrome — the dialog, the header, its actions.
 */
export function FilePreview({
  content,
  filename,
  download,
  citations = NO_CITATIONS,
  image,
  thumbnails = false,
  onDocx,
}: {
  content: PreviewContent;
  filename: string;
  /** The authorized route of the whole file, offered where the file cannot be shown. */
  download: string;
  citations?: PreviewCitations;
  /** Leaves the image's zoom and turn to the surface; without it the preview offers its own controls. */
  image?: ImageTransform;
  /** A page rail beside a paged original, for a reader wide enough to hold one. */
  thumbnails?: boolean;
  /** The words of a Word file, once it has rendered, for a surface that describes or copies them. */
  onDocx?: Rendered;
}) {
  const ui = useAppTranslation();
  const { texts, sections = NONE, pages = NONE, boxes = NONE, rows = NONE, active = 0 } = citations;
  const highlighted = (children: (onRendered: Rendered) => ReactNode, rendered?: boolean) => (
    <HighlightedText
      citations={texts}
      sections={sections}
      active={active}
      onPlaced={citations.onPlaced}
      onActive={citations.onActive}
      rendered={rendered}
    >
      {children}
    </HighlightedText>
  );

  switch (content.kind) {
    case "pdf":
      // PDF citations are drawn from the regions provenance recorded, never searched for.
      return <LazyPdfView url={content.file} pages={pages} boxes={boxes} thumbnails={thumbnails} />;
    case "image":
      return image ? (
        <ImageView
          blob={content.blob}
          alt={filename}
          zoom={image.zoom}
          rotation={image.rotation}
          onZoom={image.onZoom}
        />
      ) : (
        <ControlledImage blob={content.blob} alt={filename} />
      );
    case "docx":
      return highlighted((onRendered) => (
        <DocxView
          blob={content.blob}
          onLoad={(result) => {
            onRendered(result);
            onDocx?.(result);
          }}
        />
      ));
    case "xlsx":
      // Without citations there is nothing to place, so the workbook is not read for rows.
      if (!texts.length)
        return (
          <PreviewCanvas>
            <SheetView sheets={content.sheets} />
          </PreviewCanvas>
        );
      return (
        <CitationSteps active={active} total={texts.length} onActive={citations.onActive}>
          <WorkbookOriginal
            sheets={content.sheets}
            rows={rows}
            active={active}
            onPlaced={citations.onPlaced}
          />
        </CitationSteps>
      );
    case "csv":
      return highlighted(
        () => (
          <PreviewCanvas>
            <CsvView csv={content.text} truncated={content.truncated} />
          </PreviewCanvas>
        ),
        true,
      );
    case "markdown":
      return highlighted(
        () => (
          <PreviewCanvas>
            <MarkdownView text={content.text} truncated={content.truncated} />
          </PreviewCanvas>
        ),
        true,
      );
    case "code":
    case "text":
      return highlighted(
        () => (
          <TextView
            text={content.text}
            filename={filename}
            kind={content.kind}
            truncated={content.truncated}
          />
        ),
        true,
      );
    case "doc":
      return (
        <DownloadView
          href={download}
          filename={filename}
          message={ui("Không xem trước được tệp .doc cũ. Hãy tải tệp xuống để mở.")}
        />
      );
    default:
      return (
        <DownloadView
          href={download}
          filename={filename}
          message={ui(
            "Tệp này không xem trực tiếp được. Hãy tải xuống để mở bằng ứng dụng phù hợp.",
          )}
        />
      );
  }
}

/** An image with its own zoom and turn, for a surface that has no tools of its own for it. */
function ControlledImage({ blob, alt }: { blob: Blob; alt: string }) {
  const [zoom, setZoom] = useState(100);
  const [rotation, setRotation] = useState(0);
  return (
    <div className="relative flex min-h-0 flex-1 flex-col bg-surface-sunken">
      <ImageView blob={blob} alt={alt} zoom={zoom} rotation={rotation} />
      <div className="pointer-events-none absolute inset-x-0 bottom-4 flex justify-center">
        <div className="pointer-events-auto">
          <ImageControls
            zoom={zoom}
            onZoom={setZoom}
            onRotate={(degrees) => setRotation((turn) => (turn + degrees + 360) % 360)}
          />
        </div>
      </div>
    </div>
  );
}

/**
 * Paints the citations over whatever its child renders, once the child says it has rendered. The highlight
 * is drawn with the CSS Custom Highlight API, so the original's own markup and layout are never touched.
 */
function HighlightedText({
  citations,
  sections,
  active,
  rendered = false,
  onPlaced,
  onActive,
  children,
}: {
  citations: readonly string[];
  sections: readonly (string | undefined)[];
  active: number;
  /** A view that renders synchronously is ready as soon as it is in the tree. */
  rendered?: boolean;
  onPlaced?: (confidence: readonly CitationConfidence[]) => void;
  onActive?: (index: number) => void;
  children: (onRendered: Rendered) => ReactNode;
}) {
  const container = useRef<HTMLDivElement>(null);
  const [ready, setReady] = useState(rendered);
  const onRendered = useCallback(() => setReady(true), []);
  const report = useRef(onPlaced);
  useEffect(() => {
    report.current = onPlaced;
  }, [onPlaced]);
  useEffect(() => {
    const root = container.current;
    if (!root || !ready || !citations.length) return undefined;
    const placements = placeCitations(root, citations, sections);
    paintCitations(placements, active);
    report.current?.(placements.map((placement) => placement.confidence));
    const opened = placements[active]?.range ?? placements.find((found) => found.range)?.range;
    const anchor =
      opened?.startContainer.nodeType === Node.ELEMENT_NODE
        ? (opened.startContainer as Element)
        : opened?.startContainer.parentElement;
    anchor?.scrollIntoView({ block: "center" });
    return () => clearCitations();
  }, [citations, sections, active, ready]);
  return (
    <CitationSteps active={active} total={citations.length} onActive={onActive}>
      <div ref={container} className="flex min-h-0 flex-1 flex-col">
        {children(onRendered)}
      </div>
    </CitationSteps>
  );
}

/**
 * A workbook shows its citations on the rows the extraction recorded. Nothing is searched for: the extraction
 * and this preview format numbers and dates differently, and a row number says exactly where the passage came
 * from.
 */
function WorkbookOriginal({
  sheets,
  rows,
  active,
  onPlaced,
}: {
  sheets: Sheets;
  rows: readonly (SheetCitation | undefined)[];
  active: number;
  onPlaced?: (confidence: readonly CitationConfidence[]) => void;
}) {
  // Parsing the workbook is the expensive part, so it happens once per workbook rather than once per citation.
  const rendered = useMemo(() => renderedRows(sheets), [sheets]);
  const placements = placeSheetCitations(sheets, rendered, rows);
  const report = useRef(onPlaced);
  useEffect(() => {
    report.current = onPlaced;
  });
  useEffect(() => {
    report.current?.(placements.map((placement) => (placement ? "exact" : "none")));
  });
  return (
    <PreviewCanvas>
      <SheetView sheets={sheets} placements={placements} active={active} />
    </PreviewCanvas>
  );
}

/** The floating previous/next control a reader steps through the citations with, over any original. */
function CitationSteps({
  active,
  total,
  onActive,
  children,
}: {
  active: number;
  total: number;
  onActive?: (index: number) => void;
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  return (
    <div className="relative flex min-h-0 min-w-0 flex-1 flex-col">
      {children}
      {onActive && total > 1 ? (
        <div className="pointer-events-none absolute inset-x-0 bottom-3 z-10 flex justify-center px-4">
          <PreviewToolbar>
            <ToolbarGroup>
              <IconButton
                prominence="internal"
                size="sm"
                aria-label={ui("Đoạn trước")}
                disabled={active <= 0}
                onClick={() => onActive(active - 1)}
              >
                <ChevronLeft />
              </IconButton>
              <span className="min-w-14 text-center font-secondary-action text-content-secondary tabular-nums">
                {active + 1} / {total}
              </span>
              <IconButton
                prominence="internal"
                size="sm"
                aria-label={ui("Đoạn sau")}
                disabled={active >= total - 1}
                onClick={() => onActive(active + 1)}
              >
                <ChevronRight />
              </IconButton>
            </ToolbarGroup>
          </PreviewToolbar>
        </div>
      ) : null}
    </div>
  );
}
