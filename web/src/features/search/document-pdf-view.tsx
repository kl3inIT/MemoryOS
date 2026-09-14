import { Minus, Plus } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Document, Page, pdfjs } from "react-pdf";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { mostVisiblePage, pageHeight, pagesToRender } from "./pdf-page-window";
import { formatPages, pdfBoxRect, type PdfPageView, type ProvenanceBox } from "./source-provenance";

// Self-hosted worker: the bundler emits it under the app origin, so `script-src 'self'` covers it.
pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  "pdfjs-dist/build/pdf.worker.min.mjs",
  import.meta.url,
).toString();

// pdf.js reads the original by HTTP range: after the first response headers it cancels the full download and
// requests only the 1 MiB chunks the rendered pages need. Originals up to 2 MiB are still read whole.
const PDF_OPTIONS = { disableStream: true, disableAutoFetch: true, rangeChunkSize: 1024 * 1024 };
// Multiples of the fit-to-width size: a Letter page in a 400px panel needs 2–3× for body text.
const ZOOM_STEPS = [1, 1.5, 2, 3] as const;

/**
 * Shows the whole authorized original PDF, opened at the first cited page with the cited regions highlighted
 * from extraction provenance. Only pages near the viewport are rendered; the rest are sized placeholders.
 */
export function DocumentPdfView({
  url,
  pages,
  boxes,
}: {
  url: string;
  pages: readonly number[];
  boxes: readonly ProvenanceBox[];
}) {
  const ui = useAppTranslation();
  const container = useRef<HTMLDivElement>(null);
  const fitWidth = Math.max(240, useWidth(container));
  const [zoom, setZoom] = useState(0);
  const renderedWidth = Math.round(fitWidth * ZOOM_STEPS[zoom]!);
  const [pageCount, setPageCount] = useState<number>();
  const [pageViews, setPageViews] = useState<Record<number, PdfPageView>>({});
  const [nearPages, setNearPages] = useState<ReadonlySet<number>>(() => new Set());
  const [visiblePage, setVisiblePage] = useState<number>();
  // A new object per opening: react-pdf releases the document on unmount and never shares it across readers.
  const file = useMemo(() => ({ url }), [url]);
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

  const returnToCitation = () => {
    const target = firstBox.current ?? figures.current.get(anchor);
    target?.scrollIntoView({ block: firstBox.current ? "center" : "start", inline: "nearest" });
  };

  const status = (text: string) => (
    <p role="status" className="py-12 text-center font-main-ui-body text-content-secondary">
      {text}
    </p>
  );
  const failed = (
    <p role="alert" className="rounded-xl bg-status-danger-surface p-4 text-status-danger-content">
      {ui("Không mở được bản PDF gốc. Hãy xem đoạn trích.")}
    </p>
  );
  const rendered = new Set(pageCount ? pagesToRender(nearPages, anchor, pageCount) : []);
  const fallbackView = anchorView ?? Object.values(pageViews)[0];

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex shrink-0 items-center justify-between gap-3 border-b border-border-subtle px-4 py-1.5 sm:px-5">
        <div className="flex min-w-0 items-center gap-2">
          <p
            aria-live="polite"
            className="min-w-0 truncate font-secondary-body text-content-muted tabular-nums"
          >
            {pageCount
              ? ui("Trang {{pages}} / {{total}}", { pages: currentPage, total: pageCount })
              : ui("Trang {{pages}}", { pages: formatPages(cited) })}
          </p>
          {pageCount && !cited.includes(currentPage) ? (
            <Button size="sm" prominence="internal" className="shrink-0" onClick={returnToCitation}>
              {ui("Về đoạn trích dẫn")}
            </Button>
          ) : null}
        </div>
        <div role="group" aria-label={ui("Thu phóng")} className="flex shrink-0 items-center gap-1">
          <IconButton
            prominence="internal"
            size="sm"
            aria-label={ui("Thu nhỏ")}
            disabled={zoom === 0}
            onClick={() => setZoom((value) => Math.max(0, value - 1))}
          >
            <Minus />
          </IconButton>
          <span
            aria-live="polite"
            className="min-w-16 text-center font-secondary-action text-content-secondary tabular-nums"
          >
            {zoom === 0
              ? ui("Vừa khung")
              : ui("{{percent}}%", { percent: ZOOM_STEPS[zoom]! * 100 })}
          </span>
          <IconButton
            prominence="internal"
            size="sm"
            aria-label={ui("Phóng to")}
            disabled={zoom === ZOOM_STEPS.length - 1}
            onClick={() => setZoom((value) => Math.min(ZOOM_STEPS.length - 1, value + 1))}
          >
            <Plus />
          </IconButton>
        </div>
      </div>
      <div
        ref={container}
        className="min-h-0 flex-1 overflow-auto overscroll-contain bg-surface-sunken p-4 [scrollbar-gutter:stable] sm:px-5"
      >
        <Document
          file={file}
          options={PDF_OPTIONS}
          suspense={false}
          loading={status(ui("Đang tải trang PDF…"))}
          error={failed}
          onLoadSuccess={(document) => setPageCount(document.numPages)}
          className="w-fit min-w-full space-y-5"
        >
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
                  className="relative isolate bg-white shadow-md ring-1 ring-black/5"
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
                  {show && view
                    ? boxes
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
                              className="pointer-events-none absolute scroll-m-12 rounded-[3px] bg-pdf-highlight/55 mix-blend-multiply ring-1 ring-pdf-highlight-border/80"
                              style={rect}
                            />
                          );
                        })
                    : null}
                </div>
              </figure>
            );
          })}
        </Document>
      </div>
    </div>
  );
}

function useWidth(ref: React.RefObject<HTMLElement | null>) {
  const [width, setWidth] = useState(360);
  useEffect(() => {
    const element = ref.current;
    if (!element) return undefined;
    const observer = new ResizeObserver(([entry]) => {
      if (entry) setWidth(Math.floor(entry.contentRect.width));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref]);
  return width;
}
