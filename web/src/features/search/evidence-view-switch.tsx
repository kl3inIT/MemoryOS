import { FileText, TextQuote } from "lucide-react";
import { Tabs } from "radix-ui";
import { lazy, Suspense, useState, type ReactNode } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ProvenanceBox } from "./source-provenance";

// pdf.js loads only when a reader opens the page view.
const DocumentPdfView = lazy(() =>
  import("./document-pdf-view").then((module) => ({ default: module.DocumentPdfView })),
);

export type PdfEvidence = {
  /** Same-origin URL of the authorized original; pdf.js reads it by HTTP range. */
  url: string;
  pages: readonly number[];
  boxes: readonly ProvenanceBox[];
};

const trigger =
  "inline-flex h-7 min-w-0 cursor-pointer items-center justify-center gap-1.5 rounded-md px-3 font-secondary-action text-content-muted outline-none transition-[color,background-color,box-shadow] duration-150 hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40 data-[state=active]:bg-surface-base data-[state=active]:text-content-primary data-[state=active]:shadow-xs motion-reduce:transition-none [&_svg]:size-3.5 [&_svg]:shrink-0";

const panel = "flex min-h-0 flex-1 flex-col outline-none";

/**
 * When the original is a PDF with recorded page provenance, a segmented tab list switches between passages and
 * the cited pages with their regions highlighted. Callers choose which view opens first; passages by default.
 */
export function EvidenceViewSwitch({
  pdf,
  defaultView = "passages",
  children,
}: {
  pdf?: PdfEvidence;
  defaultView?: "passages" | "pdf";
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const [view, setView] = useState<string>(defaultView);
  if (!pdf) return <>{children}</>;
  return (
    <Tabs.Root value={view} onValueChange={setView} className={panel}>
      <div className="shrink-0 border-b border-border-subtle px-4 py-2 sm:px-5">
        <Tabs.List
          aria-label={ui("Cách xem bằng chứng")}
          className="inline-grid grid-cols-2 gap-0.5 rounded-lg bg-surface-sunken p-0.5"
        >
          <Tabs.Trigger value="passages" className={trigger}>
            <TextQuote aria-hidden="true" />
            {ui("Đoạn trích")}
          </Tabs.Trigger>
          <Tabs.Trigger value="pdf" className={trigger}>
            <FileText aria-hidden="true" />
            {ui("Trang PDF")}
          </Tabs.Trigger>
        </Tabs.List>
      </div>
      <Tabs.Content value="passages" className={panel}>
        {children}
      </Tabs.Content>
      <Tabs.Content value="pdf" className={panel}>
        <Suspense
          fallback={
            <p role="status" className="py-12 text-center font-main-ui-body text-content-secondary">
              {ui("Đang tải trang PDF…")}
            </p>
          }
        >
          <DocumentPdfView {...pdf} />
        </Suspense>
      </Tabs.Content>
    </Tabs.Root>
  );
}
