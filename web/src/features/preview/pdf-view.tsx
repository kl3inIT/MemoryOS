import { useEffect, useMemo, useRef, useState } from "react";
import { Document, Page, pdfjs } from "react-pdf";
import { Button } from "@/components/ui/button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { captureWorkflowFailure } from "@/lib/sentry";
import { PreviewCanvas, PreviewSkeleton } from "./preview-surface";
import { PageField, PreviewToolbar, ToolbarGroup, ZoomControl } from "./preview-toolbar";
import {
  formatPages,
  mostVisiblePage,
  pageHeight,
  pagesToRender,
  pdfBoxRect,
  type PdfHighlight,
  type PdfPageView,
} from "./pdf-pages";

// Self-hosted worker: the bundler emits it under the app origin, so `script-src 'self'` covers it.
pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  "pdfjs-dist/build/pdf.worker.min.mjs",
  import.meta.url,
).toString();

// pdf.js reads the original by HTTP range: after the first response headers it cancels the full download and
// requests only the 1 MiB chunks the rendered pages need. Originals up to 2 MiB are still read whole.
// Image decoders (JBIG2, JPEG 2000) for scanned originals, copied from pdfjs-dist by vite.config.ts. The CSP does not
// allow WebAssembly, so pdf.js uses their JavaScript fallbacks.
const wasmUrl = `/assets/pdfjs-${pdfjs.version}/`;
const RANGE_OPTIONS = {
  disableStream: true,
  disableAutoFetch: true,
  rangeChunkSize: 1024 * 1024,
  wasmUrl,
};
// When range loading fails, one whole read, as before range support; the failure is reported.
const WHOLE_OPTIONS = { disableRange: true, wasmUrl };
// Multiples of the fit-to-width size: a Letter page in a 400px panel needs 2–3× for body text.
const ZOOM_STEPS = [1, 1.5, 2, 3] as const;

/**
 * Shows the whole authorized original PDF, opened at the first cited page with the cited regions highlighted
 * from extraction provenance. Only pages near the viewport are rendered; the rest are sized placeholders.
 */
