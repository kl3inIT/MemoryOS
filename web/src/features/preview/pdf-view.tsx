import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Document, Page, pdfjs } from "react-pdf";
import { Button } from "@/components/ui/button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { PreviewCanvas, PreviewSkeleton } from "./preview-surface";
import { PageField, PreviewToolbar, ToolbarGroup, ZoomControl } from "./preview-toolbar";
import {
  formatPages,
  mostVisiblePage,
  nearestPage,
  pageHeight,
  pagesToRender,
  pdfBoxRect,
  type PdfHighlight,
  type PdfPageView,
  type PlacedOutline,
} from "./pdf-pages";
import { CitationRegion } from "./citation-region";
import { placedInkLines, placedWordSpans } from "./ink-outline";
import {
  markPdfRegions,
  placeTextItems,
  type PageText,
  type PdfMarks,
  type Rect,
} from "./pdf-text-marks";
import type { QueryTerms } from "./preview-highlight";
import {
  lineMarks,
  markedLinesOf,
  narrowRegion,
  wordCount,
  type LineTone,
  type QuestionMark,
  type ScanLine,
} from "./scan-line-marks";
import { BLOCK } from "./pdf-range-plan";
import { openPdfOriginal, PdfReading } from "./pdf-range-source";
import { useReaderWorker } from "./pdf-reader-worker";

// A same-origin original is read by HTTP range through `PdfReading`, which answers the blocks pdf.js asks for.
// Image decoders (JBIG2, JPEG 2000) for scanned originals, copied from pdfjs-dist by vite.config.ts. The CSP does not
// allow WebAssembly, so pdf.js uses their JavaScript fallbacks.
const wasmUrl = `/assets/pdfjs-${pdfjs.version}/`;

/** A recorded box's own coordinates, which name it whatever the zoom. */
const boxKey = (box: PdfHighlight) =>
  `${box.page}:${box.left}:${box.top}:${box.right}:${box.bottom}`;
const RANGE_OPTIONS = {
  disableStream: true,
  disableAutoFetch: true,
  rangeChunkSize: BLOCK,
  wasmUrl,
};
// When range loading fails, one whole read, as before range support; the failure is reported.
const WHOLE_OPTIONS = { disableRange: true, wasmUrl };
// Multiples of the fit-to-width size: a Letter page in a 400px panel needs 2–3× for body text.
const ZOOM_STEPS = [1, 1.5, 2, 3] as const;
/** Shared so a document without located lines does not mark its regions again on every render. */
const NO_LINES: readonly ScanLine[] = [];

/** What the reader needs of a pdf.js page to place its text; react-pdf hands it over once the page loads. */
type TextPage = {
  getTextContent: () => Promise<{ items: unknown[]; styles: Record<string, object> }>;
  getViewport: (parameters: { scale: number }) => { transform: number[]; width: number };
};

let measuring: CanvasRenderingContext2D | null | undefined;
/** A run's characters measured in its font family, so a mark covers its letters rather than a share of them. */
function measureText(text: string, fontFamily: string): number {
  measuring ??= document.createElement("canvas").getContext("2d");
  if (!measuring) return text.length;
  measuring.font = `100px ${fontFamily}`;
  return measuring.measureText(text).width;
}

/**
 * How a page's cited regions are drawn for a search: filled as recorded when the question was not given or
 * the page has no text in them (a scan), not yet while the page's text is read, and otherwise by its marks.
 */
type PageMarking = { kind: "fill" } | { kind: "pending" } | { kind: "marked"; marks: PdfMarks };

/** Hands pdf.js the bytes a `PdfReading` fetched, in the blocks pdf.js asked for. */
class OriginalTransport extends pdfjs.PDFDataRangeTransport {
  readonly #reading: PdfReading;

  constructor(reading: PdfReading) {
    super(reading.length, reading.head());
    this.#reading = reading;
  }

