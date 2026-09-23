import { useVirtualizer } from "@tanstack/react-virtual";
import { memo, useEffect, useMemo, useRef } from "react";
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

/** Every cell is one truncated line, so each row is the same height and none has to be measured. */
const ROW_HEIGHT = 37;

/** Rows kept rendered beyond the visible ones, so a flick of the wheel never shows empty space. */
const OVERSCAN = 16;

/** Column widths are pinned before rendering, because a virtualized table cannot size columns to content. */
const CHARACTER = 6;
const CELL_PADDING = 32;
const MIN_COLUMN = 72;
const MAX_COLUMN = 320;

/** How many rows the widths are judged from; a sheet is regular enough that a sample settles them. */
const WIDTH_SAMPLE = 200;

/**
 * A sheet as a table: the first row is the header, the first column stays visible while wide tables scroll
 * sideways, and only the rows in view are rendered. A financial sheet is a thousand rows of twenty columns,
 * and building all of it at once costs seconds the reader spends staring at an empty panel.
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
  const scroller = useRef<HTMLDivElement>(null);
  const sheet = useMemo(() => parseSheet(csv), [csv]);
  const rows = useVirtualizer({
    count: sheet.rows.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: OVERSCAN,
  });
  const opened = active === undefined ? -1 : active - 1;
  useEffect(() => {
    if (opened >= 0) rows.scrollToIndex(opened, { align: "center" });
  }, [opened, rows]);

  if (!sheet.widths.length)
    return <p className="text-sm text-content-secondary">{ui("Trang tính trống")}</p>;

  const marked = new Set(cited);
  const shown = rows.getVirtualItems();
  const before = shown[0]?.start ?? 0;
  const after = rows.getTotalSize() - (shown[shown.length - 1]?.end ?? 0);
  return (
    <div className="flex flex-col gap-2">
      <div
        ref={scroller}
        className="max-h-[70vh] overflow-auto overscroll-contain rounded-lg border border-border-subtle bg-surface-base"
      >
        <Table className="table-fixed" style={{ width: sheet.width }}>
          <colgroup>
            {sheet.widths.map((width, index) => (
              <col key={index} style={{ width }} />
            ))}
          </colgroup>
          <TableHeader className="sticky top-0 z-10 bg-surface-subtle">
            <TableRow>
              {sheet.widths.map((_, index) => (
                <TableHead
                  key={index}
                  className={cn(
                    "truncate whitespace-nowrap",
                    index === 0 && "sticky left-0 bg-surface-subtle",
                  )}
                >
                  {sheet.header[index] ?? ""}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            <Spacer height={before} columns={sheet.widths.length} />
            {shown.map((item) => {
              // The header is line 0, so the first data row is sheet row 1.
              const sheetRow = item.index + 1;
              return (
                <SheetRow
                  key={item.index}
                  row={sheet.rows[item.index]!}
                  columns={sheet.widths.length}
                  cited={marked.has(sheetRow)}
                  current={active === sheetRow}
                />
              );
            })}
            <Spacer height={after} columns={sheet.widths.length} />
          </TableBody>
        </Table>
      </div>
      {(truncated || sheet.cut) && (
        <p className="text-xs text-content-muted">{ui("Bản xem trước bị cắt bớt")}</p>
      )}
    </div>
  );
}

/** The rows outside the window, as height rather than elements, so the scrollbar spans the whole sheet. */
function Spacer({ height, columns }: { height: number; columns: number }) {
  if (height <= 0) return null;
  return (
    <tr aria-hidden="true">
      <td colSpan={columns} style={{ height, padding: 0, border: 0 }} />
    </tr>
  );
}

/** One rendered row. Its props are all plain values, so an unmarked row survives a citation step untouched. */
const SheetRow = memo(function SheetRow({
  row,
  columns,
  cited,
  current,
}: {
  row: readonly string[];
  columns: number;
  cited: boolean;
  current: boolean;
}) {
  return (
    <TableRow
      data-cited={cited ? "true" : undefined}
      aria-current={current ? "true" : undefined}
      style={{ height: ROW_HEIGHT }}
      className={cn(
        "bg-surface-base",
        // A marked row keeps its colour under the pointer; the mark is what the reader came for.
        cited && "bg-evidence-highlight-surface/60 hover:bg-evidence-highlight-surface/60",
        current && "bg-evidence-highlight-surface hover:bg-evidence-highlight-surface",
      )}
    >
      {Array.from({ length: columns }, (_, index) => (
        <TableCell
          key={index}
          title={row[index] || undefined}
          className={cn(
            "truncate whitespace-nowrap",
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
});

/** The parsed sheet and the column widths it will be drawn with, computed once per sheet. */
function parseSheet(csv: string) {
  const [header = [], ...body] = parseCsv(csv);
  const rows = body.slice(0, MAX_TABLE_ROWS);
  const columns = Math.max(header.length, ...rows.map((row) => row.length));
  const widths: number[] = [];
  for (let column = 0; column < columns; column++) {
    let longest = header[column]?.length ?? 0;
    for (let row = 0; row < Math.min(rows.length, WIDTH_SAMPLE); row++) {
      longest = Math.max(longest, rows[row]![column]?.length ?? 0);
    }
    widths.push(Math.min(Math.max(longest * CHARACTER + CELL_PADDING, MIN_COLUMN), MAX_COLUMN));
  }
  return {
    header,
    rows,
    widths,
    width: widths.reduce((total, width) => total + width, 0),
    cut: body.length > MAX_TABLE_ROWS,
  };
}
