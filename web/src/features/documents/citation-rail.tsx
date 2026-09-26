import type { ReactNode } from "react";
import type { CitationConfidence } from "@/features/preview/file-preview";
import { formatPages } from "@/features/preview/pdf-pages";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { readSourceLocation } from "./source-provenance";

export type CitationEntry = {
  /** The cited passage text, without the header the chunker writes in front of it. */
  text: string;
  /** The heading trail the passage sits under, which is the context it is read in. */
  section?: string;
  /** Provenance of this citation, which carries its page or sheet when the extraction recorded one. */
  provenance?: readonly string[];
};

/**
 * The cited passages beside the original, one card each. A card states where its citation ended up, because
 * an unlocated citation is never drawn and a reader should be told that rather than left looking for a mark
 * that is not there.
 */
export function CitationRail({
  entries,
  confidence,
  active,
  onActivate,
  located,
  variant = "search",
  children,
}: {
  entries: readonly CitationEntry[];
  /** Where each citation ended up in the original, in the same order; empty until the original is read. */
  confidence: readonly CitationConfidence[];
  active: number;
  onActivate: (index: number) => void;
  /** False for an original with no text to search, such as an image or a PDF without OCR text. */
  located: boolean;
  /**
   * Where the passages came from. A Chat answer cited exactly these; a search ranked the document's
   * passages and kept only the strongest, so the rail must not let its count read as a total.
   */
  variant?: "search" | "chat";
  /** The rail's own actions, such as opening the whole extraction. */
  children?: ReactNode;
}) {
  const ui = useAppTranslation();
  const heading =
    variant === "chat"
      ? ui("Các đoạn được trích dẫn")
      : entries.length === 1
        ? ui("Đoạn khớp nhất")
        : ui("{{count}} đoạn khớp nhất", { count: entries.length });
  return (
    <aside
      aria-label={heading}
      data-slot="citation-rail"
      className="flex max-h-72 shrink-0 flex-col border-border-subtle border-t bg-surface-base lg:max-h-none lg:w-88 lg:border-t-0 lg:border-l"
    >
      <div className="flex shrink-0 items-center justify-between gap-3 px-5 pt-4 pb-3">
        <h3 className="flex items-center gap-2 font-secondary-action text-content-primary">
          {heading}
          {/* A ranked heading already carries its number; repeating it in a badge reads as a total. */}
          {variant === "chat" ? (
            <span className="rounded-full bg-surface-sunken px-1.5 py-0.5 font-secondary-body text-content-muted tabular-nums">
              {entries.length}
            </span>
          ) : null}
        </h3>
        {children}
      </div>
      <ol className="min-h-0 flex-1 space-y-2 overflow-y-auto overscroll-contain px-4 pb-4">
        {entries.map((entry, index) => {
          const place = readSourceLocation(entry.provenance ?? []);
          const pages = formatPages(place.pages);
          const current = index === active;
          return (
            <li key={index}>
              <button
                type="button"
                aria-current={current ? "true" : undefined}
                onClick={() => onActivate(index)}
                className={cn(
                  "relative block w-full cursor-pointer overflow-hidden rounded-xl border p-3 pl-4 text-left outline-none transition-colors duration-150 focus-visible:ring-3 focus-visible:ring-focus-ring/40 motion-reduce:transition-none",
                  current
                    ? "border-evidence-highlight-border/70 bg-evidence-highlight-surface"
                    : "border-border-subtle bg-surface-base hover:border-border-default hover:bg-surface-subtle",
                )}
              >
                {/* The accent repeats the highlight colour, so the rail and the mark read as one thing. */}
                <span
                  aria-hidden="true"
                  className={cn(
                    "absolute inset-y-0 left-0 w-1",
                    current ? "bg-pdf-highlight" : "bg-transparent",
                  )}
                />
                <div className="flex items-center gap-2 font-secondary-body text-content-muted">
                  <span className="tabular-nums">{index + 1}</span>
                  {pages ? <span>·</span> : null}
                  {pages ? <span>{ui("Trang {{pages}}", { pages })}</span> : null}
                  {place.sheet ? <span>·</span> : null}
                  {place.sheet ? <span>{place.sheet}</span> : null}
                </div>
                {entry.section && entry.section !== place.sheet ? (
                  <p className="mt-1 line-clamp-2 break-words font-secondary-body text-content-secondary">
                    {entry.section}
                  </p>
                ) : null}
                <p className="mt-1.5 line-clamp-4 whitespace-pre-wrap break-words font-main-ui-body text-content-primary leading-6">
                  {entry.text || "…"}
                </p>
                <Placement found={confidence[index]} located={located} />
              </button>
            </li>
          );
        })}
      </ol>
    </aside>
  );
}

/** A dot in the highlight's own colour, so "đã đánh dấu" looks like the mark the reader will find. */
function Placement({
  found,
  located,
}: {
  found: CitationConfidence | undefined;
  located: boolean;
}) {
  const ui = useAppTranslation();
  const [label, dot] = !located
    ? [ui("Không có lớp văn bản"), "bg-content-muted/40"]
    : found === "exact"
      ? [ui("Đã đánh dấu"), "bg-pdf-highlight"]
      : found === "approximate"
        ? [ui("Gần đúng"), "bg-pdf-highlight/50 ring-1 ring-pdf-highlight-border"]
        : found === "none"
          ? [ui("Không tìm thấy"), "bg-content-muted/40"]
          : [ui("Đang tìm…"), "animate-pulse bg-content-muted/40"];
  return (
    <p className="mt-2 flex items-center gap-1.5 font-secondary-body text-content-muted">
      <span aria-hidden="true" className={cn("size-2 shrink-0 rounded-full", dot)} />
      {label}
    </p>
  );
}
