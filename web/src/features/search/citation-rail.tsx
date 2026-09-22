import type { CitationConfidence } from "@/features/preview/original-view";
import { formatPages } from "@/features/preview/pdf-pages";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { readSourceLocation } from "./source-provenance";

export type CitationEntry = {
  /** The cited passage text, as the extraction recorded it. */
  text: string;
  /** Provenance of this citation, which carries its page or sheet when the extraction recorded one. */
  provenance?: readonly string[];
};

/**
 * The cited passages beside the original, one card each. A card says where its citation was found: nothing
 * when it is drawn in the original, and a stated reason when it is not, because an unlocated citation is
 * never drawn and the reader is told so rather than left to look for a mark that is not there.
 */
export function CitationRail({
  entries,
  confidence,
  active,
  onActivate,
  located,
  children,
}: {
  entries: readonly CitationEntry[];
  /** Where each citation ended up in the original, in the same order; empty until the original is read. */
  confidence: readonly CitationConfidence[];
  active: number;
  onActivate: (index: number) => void;
  /** False for an original with no text to search, such as an image or a PDF without OCR text. */
  located: boolean;
  /** The rail's own actions, such as opening the whole extraction. */
  children?: React.ReactNode;
}) {
  const ui = useAppTranslation();
  return (
    <aside
      aria-label={ui("Các đoạn được trích dẫn")}
      data-slot="citation-rail"
      className="flex max-h-64 shrink-0 flex-col border-border-subtle border-t bg-surface-base lg:max-h-none lg:w-80 lg:border-t-0 lg:border-l"
    >
      <div className="flex shrink-0 items-center justify-between gap-2 px-4 pt-3 pb-2">
        <h3 className="font-secondary-action text-content-secondary">
          {ui("Các đoạn được trích dẫn")}
        </h3>
        {children}
      </div>
      <ol className="min-h-0 flex-1 space-y-1.5 overflow-y-auto overscroll-contain px-3 pb-3">
        {entries.map((entry, index) => {
          const place = readSourceLocation(entry.provenance ?? []);
          const pages = formatPages(place.pages);
          const found = confidence[index];
          return (
            <li key={index}>
              <button
                type="button"
                aria-current={index === active ? "true" : undefined}
                onClick={() => onActivate(index)}
                className={cn(
                  "block w-full cursor-pointer rounded-xl border p-3 text-left outline-none transition-colors duration-150 focus-visible:ring-3 focus-visible:ring-focus-ring/40 motion-reduce:transition-none",
                  index === active
                    ? "border-evidence-highlight-border bg-evidence-highlight-surface"
                    : "border-border-subtle bg-surface-base hover:bg-surface-subtle",
                )}
              >
                <p className="line-clamp-4 whitespace-pre-wrap break-words font-secondary-body text-content-primary">
                  {entry.text || ui("Đang tải nội dung tài liệu…")}
                </p>
                <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 font-secondary-body text-content-muted">
                  {pages ? <span>{ui("Trang {{pages}}", { pages })}</span> : null}
                  {place.sheet ? (
                    <span>{ui("Trang tính {{name}}", { name: place.sheet })}</span>
                  ) : null}
                  <span>{placementLabel(found, located, ui)}</span>
                </p>
              </button>
            </li>
          );
        })}
      </ol>
    </aside>
  );
}

function placementLabel(
  found: CitationConfidence | undefined,
  located: boolean,
  ui: ReturnType<typeof useAppTranslation>,
) {
  if (!located) return ui("Tệp gốc không có lớp văn bản để đánh dấu");
  if (found === "exact") return ui("Đã đánh dấu trong tệp gốc");
  if (found === "approximate") return ui("Đã đánh dấu gần đúng");
  if (found === "none") return ui("Không tìm thấy đoạn này trong tệp gốc");
  return ui("Đang tìm trong tệp gốc…");
}
