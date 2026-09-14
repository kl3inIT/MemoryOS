import { useQuery } from "@tanstack/react-query";
import { Minus, Plus } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Document, Page, pdfjs } from "react-pdf";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { formatPages, pdfBoxRect, type PdfPageView, type ProvenanceBox } from "./source-provenance";

// Self-hosted worker: the bundler emits it under the app origin, so `script-src 'self'` covers it.
pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  "pdfjs-dist/build/pdf.worker.min.mjs",
  import.meta.url,
).toString();

const MAX_RENDERED_PAGES = 5;
// Multiples of the fit-to-width size: a Letter page in a 400px panel needs 2–3× for body text.
const ZOOM_STEPS = [1, 1.5, 2, 3] as const;

/**
 * Renders only the cited pages of an authorized original PDF and highlights the cited regions from
 * extraction provenance. Bytes are fetched on demand and dropped from the query cache seconds after the view closes.
 */
export function DocumentPdfView({
  queryKey,
  load,
  pages,
  boxes,
}: {
  queryKey: readonly unknown[];
  load: (signal: AbortSignal) => Promise<Blob>;
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
  const original = useQuery({
    queryKey: ["document-original", ...queryKey],
    queryFn: ({ signal }) => load(signal),
    retry: false,
    // One read per opening: survive development double-mounts briefly, then drop the bytes.
    staleTime: Infinity,
    gcTime: 5_000,
    refetchOnWindowFocus: false,
  });
  const cited = (pages.length ? pages : [1])
    .filter((page) => !pageCount || page <= pageCount)
    .slice(0, MAX_RENDERED_PAGES);
  const firstBox = useRef<HTMLSpanElement | null>(null);

  useEffect(() => {
    firstBox.current?.scrollIntoView({ block: "center", inline: "nearest" });
  }, [pageViews, renderedWidth]);

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
  const citedPages = formatPages(cited);

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex shrink-0 items-center justify-between gap-3 border-b border-border-subtle px-4 py-1.5 sm:px-5">
        <p className="min-w-0 truncate font-secondary-body text-content-muted">
          {citedPages
            ? pageCount
              ? ui("Trang {{pages}} / {{total}}", { pages: citedPages, total: pageCount })
              : ui("Trang {{pages}}", { pages: citedPages })
            : null}
        </p>
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
        {original.isPending ? (
          status(ui("Đang tải trang PDF…"))
        ) : original.isError ? (
          failed
        ) : (
          <Document
            file={original.data}
            loading={status(ui("Đang tải trang PDF…"))}
            error={failed}
            onLoadSuccess={(document) => setPageCount(document.numPages)}
            className="w-fit min-w-full space-y-5"
          >
            {cited.map((pageNumber, pageIndex) => {
              const view = pageViews[pageNumber];
              const pageBoxes = boxes.filter((box) => box.page === pageNumber);
              return (
                <figure
                  key={pageNumber}
                  data-slot="pdf-page"
                  data-page={pageNumber}
                  className="mx-auto w-fit"
                >
                  {cited.length > 1 ? (
                    <figcaption className="mb-1.5 font-secondary-action text-content-muted">
                      {ui("Trang {{pages}}", { pages: pageNumber })}
                    </figcaption>
                  ) : null}
                  {/* The canvas is always a white sheet, so the highlighter multiplies onto it in both themes. */}
                  <div className="relative isolate bg-white shadow-md ring-1 ring-black/5">
                    <Page
                      pageNumber={pageNumber}
                      width={renderedWidth}
                      renderTextLayer={false}
                      renderAnnotationLayer={false}
                      onLoadSuccess={(page) =>
                        setPageViews((current) => ({
                          ...current,
                          [pageNumber]: { view: [...page.view], rotate: page.rotate },
                        }))
                      }
                    />
                    {view
                      ? pageBoxes.map((box, boxIndex) => {
                          const rect = pdfBoxRect(box, view, renderedWidth);
                          if (!rect) return null;
                          return (
                            <span
                              key={`${box.left}:${box.top}:${boxIndex}`}
                              ref={pageIndex === 0 && boxIndex === 0 ? firstBox : undefined}
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
        )}
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
