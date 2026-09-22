import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { MAX_TABLE_ROWS, parseCsv } from "./preview-kind";

/** The first row is the header; the first column stays visible while wide tables scroll sideways. */
export function CsvView({ csv, truncated = false }: { csv: string; truncated?: boolean }) {
  const ui = useAppTranslation();
  const [header = [], ...rows] = parseCsv(csv);
  const columns = Math.max(header.length, ...rows.map((row) => row.length));
  if (!columns) return <p className="text-sm text-content-secondary">{ui("Trang tính trống")}</p>;
  const shown = rows.slice(0, MAX_TABLE_ROWS);
  return (
    <div className="flex flex-col gap-2">
      <div className="overflow-auto rounded-lg border border-border-subtle bg-surface-base">
        <Table>
          <TableHeader className="sticky top-0 bg-surface-subtle">
            <TableRow>
              {Array.from({ length: columns }, (_, index) => (
                <TableHead
                  key={index}
                  className={cn(
                    "whitespace-nowrap",
                    index === 0 && "sticky left-0 bg-surface-subtle",
                  )}
                >
                  {header[index] ?? ""}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {shown.map((row, rowIndex) => (
              <TableRow key={rowIndex}>
                {Array.from({ length: columns }, (_, index) => (
                  <TableCell
                    key={index}
                    title={row[index] || undefined}
                    className={cn(
                      "max-w-80 truncate whitespace-nowrap",
                      index === 0 && "sticky left-0 bg-surface-base font-medium",
                    )}
                  >
                    {row[index] ?? ""}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {(truncated || rows.length > MAX_TABLE_ROWS) && (
        <p className="text-xs text-content-muted">{ui("Bản xem trước bị cắt bớt")}</p>
      )}
    </div>
  );
}