  override requestDataRange(begin: number, end: number) {
    this.#reading.request(begin, end, (bytes) => this.onDataRange(begin, bytes));
  }

  override abort() {
    this.#reading.close();
  }
}

/**
 * What `Document` loads: the file itself, a URL read whole, or a range transport once its original is opened.
 * A URL is opened by range first; a read that fails is told through `onFailure` and the caller reads it whole.
 */
function useOriginal(url: string | Blob, whole: boolean, onFailure: () => void) {
  const [ranged, setRanged] = useState<{ url: string; range: OriginalTransport }>();
  useEffect(() => {
    if (typeof url !== "string" || whole) return undefined;
    let reading: PdfReading | undefined;
    let left = false;
    openPdfOriginal(url).then(
      (held) => {
        if (left) return;
        reading = new PdfReading(url, held, onFailure);
        setRanged({ url, range: new OriginalTransport(reading) });
      },
      () => {
        if (!left) onFailure();
      },
    );
    return () => {
      left = true;
      reading?.close();
    };
  }, [url, whole, onFailure]);
  // A new object per opening: react-pdf releases the document on unmount and never shares it across readers.
  return useMemo(() => {
    if (typeof url !== "string") return url;
    if (whole) return { url };
    return ranged?.url === url ? { range: ranged.range } : undefined;
  }, [url, whole, ranged]);
}

/**
 * Shows the whole authorized original PDF, opened at the first cited page with the cited regions highlighted
 * from extraction provenance. The cited page is drawn first and alone; then the pages near the viewport, and
 * the page rail once those are drawn. Every other page stays a sized placeholder.
 */
