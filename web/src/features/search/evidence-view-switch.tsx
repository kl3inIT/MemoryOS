import { FileText, TextQuote } from "lucide-react";
import { Tabs } from "radix-ui";
import { lazy, Suspense, useState, type ReactNode } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ProvenanceBox } from "./source-provenance";

// pdf.js and docx-preview load only when a reader opens the original view.
const DocumentPdfView = lazy(() =>
  import("./document-pdf-view").then((module) => ({ default: module.DocumentPdfView })),
);
const DocumentDocxView = lazy(() =>
  import("./document-docx-view").then((module) => ({ default: module.DocumentDocxView })),
);

/** The stored original a reader can show beside the passages, with the viewer its media type needs. */
export type OriginalEvidence =
  | {
      kind: "pdf";
      /** Same-origin URL of the authorized original; pdf.js reads it by HTTP range. */
      url: string;
      pages: readonly number[];
      boxes: readonly ProvenanceBox[];
      /** The cited passage is a table row, so its page view opens first. */
      table: boolean;
    }
  | { kind: "docx"; url: string };

export type EvidenceView = "passages" | "original";

const trigger =
  "inline-flex h-7 min-w-0 cursor-pointer items-center justify-center gap-1.5 rounded-md px-3 font-secondary-action text-content-muted outline-none transition-[color,background-color,box-shadow] duration-150 hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40 data-[state=active]:bg-surface-base data-[state=active]:text-content-primary data-[state=active]:shadow-xs motion-reduce:transition-none [&_svg]:size-3.5 [&_svg]:shrink-0";

const panel = "flex min-h-0 flex-1 flex-col outline-none";

/**
 * When the stored original is one a reader renders — a PDF with recorded page provenance, or a Word document —
 * a segmented tab list switches between the passages and that original: the cited pages with their regions
 * highlighted, or the whole Word document. Callers may choose the first view; otherwise passages open first,
 * except for table rows: the passage text flattens the table's columns, which the page keeps (owner decision
 * 2026-09-15). A parent that shows the same evidence in two places controls `view` so both stay on the same tab.
 */
export function EvidenceViewSwitch({
  original,
  defaultView,
  view,
  onViewChange,
  children,
}: {
  original?: OriginalEvidence;
  defaultView?: EvidenceView;
  view?: EvidenceView;
  onViewChange?: (view: EvidenceView) => void;
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const [ownView, setOwnView] = useState<EvidenceView>(
    defaultView ?? (original?.kind === "pdf" && original.table ? "original" : "passages"),
  );
  if (!original) return <>{children}</>;
  return (
    <Tabs.Root
      value={view ?? ownView}
      onValueChange={(value) => {
        const next: EvidenceView = value === "original" ? "original" : "passages";
        setOwnView(next);
        onViewChange?.(next);
      }}
      className={panel}
    >
      <div className="shrink-0 border-b border-border-subtle px-4 py-2 sm:px-5">
        <Tabs.List
          aria-label={ui("Cách xem bằng chứng")}
          className="inline-grid grid-cols-2 gap-0.5 rounded-lg bg-surface-sunken p-0.5"
        >
          <Tabs.Trigger value="passages" className={trigger}>
            <TextQuote aria-hidden="true" />
            {ui("Đoạn trích")}
          </Tabs.Trigger>
          <Tabs.Trigger value="original" className={trigger}>
            <FileText aria-hidden="true" />
            {original.kind === "pdf" ? ui("Trang PDF") : ui("Tài liệu gốc")}
          </Tabs.Trigger>
        </Tabs.List>
      </div>
      <Tabs.Content value="passages" className={panel}>
        {children}
      </Tabs.Content>
      <Tabs.Content value="original" className={panel}>
        <Suspense
          fallback={
            <p role="status" className="py-12 text-center font-main-ui-body text-content-secondary">
              {original.kind === "pdf" ? ui("Đang tải trang PDF…") : ui("Đang tải tài liệu…")}
            </p>
          }
        >
          {original.kind === "pdf" ? (
            <DocumentPdfView url={original.url} pages={original.pages} boxes={original.boxes} />
          ) : (
            <DocumentDocxView url={original.url} />
          )}
        </Suspense>
      </Tabs.Content>
    </Tabs.Root>
  );
}
