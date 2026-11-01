import { FileText, TextQuote } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import type { PdfHighlight } from "@/features/preview/pdf-pages";
import { LazyOriginalView } from "@/features/preview/lazy-original-view";
import { warmOriginal } from "@/features/preview/warm-original";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { OriginalReader } from "@/features/preview/original-view";
import { firstEvidenceView, type EvidenceView } from "./evidence-order";

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

const panel = "flex min-h-0 flex-1 flex-col";

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
  const shown = view ?? ownView;
  // The readers' code, and a PDF's first bytes, start with the tab rather than after its own code has run.
  const opened = original && shown === "original" ? original : undefined;
  const originalUrl = opened?.reader.url;
  const filename = opened?.filename;
  const mediaType = opened?.mediaType;
  useEffect(() => {
    if (originalUrl && filename) warmOriginal(filename, mediaType, originalUrl);
  }, [originalUrl, filename, mediaType]);
  if (!original) return <>{children}</>;
  return (
    <Tabs
      value={shown}
      onValueChange={(value) => {
        const next: EvidenceView = value === "original" ? "original" : "passages";
        setOwnView(next);
        onViewChange?.(next);
      }}
      className={panel}
    >
      <div className="shrink-0 border-b border-border-subtle px-4 py-2 sm:px-5">
        <TabsList aria-label={ui("Cách xem bằng chứng")}>
          <TabsTrigger value="passages">
            <TextQuote aria-hidden="true" />
            {ui("Đoạn trích")}
          </TabsTrigger>
          <TabsTrigger value="original">
            <FileText aria-hidden="true" />
            {ui("Tệp gốc")}
          </TabsTrigger>
        </TabsList>
      </div>
      <TabsContent value="passages" className={panel}>
        {children}
      </TabsContent>
      <TabsContent value="original" className={panel}>
        <LazyOriginalView
          reader={original.reader}
          filename={original.filename}
          mediaType={original.mediaType}
          pages={original.pages}
          boxes={original.boxes}
          texts={original.citations}
        />
      </TabsContent>
    </Tabs>
  );
}