export function PdfView({
  url,
  pages,
  boxes,
  terms,
  lines = NO_LINES,
  thumbnails = false,
  steps,
  onReady,
}: {
  /** A same-origin URL, or the file itself: the CSP (connect-src 'self') does not let pdf.js fetch blob: URLs. */
  url: string | Blob;
  pages: readonly number[];
  boxes: readonly PdfHighlight[];
  /**
   * The reader's question. Given one, a cited region whose own text holds it is drawn as the sentences holding
   * it with the question marked, rather than as the whole region.
   */
  terms?: QueryTerms;
  /**
   * The text lines the OCR located in the cited blocks of a scanned page. With a question, a region of a page
   * without a text layer is narrowed to the lines that hold it, the citation's other lines drawn faint.
   */
  lines?: readonly ScanLine[];
  /** A page rail beside the document, for a reader wide enough to hold one. */
  thumbnails?: boolean;
  /** A group for the toolbar that steps between cited passages; the caller moves `pages` and `boxes`. */
  steps?: ReactNode;
  /** Called once the page it opens at is drawn, so a caller can swap it in without a blank frame. */
  onReady?: () => void;
}) {
  const ui = useAppTranslation();
  const container = useRef<HTMLDivElement>(null);
  const [pageCount, setPageCount] = useState<number>();
  // The canvas is only mounted once the document has loaded, so it is measured again then.
  const fitWidth = Math.max(240, useWidth(container, pageCount));
  const [zoom, setZoom] = useState(0);
  const [wholeFile, setWholeFile] = useState(false);
  const scale = ZOOM_STEPS[zoom] ?? 1;
  const renderedWidth = Math.round(fitWidth * scale);
  const [pageViews, setPageViews] = useState<Record<number, PdfPageView>>({});
  const [nearPages, setNearPages] = useState<ReadonlySet<number>>(() => new Set());
  const [visiblePage, setVisiblePage] = useState<number>();
  // Pages whose canvas has been drawn, or could not be: what the reader is no longer waiting for.
  const [drawn, setDrawn] = useState<ReadonlySet<number>>(() => new Set());
  // The reader scrolled, typed or picked a page, so the cited page no longer goes first.
  const [moved, setMoved] = useState(false);
  // The whole read mounts the document again, pages and canvas with it: what was counted and drawn starts over,
  // so the observers and the opening scroll are set up on the new canvas.
  const readWhole = useCallback(() => {
    setWholeFile(true);
    setPageCount(undefined);
    setDrawn((known) => (known.size ? new Set() : known));
  }, []);
  const file = useOriginal(url, wholeFile, readWhole);
  const worker = useReaderWorker();
  const options = useMemo(
    () => ({ ...(wholeFile ? WHOLE_OPTIONS : RANGE_OPTIONS), worker }),
    [wholeFile, worker],
  );
  const cited = pages.length ? pages : [1];
  const [firstCited = 1] = cited;
  const anchor = Math.min(firstCited, pageCount ?? firstCited);
  const currentPage = visiblePage ?? anchor;
  const figures = useRef(new Map<number, HTMLElement>());
  // A box with an outline is drawn around the ink of its lines once its page's canvas has been drawn and read.
  const [inked, setInked] = useState<Record<string, PlacedOutline[]>>({});
  const viewsNow = useRef(pageViews);
  useEffect(() => {
    viewsNow.current = pageViews;
  });
  const readInk = (pageNumber: number, width: number) => {
    const outlined = boxes.filter((box) => box.page === pageNumber && box.outline);
    if (!outlined.length) return;
    // After the paint: the outline shows at once and the lines replace it.
    setTimeout(() => {
      const view = viewsNow.current[pageNumber];
      const canvas = figures.current.get(pageNumber)?.querySelector("canvas");
      if (!view || !canvas?.width || !canvas.height) return;
      const scale = canvas.width / width;
      const found: Record<string, PlacedOutline[]> = {};
      for (const box of outlined) {
        const rect = pdfBoxRect(box, view, width);
        const lines =
          rect?.outline &&
          placedInkLines(canvas, canvas.width, canvas.height, rect, rect.outline, scale, scale);
        if (lines) found[boxKey(box)] = lines;
      }
      if (Object.keys(found).length) setInked((current) => ({ ...current, ...found }));
    }, 0);
  };
  // The text of each cited page, read once its page has loaded: `null` when it could not be read.
  const searching = Boolean(terms?.phrase.trim());
  const [pageTexts, setPageTexts] = useState<Record<number, PageText | null>>({});
  const loadedPages = useRef(new Map<number, TextPage>());
  const readingText = useRef(new Set<number>());
  const readText = useCallback((pageNumber: number) => {
    const page = loadedPages.current.get(pageNumber);
    if (!page || readingText.current.has(pageNumber)) return;
    readingText.current.add(pageNumber);
    const viewport = page.getViewport({ scale: 1 });
    page.getTextContent().then(
      (content) =>
        setPageTexts((known) => ({
          ...known,
          [pageNumber]: {
            items: placeTextItems(content.items, content.styles, viewport.transform),
            width: viewport.width,
          },
        })),
      () => setPageTexts((known) => ({ ...known, [pageNumber]: null })),
    );
  }, []);
  // Another cited passage may be on a page already loaded, whose text has not been read yet.
  useEffect(() => {
    if (!searching) return;
    for (const box of boxes) readText(box.page);
  }, [searching, boxes, readText]);
  const markings = useMemo(() => {
    const found: Record<number, PageMarking> = {};
    if (!searching || !terms) return found;
    for (const pageNumber of new Set(boxes.map((box) => box.page))) {
      const view = pageViews[pageNumber];
      const text = pageTexts[pageNumber];
      if (text === undefined || !view) {
        found[pageNumber] = { kind: "pending" };
        continue;
      }
      const regions = boxes
        .filter((box) => box.page === pageNumber)
        .flatMap((box): Rect[] => {
          const rect = pdfBoxRect(box, view, renderedWidth);
          return rect ? [rect] : [];
        });
      const marks =
        text && markPdfRegions(text, regions, renderedWidth / text.width, terms, measureText);
      found[pageNumber] = marks ? { kind: "marked", marks } : { kind: "fill" };
    }
    return found;
  }, [searching, terms, boxes, pageViews, pageTexts, renderedWidth]);
  const questionLines = useMemo(() => lineMarks(lines, terms), [lines, terms]);
  // Where each marked line's words lie along it, read from its page's canvas once the page is drawn.
  const [words, setWords] = useState<{
    lines: readonly ScanLine[];
    spans: ReadonlyMap<number, [number, number][] | undefined>;
  }>();
  useEffect(() => {
    if (!questionLines.size) return undefined;
    const timer = setTimeout(() => {
      const spans = new Map<number, [number, number][] | undefined>();
      for (const index of questionLines.keys()) {
        const line = lines[index]!;
        const view = viewsNow.current[line.page];
        const canvas = figures.current.get(line.page)?.querySelector("canvas");
        if (!view || !drawn.has(line.page) || !canvas?.width || !canvas.height) continue;
        const xs = line.points.map(([x]) => x);
        const ys = line.points.map(([, y]) => y);
        const placed = pdfBoxRect(
          {
            page: line.page,
            left: Math.min(...xs),
            top: Math.min(...ys),
            right: Math.max(...xs),
            bottom: Math.max(...ys),
            origin: line.origin,
            ...(line.frame ? { frame: line.frame } : {}),
            outline: line.points,
          },
          view,
          renderedWidth,
        );
        if (!placed?.outline) continue;
        const scale = canvas.width / renderedWidth;
        const quad = placed.outline.map(([x, y]): [number, number] => [
          (placed.left + x * placed.width) * scale,
          (placed.top + y * placed.height) * scale,
        ]);
        spans.set(
          index,
          placedWordSpans(canvas, canvas.width, canvas.height, quad, wordCount(line.text)),
        );
      }
      setWords({ lines, spans });
    }, 0);
    return () => clearTimeout(timer);
  }, [questionLines, lines, drawn, pageViews, renderedWidth]);
  const spans = words?.lines === lines ? words.spans : undefined;
  const firstBox = useRef<Element | null>(null);
  const currentPageRef = useRef(currentPage);
  const lastWidth = useRef(renderedWidth);
  const anchorView = pageViews[anchor];

  useEffect(() => {
    const root = container.current;
    if (!root || !pageCount) return undefined;
    const pageOf = (entry: IntersectionObserverEntry) =>
      Number((entry.target as HTMLElement).dataset.page);
    const near = new Set<number>();
    const ratios = new Map<number, number>();
    const nearObserver = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) near.add(pageOf(entry));
          else near.delete(pageOf(entry));
        }
        setNearPages((known) => (sameSet(known, near) ? known : new Set(near)));
      },
      { root, rootMargin: "100% 0px" },
    );
    // Any of these is the reader taking over from the opening scroll to the cited page.
    const takeOver = () => setMoved(true);
    const gestures = ["wheel", "touchmove", "keydown", "pointerdown"] as const;
    for (const gesture of gestures) root.addEventListener(gesture, takeOver, { passive: true });
    const visibleObserver = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.intersectionRatio > 0) ratios.set(pageOf(entry), entry.intersectionRatio);
          else ratios.delete(pageOf(entry));
        }
        const page = mostVisiblePage(ratios);
        if (page) setVisiblePage(page);
      },
      { root, threshold: [0, 0.25, 0.5, 0.75, 1] },
    );
    for (const figure of figures.current.values()) {
      nearObserver.observe(figure);
      visibleObserver.observe(figure);
    }
    return () => {
      nearObserver.disconnect();
      visibleObserver.disconnect();
      for (const gesture of gestures) root.removeEventListener(gesture, takeOver);
    };
  }, [pageCount]);

  useEffect(() => {
    currentPageRef.current = currentPage;
  }, [currentPage]);

  // The first cited region of the anchor page, as a value: it changes only when another passage is cited, so
  // a passage on the page already open is scrolled to as well and a repaint moves no one.
  const region = boxes.find((box) => box.page === anchor);
  const citedRegion = region && `${region.left}:${region.top}:${region.right}:${region.bottom}`;
  // Open at the cited region once its page is measured; a zoom keeps the reader on the page they were reading.
  useEffect(() => {
    if (!pageCount) return;
    const zoomed = lastWidth.current !== renderedWidth;
    lastWidth.current = renderedWidth;
    const page = zoomed ? currentPageRef.current : anchor;
    if (page === anchor && firstBox.current) {
      firstBox.current.scrollIntoView({ block: "center", inline: "nearest" });
    } else {
      figures.current.get(page)?.scrollIntoView({ block: "start", inline: "nearest" });
    }
  }, [pageCount, anchor, anchorView, renderedWidth, citedRegion]);

  const goToPage = (page: number) => {
    setMoved(true);
    figures.current.get(page)?.scrollIntoView({ block: "start", inline: "nearest" });
  };
  const settle = (page: number) =>
    setDrawn((known) => (known.has(page) ? known : new Set(known).add(page)));

  const returnToCitation = () => {
    const target = firstBox.current ?? figures.current.get(anchor);
    target?.scrollIntoView({ block: firstBox.current ? "center" : "start", inline: "nearest" });
  };

  const loadingPages = (
    <PreviewCanvas>
      <PreviewSkeleton width={renderedWidth} />
    </PreviewCanvas>
  );
  const failed = (
    <PreviewCanvas>
      <p
        role="alert"
        className="rounded-xl bg-status-danger-surface p-4 text-status-danger-content"
      >
        {ui("Không mở được bản PDF gốc. Hãy xem đoạn trích.")}
      </p>
    </PreviewCanvas>
  );
  const near = pageCount ? pagesToRender(nearPages, anchor, pageCount) : [];
  // The cited page goes first and alone: its neighbours would share the link and the worker with it.
  const opened = moved || drawn.has(anchor);
  const rendered = new Set(opened ? near : near.filter((page) => page === anchor));
  const idle = opened && near.every((page) => drawn.has(page));
  const fallbackView = anchorView ?? Object.values(pageViews)[0];

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      <Document
        key={wholeFile ? "whole" : "range"}
        file={worker ? file : undefined}
        options={options}
        suspense={false}
        loading={loadingPages}
        noData={loadingPages}
        error={wholeFile ? failed : loadingPages}
        onLoadError={readWhole}
        onLoadSuccess={(document) => setPageCount(document.numPages)}
        className="flex min-h-0 flex-1"
      >
        {thumbnails && pageCount ? (
          <PdfThumbnails total={pageCount} current={currentPage} idle={idle} onGo={goToPage} />
        ) : null}
        <PreviewCanvas ref={container} className="pb-16">
          <div className="flex w-fit min-w-full flex-col gap-5">
            {Array.from({ length: pageCount ?? 0 }, (_, index) => {
              const pageNumber = index + 1;
              const view = pageViews[pageNumber];
              const show = rendered.has(pageNumber);
              return (
                <figure
                  key={pageNumber}
                  ref={(element) => {
                    if (element) figures.current.set(pageNumber, element);
                    else figures.current.delete(pageNumber);
                  }}
                  data-slot="pdf-page"
                  data-page={pageNumber}
                  data-rendered={show || undefined}
                  aria-label={ui("Trang {{pages}}", { pages: pageNumber })}
                  className="mx-auto w-fit"
                >
                  {/* The canvas is always a white sheet, so the highlighter multiplies onto it in both themes. */}
                  <div
                    className="relative isolate bg-surface-document shadow-md ring-1 ring-border-subtle"
                    style={{
                      width: renderedWidth,
                      height: pageHeight(view, renderedWidth, fallbackView),
                    }}
                  >
                    {show ? (
                      <Page
                        pageNumber={pageNumber}
                        width={renderedWidth}
                        loading=""
                        renderTextLayer={false}
                        renderAnnotationLayer={false}
                        onRenderSuccess={() => {
                          settle(pageNumber);
                          readInk(pageNumber, renderedWidth);
                          if (pageNumber === anchor) onReady?.();
                        }}
                        onRenderError={() => settle(pageNumber)}
                        onLoadError={() => settle(pageNumber)}
                        onLoadSuccess={(page) => {
                          loadedPages.current.set(pageNumber, page);
                          if (searching && boxes.some((box) => box.page === pageNumber))
                            readText(pageNumber);
                          setPageViews((current) =>
                            current[pageNumber]
                              ? current
                              : {
                                  ...current,
                                  [pageNumber]: { view: [...page.view], rotate: page.rotate },
                                },
                          );
                        }}
                      />
                    ) : null}
                    {/*
                      One multiplying layer for the whole page. Regions overlap — a passage spanning two
                      lines of a table records a box for each — and multiplying each one onto the last
                      darkens the overlap towards red until the page is unreadable. Painted together and
                      multiplied once, an overlap is the same yellow as a single region.
                    */}
                    {show && view ? (
                      <PageCitations
                        regions={boxes
                          .filter((box) => box.page === pageNumber)
                          .map((box, boxIndex) => {
                            const rect = pdfBoxRect(box, view, renderedWidth);
                            const bands = inked[boxKey(box)];
                            // A page whose text layer held the question marks its sentences instead.
                            const narrowed =
                              rect &&
                              questionLines.size &&
                              (markings[pageNumber]?.kind ?? "fill") === "fill"
                                ? narrowRegion(
                                    bands,
                                    markedLinesOf(
                                      box,
                                      lines,
                                      questionLines,
                                      spans,
                                      (outlined) =>
                                        pdfBoxRect(outlined, view, renderedWidth)?.outline,
                                    ),
                                    rect.width / rect.height,
                                  )
                                : undefined;
                            return (
                              rect && {
                                key: `${box.left}:${box.top}:${boxIndex}`,
                                rect,
                                lines: narrowed ? narrowed.lines : bands,
                                tones: narrowed?.tones,
                                marks: narrowed?.marks,
                                tone: narrowed?.tone,
                                anchor: pageNumber === anchor && boxIndex === 0,
                              }
                            );
                          })}
                        marking={markings[pageNumber] ?? { kind: "fill" }}
                        anchorRef={firstBox}
                        label={ui("Vùng được trích dẫn trên trang {{page}}", { page: pageNumber })}
                      />
                    ) : null}
                  </div>
                </figure>
              );
            })}
          </div>
        </PreviewCanvas>
      </Document>
      <div className="pointer-events-none absolute inset-x-0 bottom-3 flex justify-center px-4">
        <PreviewToolbar>
          {pageCount ? (
            <PageField page={currentPage} total={pageCount} onPage={goToPage} />
          ) : (
            <ToolbarGroup>
              <span className="px-1.5 font-secondary-body text-content-muted tabular-nums">
                {ui("Trang {{pages}}", { pages: formatPages(cited) })}
              </span>
            </ToolbarGroup>
          )}
          {pageCount && !cited.includes(currentPage) ? (
            <ToolbarGroup>
              <Button size="sm" prominence="internal" onClick={returnToCitation}>
                {ui("Về đoạn trích dẫn")}
              </Button>
            </ToolbarGroup>
          ) : null}
          {steps}
          <ZoomControl
            label={zoom === 0 ? ui("Vừa khung") : ui("{{percent}}%", { percent: scale * 100 })}
            onOut={() => setZoom((value) => Math.max(0, value - 1))}
            onIn={() => setZoom((value) => Math.min(ZOOM_STEPS.length - 1, value + 1))}
            outDisabled={zoom === 0}
            inDisabled={zoom === ZOOM_STEPS.length - 1}
          />
        </PreviewToolbar>
      </div>
    </div>
  );
}

