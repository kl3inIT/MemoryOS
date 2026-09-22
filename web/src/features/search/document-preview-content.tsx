import { useAppTranslation } from "@/i18n/use-app-translation";
import { useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import type { DocumentReading } from "./document-reading";
import { stripGeneratedTitlePrefix } from "./search-presentation";
import type { DocumentSelection } from "./document-preview-dialog";

export function DocumentPreviewContent({
  selection,
  variant = "search",
  reading,
}: {
  selection: DocumentSelection;
  variant?: "search" | "chat";
  /** Shared with the original view, so both show the same match and the highlight follows the citation. */
  reading: DocumentReading;
}) {
  const ui = useAppTranslation();
  const { activeMatchIndex, activeMatch, from, detail } = reading;
  const contentRef = useRef<HTMLDivElement>(null);
  const matchingPassageRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (detail.data && from === activeMatch?.from && matchingPassageRef.current) {
      matchingPassageRef.current.scrollIntoView({ block: "center" });
    } else if (detail.data && contentRef.current) {
      contentRef.current.scrollTop = 0;
    }
  }, [activeMatch?.matchingOrdinal, activeMatch?.from, from, detail.data]);

  return (
    <>
      <div
        ref={contentRef}
        className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-4 sm:px-6"
        aria-busy={detail.isPending}
      >
        {detail.isPending ? (
          <p role="status" className="py-12 text-center font-main-ui-body text-content-secondary">
            {ui("Đang tải nội dung tài liệu…")}
          </p>
        ) : detail.isError || !detail.data ? (
          <div
            role="alert"
            className="rounded-xl bg-status-danger-surface p-4 text-status-danger-content"
          >
            <p className="font-main-ui-body">
              {ui("Tài liệu không còn khả dụng hoặc đã thay đổi. Hãy tìm lại phiên bản hiện tại.")}
            </p>
          </div>
        ) : (
          <div className={variant === "chat" ? "space-y-0" : "space-y-4"}>
            {detail.data.passages.map((passage) => {
              const isMatch =
                !!activeMatch &&
                passage.ordinal >= activeMatch.matchingOrdinal &&
                passage.ordinal <= (activeMatch.matchingEndOrdinal ?? activeMatch.matchingOrdinal);
              return (
                <article
                  key={passage.ordinal}
                  ref={
                    passage.ordinal === activeMatch?.matchingOrdinal
                      ? matchingPassageRef
                      : undefined
                  }
                  aria-current={isMatch ? "true" : undefined}
                  aria-label={isMatch ? ui("Đoạn được chọn") : undefined}
                  className={
                    isMatch
                      ? variant === "chat"
                        ? "scroll-m-8 border-l-2 border-evidence-highlight-border bg-evidence-highlight-surface px-4 py-3"
                        : "scroll-m-8 rounded-xl border border-evidence-highlight-border/60 bg-evidence-highlight-surface p-4"
                      : variant === "chat"
                        ? "px-4 py-3"
                        : "border-t border-border-subtle pt-4 first:border-0 first:pt-0"
                  }
                >
                  {isMatch && variant !== "chat" ? (
                    <p className="mb-2 font-secondary-action text-content-muted">
                      {ui("Đoạn được chọn")}
                    </p>
                  ) : null}
                  <p
                    className={`whitespace-pre-wrap break-words text-content-primary ${variant === "chat" ? "text-sm leading-7" : "font-main-content-body"}`}
                  >
                    {stripGeneratedTitlePrefix(passage.content, detail.data.title).trim()}
                  </p>
                </article>
              );
            })}
          </div>
        )}
      </div>

      {variant === "chat" && activeMatch && from !== activeMatch.from && (
        <div className="shrink-0 border-t border-border-subtle px-5 py-2">
          <Button size="sm" prominence="internal" onClick={() => reading.page(activeMatch.from)}>
            {ui("Về đoạn trích dẫn")}
          </Button>
        </div>
      )}

      {/* One footer row: match switcher (segmented, like the evidence tabs) and passage paging. */}
      {selection.matches.length > 1 || (!detail.isPending && !detail.isError && detail.data) ? (
        <footer className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-t border-border-subtle px-5 pt-2.5 pb-[max(0.625rem,env(safe-area-inset-bottom))] sm:px-6">
          {selection.matches.length > 1 ? (
            <nav
              aria-label={ui("Các đoạn khớp")}
              className="inline-flex max-w-full gap-0.5 overflow-x-auto rounded-lg bg-surface-sunken p-0.5"
            >
              {selection.matches.map((match, index) => (
                <button
                  key={match.matchingOrdinal}
                  type="button"
                  aria-current={index === activeMatchIndex ? "true" : undefined}
                  className="inline-flex h-7 shrink-0 cursor-pointer items-center rounded-md px-2.5 font-secondary-action text-content-muted outline-none transition-[color,background-color,box-shadow] duration-150 hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40 aria-[current]:bg-surface-base aria-[current]:text-content-primary aria-[current]:shadow-xs motion-reduce:transition-none"
                  onClick={() => reading.select(index)}
                >
                  {ui("Đoạn")} {index + 1}
                </button>
              ))}
            </nav>
          ) : (
            <span aria-hidden="true" />
          )}
          {!detail.isPending && !detail.isError && detail.data ? (
            <div className="ml-auto flex items-center gap-1.5">
              <Button
                size="sm"
                prominence="secondary"
                disabled={from === 0}
                onClick={() => reading.page(Math.max(0, from - 20))}
              >
                {ui("Phần trước")}
              </Button>
              <Button
                size="sm"
                prominence="secondary"
                disabled={!detail.data.hasMore}
                onClick={() => reading.page(from + 20)}
              >
                {ui("Phần tiếp")}
              </Button>
            </div>
          ) : null}
        </footer>
      ) : null}
    </>
  );
}
