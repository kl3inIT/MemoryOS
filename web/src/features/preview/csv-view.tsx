import { useEffect, useRef } from "react";
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

/**
 * The first row is the header; the first column stays visible while wide tables scroll sideways.
 *
 * A cited row is marked by the row number the extraction recorded, not by searching for its text: the
 * extraction and this preview format numbers and dates differently, and the row number is exact.
 */
export function CsvView({
  csv,
  truncated = false,
  cited = [],
  active,
}: {
  csv: string;
  truncated?: boolean;
  /** Sheet row numbers of the cited rows, where row 0 is the header line. */
  cited?: readonly number[];
  /** Which of those rows the reader is on; it is scrolled into view and marked. */
  active?: number;
}) {
  const ui = useAppTranslation();
  const opened = useRef<HTMLTableRowElement>(null);
  useEffect(() => {
    opened.current?.scrollIntoView({ block: "center" });
  }, [active]);
  const [header = [], ...rows] = parseCsv(csv);
  const columns = Math.max(header.length, ...rows.map((row) => row.length));
  if (!columns) return <p className="text-sm text-content-secondary">{ui("Trang tính trống")}</p>;
  const shown = rows.slice(0, MAX_TABLE_ROWS);
  const marked = new Set(cited);
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
            {shown.map((row, rowIndex) => {
              // The header is line 0, so the first data row is sheet row 1.
              const sheetRow = rowIndex + 1;
              const current = active === sheetRow;
              return (
                <TableRow
                  key={rowIndex}
                  ref={current ? opened : undefined}
                  data-cited={marked.has(sheetRow) ? "true" : undefined}
                  aria-current={current ? "true" : undefined}
                  className={cn(
                    "bg-surface-base",
                    // A marked row keeps its colour under the pointer; the mark is what the reader came for.
                    marked.has(sheetRow) &&
                      "bg-evidence-highlight-surface/60 hover:bg-evidence-highlight-surface/60",
                    current && "bg-evidence-highlight-surface hover:bg-evidence-highlight-surface",
                  )}
                >
                  {Array.from({ length: columns }, (_, index) => (
                    <TableCell
                      key={index}
                      title={row[index] || undefined}
                      className={cn(
                        "max-w-80 truncate whitespace-nowrap",
                        index === 0 && "sticky left-0 bg-inherit font-medium",
                        // The accent repeats the highlight colour the reader sees inside a document.
                        index === 0 && current && "border-pdf-highlight border-l-4",
                      )}
                    >
                      {row[index] ?? ""}
                    </TableCell>
                  ))}
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>
      {(truncated || rows.length > MAX_TABLE_ROWS) && (
        <p className="text-xs text-content-muted">{ui("Bản xem trước bị cắt bớt")}</p>
      )}
    </div>
  );
}