const THUMBNAIL_WIDTH = 96;

/**
 * The page rail: every page as a button. A thumbnail is a whole page to pdf.js — its bytes and its decode —
 * so the rail draws one at a time, only for buttons in view, nearest the current page first, and only while
 * the document canvas is `idle`. One that left the rail's surroundings is released.
 */
type PlacedRegion = {
  key: string;
  rect: Rect & { outline?: PlacedOutline };
  lines?: PlacedOutline[];
  tones?: LineTone[];
  marks?: QuestionMark[];
  tone?: LineTone;
  /** The first region of the page the reader opens at, which the opening scroll centres. */
  anchor: boolean;
};

/**
 * One page's cited regions. Filled as recorded, unless the question was marked in their text: then the
 * sentences holding it are a light focus, its marks sit over them at full strength, and each region is a bar in
 * its margin, or a dashed outline when its text holds none of the question.
 */
function PageCitations({
  regions,
  marking,
  anchorRef,
  label,
}: {
  regions: readonly (PlacedRegion | undefined)[];
  marking: PageMarking;
  anchorRef: React.RefObject<Element | null>;
  label: string;
}) {
  const marked = marking.kind === "marked" ? marking.marks : undefined;
  const variant =
    marking.kind === "pending"
      ? "pending"
      : !marked
        ? "fill"
        : marked.marks.length
          ? "bar"
          : "outline";
  const drawn = regions.map((region) => {
    if (!region) return null;
    const { outline, ...placed } = region.rect;
    return (
      <CitationRegion
        key={region.key}
        regionRef={region.anchor ? anchorRef : undefined}
        slot="pdf-citation-box"
        label={label}
        outline={outline}
        lines={region.lines}
        tones={region.tones}
        marks={region.marks}
        tone={region.tone}
        style={placed}
        variant={variant}
      />
    );
  });
  return (
    <>
      <div className="pointer-events-none absolute inset-0 opacity-55 mix-blend-multiply">
        {variant === "fill" ? drawn : null}
        {marked?.focus.map((rect, index) => (
          <span
            key={`focus:${index}`}
            data-slot="pdf-citation-focus"
            className="absolute rounded-sm bg-pdf-highlight opacity-60"
            style={rect}
          />
        ))}
      </div>
      {/* The marks and the region's own sign sit at full strength over the focus. */}
      <div className="pointer-events-none absolute inset-0 mix-blend-multiply">
        {variant === "fill" ? null : drawn}
        {marked?.marks.map(({ whole, ...rect }, index) => (
          <span
            key={`mark:${index}`}
            data-slot="pdf-citation-mark"
            data-whole={whole}
            className={cn(
              "absolute border-b-2 border-pdf-highlight-border",
              whole && "rounded-sm bg-pdf-highlight",
            )}
            style={rect}
          />
        ))}
      </div>
    </>
  );
}

