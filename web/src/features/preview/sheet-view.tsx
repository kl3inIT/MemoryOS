import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { CsvView } from "./csv-view";
import type { Sheets } from "./preview-kind";

export function SheetView({ sheets }: { sheets: Sheets }) {
  const ui = useAppTranslation();
  if (!sheets.length)
    return <p className="p-4 text-sm text-content-secondary">{ui("Không đọc được bảng tính.")}</p>;
  return (
    <Tabs defaultValue="0" className="p-4">
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
          <CsvView csv={sheet.csv} truncated={sheet.truncated} />
        </TabsContent>
      ))}
    </Tabs>
  );
}
