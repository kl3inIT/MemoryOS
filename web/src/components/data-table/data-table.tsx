import type { ReactNode } from "react";
import type { ReactTable, RowData, TableFeatures, TableState } from "@tanstack/react-table";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { cn } from "@/lib/utils";

/**
 * Column options the data table reads, declared on the page's features as
 * `tableFeatures({ …, columnMeta: {} as DataTableColumnMeta })` so column definitions type them.
 */
export type DataTableColumnMeta = {
  /** Width of the column (the table has a fixed layout), as a `<col>` class such as `w-44`. */
  width?: string;
  /** Alignment of the column's header and cells. */
  align?: "end";
};

const metaOf = (meta: unknown) => meta as DataTableColumnMeta | undefined;

type DataTableProps<TFeatures extends TableFeatures, TData extends RowData> = {
  // The table is built by the page, which owns its features and server-side state.
  table: ReactTable<TFeatures, TData, Partial<TableState<TFeatures>>>;
  /** Accessible name of the table. */
  label: string;
  /** Layout of the table element, such as the `min-w-*` width below which it scrolls. */
  className?: string;
  /** Pagination drawn inside the frame under the rows. */
  footer?: ReactNode;
};

/**
 * The shadcn Data Table: a TanStack Table instance rendered through the `Table` primitives in one
 * bordered frame. Paging and sorting stay with the page (`manualPagination`, `manualSorting`), which
 * builds the table with `useTable` and passes it here.
 */
export function DataTable<TFeatures extends TableFeatures, TData extends RowData>({
  table,
  label,
  className,
  footer,
}: DataTableProps<TFeatures, TData>) {
  const columns = table.getAllLeafColumns();
  return (
    <div className="flex flex-col overflow-hidden rounded-lg border border-border-subtle">
      <Table aria-label={label} className={cn("table-fixed", className)}>
        <colgroup>
          {columns.map((column) => (
            <col key={column.id} className={metaOf(column.columnDef.meta)?.width} />
          ))}
        </colgroup>
        <TableHeader>
          {table.getHeaderGroups().map((group) => (
            <TableRow key={group.id}>
              {group.headers.map((header) => (
                <TableHead
                  key={header.id}
                  colSpan={header.colSpan}
                  className={
                    metaOf(header.column.columnDef.meta)?.align === "end"
                      ? "px-4 text-right"
                      : "px-4"
                  }
                >
                  {header.isPlaceholder ? null : <table.FlexRender header={header} />}
                </TableHead>
              ))}
            </TableRow>
          ))}
        </TableHeader>
        <TableBody>
          {table.getRowModel().rows.map((row) => (
            <TableRow key={row.id}>
              {row.getAllCells().map((cell) => (
                <TableCell
                  key={cell.id}
                  className={
                    metaOf(cell.column.columnDef.meta)?.align === "end"
                      ? "px-4 py-3 text-right align-top"
                      : "px-4 py-3 align-top"
                  }
                >
                  <table.FlexRender cell={cell} />
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {footer}
    </div>
  );
}