export function PdfView({
  url,
  pages,
  boxes,
  thumbnails = false,
  onReady,
}: {
  /** A same-origin URL, or the file itself: the CSP (connect-src 'self') does not let pdf.js fetch blob: URLs. */
  url: string | Blob;
  pages: readonly number[];
  boxes: readonly PdfHighlight[];
  /** A page rail beside the document, for a reader wide enough to hold one. */
  thumbnails?: boolean;
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
  const renderedWidth = Math.round(fitWidth * ZOOM_STEPS[zoom]!);
  const [pageViews, setPageViews] = useState<Record<number, PdfPageView>>({});
  const [nearPages, setNearPages] = useState<ReadonlySet<number>>(() => new Set());
  const [visiblePage, setVisiblePage] = useState<number>();
  // A new object per opening: react-pdf releases the document on unmount and never shares it across readers.
  const file = useMemo(() => (typeof url === "string" ? { url } : url), [url]);
  const cited = pages.length ? pages : [1];
  const anchor = Math.min(cited[0]!, pageCount ?? cited[0]!);
  const currentPage = visiblePage ?? anchor;
  const figures = useRef(new Map<number, HTMLElement>());
  const firstBox = useRef<HTMLSpanElement | null>(null);
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
        setNearPages(new Set(near));
      },
      { root, rootMargin: "100% 0px" },
    );
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
    };
  }, [pageCount]);

  useEffect(() => {
    currentPageRef.current = currentPage;
  }, [currentPage]);

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
  }, [pageCount, anchor, anchorView, renderedWidth]);

  const goToPage = (page: number) => {
    figures.current.get(page)?.scrollIntoView({ block: "start", inline: "nearest" });
  };

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
  const rendered = new Set(pageCount ? pagesToRender(nearPages, anchor, pageCount) : []);
  const fallbackView = anchorView ?? Object.values(pageViews)[0];

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      <Document
        key={wholeFile ? "whole" : "range"}
        file={file}
        options={wholeFile ? WHOLE_OPTIONS : RANGE_OPTIONS}
        suspense={false}
        loading={loadingPages}
        error={wholeFile ? failed : loadingPages}
        onLoadError={(error) => {
          captureWorkflowFailure(error, {
            workflow: "pdf-view",
            stage: wholeFile ? "whole-load" : "range-load",
            failureKind: error.name,
          });
          setWholeFile(true);
        }}
        onLoadSuccess={(document) => setPageCount(document.numPages)}
        className="flex min-h-0 flex-1"
      >
        {thumbnails && pageCount ? (
          <PdfThumbnails total={pageCount} current={currentPage} onGo={goToPage} />
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
                        onRenderSuccess={pageNumber === anchor ? onReady : undefined}
                        onLoadSuccess={(page) =>
                          setPageViews((current) =>
                            current[pageNumber]
                              ? current
                              : {
                                  ...current,
                                  [pageNumber]: { view: [...page.view], rotate: page.rotate },
                                },
                          )
                        }
                      />
                    ) : null}
                    {/*
                      One multiplying layer for the whole page. Regions overlap — a passage spanning two
                      lines of a table records a box for each — and multiplying each one onto the last
                      darkens the overlap towards red until the page is unreadable. Painted together and
                      multiplied once, an overlap is the same yellow as a single region.
                    */}
                    {show && view ? (
                      <div className="pointer-events-none absolute inset-0 opacity-55 mix-blend-multiply">
                        {boxes
                          .filter((box) => box.page === pageNumber)
                          .map((box, boxIndex) => {
                            const rect = pdfBoxRect(box, view, renderedWidth);
                            if (!rect) return null;
                            return (
                              <span
                                key={`${box.left}:${box.top}:${boxIndex}`}
                                ref={pageNumber === anchor && boxIndex === 0 ? firstBox : undefined}
                                role="img"
                                aria-label={ui("Vùng được trích dẫn trên trang {{page}}", {
                                  page: pageNumber,
                                })}
                                data-slot="pdf-citation-box"
                                className="absolute scroll-m-12 rounded-sm bg-pdf-highlight ring-1 ring-pdf-highlight-border/80"
                                style={rect}
                              />
                            );
                          })}
                      </div>
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
          <ZoomControl
            label={
              zoom === 0
                ? ui("Vừa khung")
                : ui("{{percent}}%", { percent: ZOOM_STEPS[zoom]! * 100 })
            }
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
 * The page rail: every page as a button, but only the ones scrolled into the rail are drawn, so opening a
 * long document does not render a hundred pages twice.
 */
function PdfThumbnails({
  total,
  current,
  onGo,
}: {
  total: number;
  current: number;
  onGo: (page: number) => void;
}) {
  const ui = useAppTranslation();
  const rail = useRef<HTMLDivElement>(null);
  const buttons = useRef(new Map<number, HTMLElement>());
  const [shown, setShown] = useState<ReadonlySet<number>>(() => new Set());
  useEffect(() => {
    const root = rail.current;
    if (!root) return undefined;
    const near = new Set<number>();
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          const page = Number((entry.target as HTMLElement).dataset.page);
          if (entry.isIntersecting) near.add(page);
          else near.delete(page);
        }
        setShown(new Set(near));
      },
      { root, rootMargin: "200% 0px" },
    );
    for (const button of buttons.current.values()) observer.observe(button);
    return () => observer.disconnect();
  }, [total]);
  // The rail follows the document, so the page being read stays visible in it.
  useEffect(() => {
    buttons.current.get(current)?.scrollIntoView({ block: "nearest" });
  }, [current]);
  return (
    <nav
      ref={rail}
      aria-label={ui("Các trang")}
      className="hidden w-32 shrink-0 overflow-y-auto overscroll-contain border-border-subtle border-r bg-surface-base p-2 lg:block"
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
                className="block w-full cursor-pointer rounded-md p-1 outline-none ring-1 ring-transparent transition-shadow focus-visible:ring-3 focus-visible:ring-focus-ring/40 aria-[current]:ring-pdf-highlight-border"
              >
                <span
                  className="flex items-center justify-center overflow-hidden bg-surface-document shadow-sm"
                  style={{ width: THUMBNAIL_WIDTH, height: Math.round(THUMBNAIL_WIDTH * 1.294) }}
                >
                  {shown.has(page) ? (
                    <Page
                      pageNumber={page}
                      width={THUMBNAIL_WIDTH}
                      loading=""
                      renderTextLayer={false}
                      renderAnnotationLayer={false}
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
