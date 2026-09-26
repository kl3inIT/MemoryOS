import { useAppTranslation } from "@/i18n/use-app-translation";
import { FileText, LoaderCircle } from "lucide-react";
import { useRef } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { Button } from "@/components/ui/button";
import { HelpPopover } from "@/components/ui/help-popover";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { TablePagination } from "@/components/ui/table-pagination";
import type { SourceItem, SourceSummary } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { useManualRefresh } from "@/lib/use-manual-refresh";
import { FileTypeIcon } from "@/features/sources/shared/file-type-icon";
import { sourceMutationError, sourceStatusMessage } from "@/features/sources/shared/source-errors";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";
import { HistoryTime, ItemStatus } from "@/features/sources/history/source-history-presentation";
import { SourceFileActions } from "./source-file-actions";
import type { SourceFiles } from "./use-source-files";

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
  const paused = source.status === "PAUSED" || source.status === "PAUSING";
  const itemBusy = disabled || items.isError;

  return (
    <section
      aria-labelledby="source-files-heading"
      className="mt-5 rounded-xl border border-border-subtle bg-surface-raised p-4 sm:p-5"
    >
      <div className="mb-4 flex items-center justify-between gap-3">
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
      </div>
      {items.isError ? (
        <p role="alert" className="mb-3 text-sm text-status-danger-content">
          {ui(
            "Files could not be loaded. Displayed files may be out of date. Retry this page or return to a previous page.",
          )}
        </p>
      ) : null}
      {items.isPending ? (
        <div className="py-8">
          <LoadingLabel label={ui("Loading files")} />
        </div>
      ) : items.data?.items.length === 0 ? (
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
      ) : items.data ? (
        <div
          className="overflow-x-auto rounded-lg border border-border-subtle focus-visible:outline-2 focus-visible:outline-focus-ring"
          tabIndex={0}
          role="region"
          aria-label={ui("Source files table")}
        >
          <Table className="w-full min-w-[54rem] table-fixed text-left text-sm">
            <colgroup>
              <col />
              <col className="w-24" />
              <col className="w-56" />
              <col className="w-40" />
              <col className="w-20" />
            </colgroup>
            <TableHeader className="border-b border-border-subtle bg-surface-sunken text-content-muted">
              <TableRow>
                <TableHead scope="col" className="px-4 py-3 font-medium">
                  {ui("File name")}
                </TableHead>
                <TableHead scope="col" className="px-4 py-3 font-medium">
                  {ui("Size")}
                </TableHead>
                <TableHead scope="col" className="px-4 py-3 font-medium">
                  {ui("Status")}
                </TableHead>
                <TableHead scope="col" className="px-4 py-3 font-medium">
                  {ui("Last indexed")}
                </TableHead>
                <TableHead scope="col" className="px-4 py-3 text-right font-medium">
                  {ui("Actions")}
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody className="divide-y divide-border-subtle">
              {items.data.items.map((item) => (
                <TableRow key={item.id}>
                  <TableCell className="px-4 py-4 [overflow-wrap:anywhere]">
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
                  </TableCell>
                  <TableCell className="whitespace-nowrap px-4 py-4 text-content-muted">
                    {item.sizeBytes == null ? ui("Unknown") : formatBytes(item.sizeBytes)}
                  </TableCell>
                  <TableCell className="px-4 py-4 text-content-secondary">
                    <ItemStatus item={item} sourcePaused={paused} />
                  </TableCell>
                  <TableCell className="px-4 py-4 whitespace-nowrap text-content-secondary">
                    <HistoryTime value={item.lastIndexedAt} />
                  </TableCell>
                  <TableCell className="px-4 py-4 text-right">
                    <SourceFileActions
                      filename={item.filename}
                      pending={working(item.id)}
                      disabled={
                        itemBusy || item.status === "DELETING" || source.status === "DELETING"
                      }
                      onReindex={onReindex ? () => onReindex(item) : undefined}
                      onRemove={onRemove ? () => onRemove(item) : undefined}
                      removeError={(cause) => sourceMutationError(cause, "remove-item")}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      ) : null}
      <TablePagination
        label={ui("Files pagination")}
        className="mt-3"
        page={paging.page}
        totalPages={files.totalPages}
        previousLabel={ui("Previous files")}
        nextLabel={ui("Next files")}
        previousDisabled={!paging.hasPrevious || items.isPlaceholderData}
        nextDisabled={!items.data?.nextCursor || items.isPlaceholderData || items.isError}
        onPrevious={() => {
          filesHeading.current?.focus();
          paging.goPrevious();
        }}
        onNext={() => {
          filesHeading.current?.focus();
          paging.goNext(items.data?.nextCursor);
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
    </section>
  );
}

function LoadingLabel({ label }: { label: string }) {
  return (
    <span className="inline-flex items-center gap-2 text-sm text-content-muted">
      <LoaderCircle className="size-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />
      {label}
    </span>
  );
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}
