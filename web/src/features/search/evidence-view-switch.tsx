import { FileText, TextQuote } from "lucide-react";
import { Tabs } from "radix-ui";
import { lazy, Suspense, useState, type ReactNode } from "react";
import type { PdfHighlight } from "@/features/preview/pdf-pages";
import { PreviewCanvas, PreviewSkeleton } from "@/features/preview/preview-surface";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { OriginalReader } from "@/features/preview/original-view";
import { firstEvidenceView, type EvidenceView } from "./evidence-order";

// The readers pull in pdf.js and docx-preview, so they load only when a reader opens the original.
const OriginalView = lazy(() =>
  import("@/features/preview/original-view").then((module) => ({ default: module.OriginalView })),
);

export type OriginalEvidence = {
  reader: OriginalReader;
  filename: string;
  mediaType?: string | null;
  /** Pages and regions the extraction recorded for the citation; PDF only. */
  pages: readonly number[];
  boxes: readonly PdfHighlight[];
  /** The cited passage text, which the original view locates and paints. */
  citations: readonly string[];
};

const trigger =
  "inline-flex h-7 min-w-0 cursor-pointer items-center justify-center gap-1.5 rounded-md px-3 font-secondary-action text-content-muted outline-none transition-[color,background-color,box-shadow] duration-150 hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40 data-[state=active]:bg-surface-base data-[state=active]:text-content-primary data-[state=active]:shadow-xs motion-reduce:transition-none [&_svg]:size-3.5 [&_svg]:shrink-0";

const panel = "flex min-h-0 flex-1 flex-col outline-none";

/**
 * A segmented tab list between the extracted passages and the stored original, with the cited passages
 * highlighted in the original. Which one opens first follows the file's kind (see `firstEvidenceView`),
 * except that a cited table row always opens the original: the passage text flattens the table's columns,
 * which the original keeps (owner decision 2026-09-15). A parent that shows the same evidence in two places
 * controls `view` so both stay on the same tab.
 */
export function EvidenceViewSwitch({
  original,
  table = false,
  view,
  onViewChange,
  children,
}: {
  original?: OriginalEvidence;
  /** The cited passage is a table row, so the original opens first whatever its kind. */
  table?: boolean;
  view?: EvidenceView;
  onViewChange?: (view: EvidenceView) => void;
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const [ownView, setOwnView] = useState<EvidenceView>(
    original &&
      (table || firstEvidenceView(original.filename, original.mediaType || "") === "original")
      ? "original"
      : "passages",
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
            {ui("Tệp gốc")}
          </Tabs.Trigger>
        </Tabs.List>
      </div>
      <Tabs.Content value="passages" className={panel}>
        {children}
      </Tabs.Content>
      <Tabs.Content value="original" className={panel}>
        <Suspense
          fallback={
            <PreviewCanvas>
              <PreviewSkeleton />
            </PreviewCanvas>
          }
        >
          <OriginalView
            reader={original.reader}
            filename={original.filename}
            mediaType={original.mediaType}
            pages={original.pages}
            boxes={original.boxes}
            citations={original.citations}
          />
        </Suspense>
      </Tabs.Content>
    </Tabs.Root>
  );
}
