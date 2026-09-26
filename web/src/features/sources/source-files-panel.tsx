import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  createColumnHelper,
  rowPaginationFeature,
  tableFeatures,
  useTable,
} from "@tanstack/react-table";
import { FileText } from "lucide-react";
import { useRef } from "react";
import { DataTable, type DataTableColumnMeta } from "@/components/data-table/data-table";
import { EmptyState } from "@/components/composites/empty-state";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardHeader } from "@/components/ui/card";
import { HelpPopover } from "@/components/ui/help-popover";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import { Spinner } from "@/components/ui/spinner";
import { TablePagination } from "@/components/ui/table-pagination";
import type { SourceItem, SourceSummary } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { useManualRefresh } from "@/lib/use-manual-refresh";
import { FileTypeIcon } from "@/features/sources/shared/file-type-icon";
import { sourceMutationError, sourceStatusMessage } from "@/features/sources/shared/source-errors";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";
import { cursorTablePaging } from "@/components/data-table/use-cursor-paging";
import { HistoryTime, ItemStatus } from "@/features/sources/history/source-history-presentation";
import { SourceFileActions } from "./source-file-actions";
import type { SourceFiles } from "./use-source-files";

type FileTableMeta = {
  source: SourceSummary;
  /** File actions wait while other work on the Source or the file list holds them. */
  busy: boolean;
  working: (itemId: string) => boolean;
  onReindex?: (item: SourceItem) => void;
  onRemove?: (item: SourceItem) => Promise<void>;
};

const features = tableFeatures({
  rowPaginationFeature,
  columnMeta: {} as DataTableColumnMeta,
  tableMeta: {} as FileTableMeta,
});
const column = createColumnHelper<typeof features, SourceItem>();

/* Columns live at module scope, because a cell renderer is a component. */
const columns = column.columns([
  column.display({
    id: "file",
    header: function FileHeader() {
      const ui = useAppTranslation();
      return ui("File name");
    },
    cell: function FileCell({ row }) {
      const ui = useAppTranslation();
      const item = row.original;
      return (
        <div className="wrap-anywhere">
          <span className="flex min-w-0 items-start gap-2 font-medium text-content-primary">
            <FileTypeIcon name={item.filename} />
            <span className="min-w-0">{item.filename ?? ui("Uploaded file")}</span>
          </span>
          {item.errorCode ? (
            // Work stopped by the operator's own pause or delete is expected, so it reads as a note.
            <p
              className={cn(
                "mt-1 text-xs",
                item.errorCode === "SOURCE_PAUSED" || item.errorCode === "SOURCE_DELETING"
                  ? "text-content-muted"
                  : "text-status-danger-content",
              )}
            >
              {ui(sourceStatusMessage(item.errorCode))}
            </p>
          ) : null}
          {item.searchStatus === "FAILED" && item.searchErrorCode ? (
            <p className="mt-1 text-xs text-status-danger-content">
              {ui(sourceStatusMessage(item.searchErrorCode))}
            </p>
          ) : null}
        </div>
      );
    },
  }),
  column.display({
    id: "size",
    header: function SizeHeader() {
      const ui = useAppTranslation();
      return ui("Size");
    },
    meta: { width: "w-24" },
    cell: function SizeCell({ row }) {
      const ui = useAppTranslation();
      return (
        <span className="whitespace-nowrap text-content-muted">
          {row.original.sizeBytes == null ? ui("Unknown") : formatBytes(row.original.sizeBytes)}
        </span>
      );
    },
  }),
  column.display({
    id: "status",
    header: function StatusHeader() {
      const ui = useAppTranslation();
      return ui("Status");
    },
    meta: { width: "w-56" },
    cell: ({ row, table }) => {
      const status = table.options.meta?.source.status;
      return (
        <span className="text-content-secondary">
          <ItemStatus
            item={row.original}
            sourcePaused={status === "PAUSED" || status === "PAUSING"}
          />
        </span>
      );
    },
  }),
  column.display({
    id: "indexed",
    header: function IndexedHeader() {
      const ui = useAppTranslation();
      return ui("Last indexed");
    },
    meta: { width: "w-40" },
    cell: ({ row }) => (
      <span className="whitespace-nowrap text-content-secondary">
        <HistoryTime value={row.original.lastIndexedAt} />
      </span>
    ),
  }),
  column.display({
    id: "actions",
    header: function ActionsHeader() {
      const ui = useAppTranslation();
      return ui("Actions");
    },
    meta: { width: "w-24", align: "end" },
    cell: ({ row, table }) => {
      const meta = table.options.meta;
      if (!meta) return null;
      const { onReindex, onRemove } = meta;
      const item = row.original;
      return (
        <SourceFileActions
          filename={item.filename}
          pending={meta.working(item.id)}
          disabled={meta.busy || item.status === "DELETING" || meta.source.status === "DELETING"}
          onReindex={onReindex ? () => onReindex(item) : undefined}
          onRemove={onRemove ? () => onRemove(item) : undefined}
          removeError={(cause) => sourceMutationError(cause, "remove-item")}
        />
      );
    },
  }),
]);