function PdfThumbnails({
  total,
  current,
  idle,
  onGo,
}: {
  total: number;
  current: number;
  /** The pages on the document canvas are drawn, so the rail may use the link and the worker. */
  idle: boolean;
  onGo: (page: number) => void;
}) {
  const ui = useAppTranslation();
  const rail = useRef<HTMLDivElement>(null);
  const buttons = useRef(new Map<number, HTMLElement>());
  const [inView, setInView] = useState<ReadonlySet<number>>(() => new Set());
  // Thumbnails that are mounted, and the one of them still being drawn.
  const [made, setMade] = useState<ReadonlySet<number>>(() => new Set());
  const [drawing, setDrawing] = useState<number>();
  useEffect(() => {
    const root = rail.current;
    if (!root) return undefined;
    const pageOf = (entry: IntersectionObserverEntry) =>
      Number((entry.target as HTMLElement).dataset.page);
    const near = new Set<number>();
    const seen = new Set<number>();
    const nearObserver = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) near.add(pageOf(entry));
          else near.delete(pageOf(entry));
        }
        setMade((known) => {
          const kept = new Set([...known].filter((page) => near.has(page)));
          return kept.size === known.size ? known : kept;
        });
        setDrawing((page) => (page !== undefined && !near.has(page) ? undefined : page));
      },
      { root, rootMargin: "200% 0px" },
    );
    const seenObserver = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) seen.add(pageOf(entry));
          else seen.delete(pageOf(entry));
        }
        setInView((known) => (sameSet(known, seen) ? known : new Set(seen)));
      },
      { root },
    );
    for (const button of buttons.current.values()) {
      nearObserver.observe(button);
      seenObserver.observe(button);
    }
    return () => {
      nearObserver.disconnect();
      seenObserver.disconnect();
    };
  }, [total]);
  // The next thumbnail starts when the last one is drawn and the canvas has nothing left to draw. It is
  // chosen while rendering, from what is in view now, and held in state so a scroll does not unseat it.
  const next =
    idle && drawing === undefined
      ? nearestPage(
          [...inView].filter((page) => !made.has(page)),
          current,
        )
      : undefined;
  if (next !== undefined) {
    setDrawing(next);
    setMade(new Set(made).add(next));
  }
  const finish = (page: number) => setDrawing((active) => (active === page ? undefined : active));
  // The rail follows the document, so the page being read stays visible in it.
  useEffect(() => {
    buttons.current.get(current)?.scrollIntoView({ block: "nearest" });
  }, [current]);
  return (
    <nav
      ref={rail}
      aria-label={ui("Các trang")}
      className="hidden w-32 shrink-0 overflow-y-auto overscroll-contain border-r border-border-subtle bg-surface-base p-2 lg:block"
    >
      <ol className="flex flex-col gap-2">
        {Array.from({ length: total }, (_, index) => {
          const page = index + 1;
          return (
            <li key={page}>
              <button
                type="button"
                ref={(element) => {
                  if (element) buttons.current.set(page, element);
                  else buttons.current.delete(page);
                }}
                data-page={page}
                aria-current={page === current ? "true" : undefined}
                aria-label={ui("Trang {{pages}}", { pages: page })}
                onClick={() => onGo(page)}
                className="block w-full cursor-pointer rounded-md p-1 ring-1 ring-transparent transition-shadow outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40 aria-[current]:ring-pdf-highlight-border"
              >
                <span
                  className="flex items-center justify-center overflow-hidden bg-surface-document shadow-sm"
                  style={{ width: THUMBNAIL_WIDTH, height: Math.round(THUMBNAIL_WIDTH * 1.294) }}
                >
                  {made.has(page) ? (
                    <Page
                      pageNumber={page}
                      width={THUMBNAIL_WIDTH}
                      loading=""
                      renderTextLayer={false}
                      renderAnnotationLayer={false}
                      onRenderSuccess={() => finish(page)}
                      onRenderError={() => finish(page)}
                      onLoadError={() => finish(page)}
                    />
                  ) : null}
                </span>
                <span className="mt-1 block text-center font-secondary-body text-content-muted tabular-nums">
                  {page}
                </span>
              </button>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

function sameSet(first: ReadonlySet<number>, second: ReadonlySet<number>) {
  if (first.size !== second.size) return false;
  for (const value of first) if (!second.has(value)) return false;
  return true;
}

function useWidth(ref: React.RefObject<HTMLElement | null>, mounted: unknown) {
  const [width, setWidth] = useState(360);
  useEffect(() => {
    const element = ref.current;
    if (!element) return undefined;
    const observer = new ResizeObserver(([entry]) => {
      // A canvas taken out while the next document loads reports 0; the last real width still holds.
      if (entry && entry.contentRect.width > 0) setWidth(Math.floor(entry.contentRect.width));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref, mounted]);
  return width;
}
