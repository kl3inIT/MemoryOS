import { useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { useApplicationSession } from "@/features/identity/application-session-context";
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
import type { PdfHighlight } from "./pdf-pages";
import { PdfView } from "./pdf-view";
import { MAX_TEXT_PREVIEW_BYTES, previewKind, type Sheets } from "./preview-kind";
import { PreviewCanvas, PreviewSkeleton } from "./preview-surface";
import { SheetView } from "./sheet-view";
import { TextView } from "./text-view";

export type CitationConfidence = CitationPlacement["confidence"];

/** The three ways a reader reaches one stored original; a surface supplies the routes its authority allows. */
export type OriginalReader = {
  /** Same-origin URL of the original; pdf.js reads it by HTTP range and the download link points at it. */
  url: string;
  bytes: (signal: AbortSignal) => Promise<Blob>;
  /** A workbook is read as CSV per sheet, because the app ships no client-side workbook parser. */
  sheets: (signal: AbortSignal) => Promise<Sheets>;
};

/** What a view reports once it has rendered; `DocxView` measures its own text while it does. */
type Rendered = (result: { words: number; text: string }) => void;

type Loaded =
  | { kind: "pdf" }
  | { kind: "xlsx"; sheets: Sheets }
  | { kind: "csv"; text: string; truncated: boolean }
  | { kind: "code" | "markdown" | "text"; text: string; truncated: boolean }
  | { kind: "image" | "docx"; blob: Blob }
  | { kind: "doc" | "pptx" | "unsupported" };

/**
 * Reads only what the chosen view needs: pdf.js reads the URL itself by range, a workbook arrives already
 * parsed per sheet, and a file this app cannot show is never downloaded to find that out.
 */
async function load(
  reader: OriginalReader,
  filename: string,
  mediaType: string,
  signal: AbortSignal,
): Promise<Loaded> {
  const kind = previewKind(filename, mediaType);
  if (kind === "pdf") return { kind };
  if (kind === "xlsx") return { kind, sheets: await reader.sheets(signal) };
  if (kind === "doc" || kind === "pptx" || kind === "unsupported") return { kind };
  const blob = await reader.bytes(signal);
  if (kind === "image" || kind === "docx") return { kind, blob };
  const truncated = blob.size > MAX_TEXT_PREVIEW_BYTES;
  return { kind, text: await blob.slice(0, MAX_TEXT_PREVIEW_BYTES).text(), truncated };
}

/**
 * The stored original of a cited Document, shown as the file looks, with the cited passages painted where
 * they were found in it. A passage the locator will not commit to is left undrawn: an unlocated citation is
 * never drawn somewhere merely plausible.
 */
export function OriginalView({
  reader,
  filename,
  mediaType,
  pages = [],
  boxes = [],
  citations,
  active = 0,
  onPlaced,
  thumbnails = false,
}: {
  reader: OriginalReader;
  filename: string;
  mediaType?: string | null;
  /** Pages and regions the extraction recorded for the citation; PDF only, empty when none were recorded. */
  pages?: readonly number[];
  boxes?: readonly PdfHighlight[];
  /** The cited passage text, in the order the citation rail lists it. */
  citations: readonly string[];
  active?: number;
  /**
   * Where each citation ended up, in citation order, so the rail can say which ones were located. A format
   * with no text to search reports nothing, and the rail keeps saying it does not know.
   */
  onPlaced?: (confidence: readonly CitationConfidence[]) => void;
  /** A page rail beside a paged original, for a reader wide enough to hold one. */
  thumbnails?: boolean;
}) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const declared = mediaType || "application/octet-stream";
  const [zoom, setZoom] = useState(100);
  const [rotation, setRotation] = useState(0);
  const loaded = useQuery({
    queryKey: ["document-original", actorId, authorizationVersion, reader.url],
    queryFn: ({ signal }) => load(reader, filename, declared, signal),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });

  if (loaded.isPending)
    return (
      <PreviewCanvas>
        <PreviewSkeleton />
      </PreviewCanvas>
    );
  if (loaded.isError || !loaded.data)
    return (
      <DownloadView
        href={reader.url}
        filename={filename}
        message={ui("Không mở được tệp gốc. Hãy tải xuống để xem toàn bộ tệp.")}
      />
    );

  const data = loaded.data;
  const highlighted = (children: (onRendered: Rendered) => ReactNode, rendered?: boolean) => (
    <HighlightedOriginal
      citations={citations}
      active={active}
      onPlaced={onPlaced}
      rendered={rendered}
    >
      {children}
    </HighlightedOriginal>
  );

  switch (data.kind) {
    case "pdf":
      return <PdfView url={reader.url} pages={pages} boxes={boxes} thumbnails={thumbnails} />;
    case "image":
      return (
        <div className="relative flex min-h-0 flex-1 flex-col bg-surface-sunken">
          <ImageView blob={data.blob} alt={filename} zoom={zoom} rotation={rotation} />
          <div className="pointer-events-none absolute inset-x-0 bottom-4 flex justify-center">
            <div className="pointer-events-auto">
              <ImageControls
                zoom={zoom}
                onZoom={setZoom}
                onRotate={() => setRotation((turn) => (turn + 90) % 360)}
              />
            </div>
          </div>
        </div>
      );
    case "docx":
      return highlighted((onRendered) => <DocxView blob={data.blob} onLoad={onRendered} />);
    case "xlsx":
      return highlighted(
        () => (
          <PreviewCanvas>
            <SheetView sheets={data.sheets} />
          </PreviewCanvas>
        ),
        true,
      );
    case "csv":
      return highlighted(
        () => (
          <PreviewCanvas>
            <CsvView csv={data.text} truncated={data.truncated} />
          </PreviewCanvas>
        ),
        true,
      );
    case "code":
    case "markdown":
    case "text":
      return highlighted(
        () => (
          <TextView
            text={data.text}
            filename={filename}
            kind={data.kind}
            truncated={data.truncated}
          />
        ),
        true,
      );
    default:
      return (
        <DownloadView
          href={reader.url}
          filename={filename}
          message={ui(
            "Tệp này không xem trực tiếp được. Hãy tải xuống để mở bằng ứng dụng phù hợp.",
          )}
        />
      );
  }
}

/**
 * Paints the citations over whatever its child renders, once the child says it has rendered. The highlight
 * is drawn with the CSS Custom Highlight API, so the original's own markup and layout are never touched.
 */
function HighlightedOriginal({
  citations,
  active,
  rendered = false,
  onPlaced,
  children,
}: {
  citations: readonly string[];
  active: number;
  /** A view that renders synchronously is ready as soon as it is in the tree. */
  rendered?: boolean;
  onPlaced?: (confidence: readonly CitationConfidence[]) => void;
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
    const placements = placeCitations(root, citations);
    paintCitations(placements, active);
    report.current?.(placements.map((placement) => placement.confidence));
    const opened = placements[active]?.range ?? placements.find((found) => found.range)?.range;
    const anchor =
      opened?.startContainer.nodeType === Node.ELEMENT_NODE
        ? (opened.startContainer as Element)
        : opened?.startContainer.parentElement;
    anchor?.scrollIntoView({ block: "center" });
    return () => clearCitations();
  }, [citations, active, ready]);
  return (
    <div ref={container} className="flex min-h-0 flex-1 flex-col">
      {children(onRendered)}
    </div>
  );
}
