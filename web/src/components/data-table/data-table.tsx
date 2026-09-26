import type { ComponentProps, ReactNode } from "react";
import {
  type ReactTable,
  type Row,
  type RowData,
  type TableFeatures,
  type TableState,
} from "@tanstack/react-table";
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

/**
 * What a page sets on one body row: a click handler, `data-*` attributes and whether the row is
 * the selected one (drawn selected and exposed as `aria-selected`). A row with a click handler is
 * only a larger pointer target; a control inside the row stays the keyboard's way to the action.
 */
export type DataTableRowProps = Omit<ComponentProps<"tr">, "children" | "className" | "style"> & {
  selected?: boolean;
} & { [attribute: `data-${string}`]: string | undefined };

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
  /** Shown across the table in place of rows when there are none, such as an `EmptyState`. */
  empty?: ReactNode;
  /** Props of each body row. */
  rowProps?: (row: Row<TFeatures, TData>) => DataTableRowProps;
};

/**
 * The shadcn Data Table: a TanStack Table instance rendered through the `Table` primitives in one
 * bordered frame. Paging and sorting stay with the page (`manualPagination`, `manualSorting`), which
 * builds the table with `useTable` and passes it here. Columns hidden through the table's
 * `columnVisibility` state (`columnVisibilityFeature`) are not rendered.
 */
export function DataTable<TFeatures extends TableFeatures, TData extends RowData>({
  table,
  label,
  className,
  footer,
  empty,
  rowProps,
}: DataTableProps<TFeatures, TData>) {
  // The header groups leave hidden columns out, with or without `columnVisibilityFeature`, so the
  // bottom group's headers are the visible leaf columns.
  const columns = (table.getHeaderGroups().at(-1)?.headers ?? []).map((header) => header.column);
  const shown = new Set(columns.map((column) => column.id));
  const rows = table.getRowModel().rows;
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
          {rows.map((row) => {
            const { selected, ...props } = rowProps?.(row) ?? {};
            return (
              <TableRow
                key={row.id}
                {...props}
                data-state={selected ? "selected" : undefined}
                aria-selected={selected || undefined}
                className={props.onClick ? "cursor-pointer" : undefined}
              >
                {row
                  .getAllCells()
                  .filter((cell) => shown.has(cell.column.id))
                  .map((cell) => (
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
            );
          })}
          {rows.length === 0 && empty ? (
            <TableRow>
              <TableCell colSpan={columns.length} className="p-0">
                {empty}
              </TableCell>
            </TableRow>
          ) : null}
        </TableBody>
      </Table>
      {footer}
    </div>
  );
}
