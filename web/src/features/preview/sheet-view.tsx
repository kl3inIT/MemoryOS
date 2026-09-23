import { useState } from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { CsvView } from "./csv-view";
import type { Sheets } from "./preview-kind";
import type { SheetPlacement } from "./sheet-citations";

/**
 * A workbook, one tab per sheet. The cited rows are marked on their own sheet, and opening a citation brings
 * its sheet forward, so the reader never has to find which tab the passage came from.
 */
export function SheetView({
  sheets,
  placements = [],
  active = 0,
}: {
  sheets: Sheets;
  /** Where each citation was placed, in citation order; an entry is absent when it could not be placed. */
  placements?: readonly (SheetPlacement | undefined)[];
  active?: number;
}) {
  const ui = useAppTranslation();
  const opened = placements[active];
  // A sheet the reader picked holds until they open another citation, which brings its own sheet forward.
  const [picked, setPicked] = useState<{ tab: string; citation: number }>();
  const tab = picked?.citation === active ? picked.tab : String(opened?.sheetIndex ?? 0);
  if (!sheets.length)
    return <p className="p-4 text-sm text-content-secondary">{ui("Không đọc được bảng tính.")}</p>;
  return (
    <Tabs
      value={tab}
      onValueChange={(chosen) => setPicked({ tab: chosen, citation: active })}
      className="p-4"
    >
      <TabsList className="w-full justify-start overflow-x-auto">
        {sheets.map((sheet, index) => (
          <TabsTrigger
            key={index}
            value={String(index)}
            className="max-w-64 flex-none"
            title={sheet.name}
          >
            <span className="truncate">{sheet.name}</span>
          </TabsTrigger>
        ))}
      </TabsList>
      {sheets.map((sheet, index) => (
        <TabsContent key={index} value={String(index)}>
          <CsvView
            csv={sheet.csv}
            truncated={sheet.truncated}
            cited={placements
              .filter((placement) => placement?.sheetIndex === index)
              .map((placement) => placement!.row)}
            active={opened?.sheetIndex === index ? opened.row : undefined}
          />
        </TabsContent>
      ))}
    </Tabs>
  );
}