/** The Source's current files with their indexing state and per-file actions. */
export function SourceFilesPanel({
  source,
  files,
  canUpload,
  disabled,
  working,
  onReindex,
  onRemove,
}: {
  source: SourceSummary;
  files: SourceFiles;
  canUpload: boolean;
  /** Other work on the Source holds the file actions still. */
  disabled: boolean;
  working: (itemId: string) => boolean;
  onReindex?: (item: SourceItem) => void;
  onRemove?: (item: SourceItem) => Promise<void>;
}) {
  const ui = useAppTranslation();
  const { items, paging } = files;
  const filesHeading = useRef<HTMLHeadingElement | null>(null);
  // The files poll while they change, so the refresh control follows the press rather than the poll.
  const filesRefresh = useManualRefresh(items.refetch);
  const table = useTable({
    features,
    columns,
    data: items.data?.items ?? [],
    getRowId: (item) => item.id,
    meta: { source, busy: disabled || items.isError, working, onReindex, onRemove },
    ...cursorTablePaging(paging, files.size, items.data?.nextCursor, files.totalPages),
  });

  return (
    <section aria-labelledby="source-files-heading" className="mt-5">
      <Card>
        <CardHeader>
          <div className="flex items-center gap-3">
            <SourceSectionIcon icon={FileText} />
            <h2
              ref={filesHeading}
              id="source-files-heading"
              tabIndex={-1}
              className="font-heading-h3 text-content-primary focus-visible:outline-2 focus-visible:outline-focus-ring"
            >
              {ui("Files")}
            </h2>
            <HelpPopover label={ui("Files and indexing times")}>
              <p>
                {ui(
                  "Current files acquired by this Source, not a log of sync runs. Last indexed is the latest retained successful attempt for the current file version; Unknown means no retained success is known.",
                )}
              </p>
              <p>
                {ui(
                  "A previous indexing success does not make a pending or failed current attempt successful.",
                )}
              </p>
            </HelpPopover>
          </div>
          <CardAction>
            <div className="flex items-center gap-2">
              {source.pendingWork ? <LoadingLabel label={ui("Work pending")} /> : null}
              <Button
                prominence="tertiary"
                pending={filesRefresh.pending}
                onClick={filesRefresh.refresh}
              >
                {ui("Refresh files")}
              </Button>
            </div>
          </CardAction>
        </CardHeader>
        <CardContent>
          <div className="flex flex-col gap-3">
            {items.isError ? (
              <p role="alert" className="text-sm text-status-danger-content">
                {ui(
                  "Files could not be loaded. Displayed files may be out of date. Retry this page or return to a previous page.",
                )}
              </p>
            ) : null}
            {items.isPending ? (
              <div className="py-8">
                <LoadingLabel label={ui("Loading files")} />
              </div>
            ) : items.data ? (
              <DataTable
                table={table}
                label={ui("Source files table")}
                className="min-w-4xl"
                empty={
                  <EmptyState
                    title={paging.hasPrevious ? ui("No files on this page") : ui("No files yet")}
                    detail={ui(
                      paging.hasPrevious
                        ? "Files may have been removed. Return to the previous page or refresh this page."
                        : source.type === "GOOGLE_DRIVE"
                          ? "Files appear here after synchronization acquires them from Google Drive."
                          : source.type === "SHAREPOINT"
                            ? "Files appear here after synchronization acquires them from SharePoint."
                            : canUpload
                              ? "Upload one supported file to start indexing."
                              : "No files are indexed in this Source.",
                    )}
                  />
                }
              />
            ) : null}
            <TablePagination
              label={ui("Files pagination")}
              page={paging.page}
              totalPages={files.totalPages}
              previousLabel={ui("Previous files")}
              nextLabel={ui("Next files")}
              previousDisabled={!paging.hasPrevious || items.isPlaceholderData}
              nextDisabled={!items.data?.nextCursor || items.isPlaceholderData || items.isError}
              onPrevious={() => {
                filesHeading.current?.focus();
                table.previousPage();
              }}
              onNext={() => {
                filesHeading.current?.focus();
                table.nextPage();
              }}
            >
              <PageSizeSelect
                label={ui("Files per page")}
                rowsLabel={ui("Rows")}
                value={files.size}
                sizes={[5, 10, 25, 50, 100]}
                disabled={items.isPlaceholderData}
                onSizeChange={files.changeSize}
              />
            </TablePagination>
          </div>
        </CardContent>
      </Card>
    </section>
  );
}

function LoadingLabel({ label }: { label: string }) {
  return (
    <span className="inline-flex items-center gap-2 text-sm text-content-muted">
      <Spinner aria-hidden="true" />
      {label}
    </span>
  );
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}
