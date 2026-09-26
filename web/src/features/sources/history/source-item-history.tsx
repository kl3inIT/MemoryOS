import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  createColumnHelper,
  rowPaginationFeature,
  tableFeatures,
  useTable,
} from "@tanstack/react-table";
import { CheckCircle2, History, RefreshCw } from "lucide-react";
import { useRef, useState } from "react";
import { DataTable, type DataTableColumnMeta } from "@/components/data-table/data-table";
import { EmptyState } from "@/components/composites/empty-state";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { HelpPopover } from "@/components/ui/help-popover";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { StatusBadge } from "@/components/ui/status-badge";
import { TablePagination } from "@/components/ui/table-pagination";
import { uiLocale } from "@/i18n/format";
import { statusLabel } from "@/i18n/status-copy";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listSourceIndexAttemptsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceIndexAttempt } from "@/lib/hey-api/types.gen";
import { useManualRefresh } from "@/lib/use-manual-refresh";
import { sourceStatusMessage } from "@/features/sources/shared/source-errors";
import { historyDuration } from "./source-history";
import { HistoryTime } from "./source-history-presentation";
import { terminalOperationStatuses } from "@/features/sources/shared/source-operations";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";
import { cursorTablePaging, useCursorPaging } from "@/features/sources/shared/use-cursor-paging";

const features = tableFeatures({
  rowPaginationFeature,
  columnMeta: {} as DataTableColumnMeta,
  tableMeta: {} as { viewDetails: (attempt: SourceIndexAttempt, opener: HTMLElement) => void },
});
const column = createColumnHelper<typeof features, SourceIndexAttempt>();

/* Columns live at module scope, because a cell renderer is a component. */
const columns = column.columns([
  column.display({
    id: "file",
    header: function FileHeader() {
      const ui = useAppTranslation();
      return ui("File");
    },
    cell: function FileCell({ row }) {
      const ui = useAppTranslation();
      return (
        <span className="font-medium break-words text-content-primary">
          {row.original.filename ?? ui("File name unavailable")}
        </span>
      );
    },
  }),
  column.display({
    id: "started",
    header: function StartedHeader() {
      const ui = useAppTranslation();
      return ui("Started");
    },
    meta: { width: "w-48" },
    cell: function StartedCell({ row }) {
      const ui = useAppTranslation();
      const attempt = row.original;
      return (
        <span className="whitespace-nowrap text-content-secondary">
          {attempt.startedAt ? (
            <HistoryTime value={attempt.startedAt} />
          ) : (
            <>
              <span className="block text-xs text-content-muted">
                {ui("Queued · start not recorded")}
              </span>
              <HistoryTime value={attempt.createdAt} />
            </>
          )}
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
    meta: { width: "w-36" },
    cell: ({ row }) => <AttemptStatus attempt={row.original} />,
  }),
  column.display({
    id: "completed",
    header: function CompletedHeader() {
      const ui = useAppTranslation();
      return ui("Completed / duration");
    },
    meta: { width: "w-48" },
    cell: function CompletedCell({ row }) {
      const ui = useAppTranslation();
      const attempt = row.original;
      return attempt.completedAt ? (
        <span className="whitespace-nowrap text-content-secondary">
          <HistoryTime value={attempt.completedAt} />
          <span className="block text-xs text-content-muted">
            {historyDuration(attempt.startedAt, attempt.completedAt) ?? ui("Duration unknown")}
          </span>
        </span>
      ) : (
        <span className="text-content-muted">—</span>
      );
    },
  }),
  column.display({
    id: "error",
    header: function ErrorHeader() {
      const ui = useAppTranslation();
      return ui("Error message");
    },
    cell: function ErrorCell({ row }) {
      const ui = useAppTranslation();
      return row.original.errorCode ? (
        <span className="line-clamp-2 text-xs break-words text-status-danger-content">
          {ui(sourceStatusMessage(row.original.errorCode))}
        </span>
      ) : (
        <span className="text-content-muted">—</span>
      );
    },
  }),
  column.display({
    id: "details",
    header: function DetailsHeader() {
      const ui = useAppTranslation();
      return <span className="sr-only">{ui("Indexing attempt details")}</span>;
    },
    meta: { width: "w-36", align: "end" },
    cell: function DetailsCell({ row, table }) {
      const ui = useAppTranslation();
      const attempt = row.original;
      return (
        <Button
          size="sm"
          prominence="tertiary"
          aria-label={ui("View details for {{v1}} queued {{v2}}", {
            v1: attempt.filename ?? ui("File name unavailable"),
            v2: new Date(attempt.createdAt).toLocaleString(uiLocale()),
          })}
          onClick={(event) => table.options.meta?.viewDetails(attempt, event.currentTarget)}
        >
          {ui("View details")}
        </Button>
      );
    },
  }),
]);

/** File indexing attempts of a Source; it loads when its section tab opens. */
export function SourceItemHistory({ sourceId }: { sourceId: string }) {
  const ui = useAppTranslation();

  const [size, setSize] = useState(5);
  const paging = useCursorPaging();
  const [detail, setDetail] = useState<SourceIndexAttempt | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const detailOpener = useRef<HTMLElement | null>(null);
  const history = useQuery({
    ...listSourceIndexAttemptsOptions({
      path: { sourceId },
      query: { size, cursor: paging.cursor },
    }),
    retry: false,
    staleTime: 0,
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      query.state.data?.items.some((operation) => !attemptFinished(operation)) ? 1_500 : false,
  });
  // An open attempt is polled, so the refresh control follows the press rather than the poll.
  const manualRefresh = useManualRefresh(history.refetch);
  const totalPages = history.data ? Math.ceil(history.data.totalItems / size) : undefined;
  // Polling keeps the open attempt current while it stays on the displayed page.
  const detailAttempt =
    detail && (history.data?.items.find((attempt) => attempt.id === detail.id) ?? detail);
  paging.clamp(totalPages);
  const table = useTable({
    features,
    columns,
    data: history.data?.items ?? [],
    getRowId: (attempt) => attempt.id,
    meta: {
      viewDetails: (attempt, opener) => {
        detailOpener.current = opener;
        setDetail(attempt);
        setDetailOpen(true);
      },
    },
    ...cursorTablePaging(paging, size, history.data?.nextCursor, totalPages),
  });

  return (
    <section aria-label={ui("File indexing attempts")} className="flex min-w-0 flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <SourceSectionIcon icon={History} />
          <h2 className="font-heading-h3 text-content-primary">{ui("File indexing attempts")}</h2>
          <HelpPopover label={ui("File indexing attempts")}>
            <p>
              {ui(
                "Each row processes one file version, including manual reindexing. The Files tab shows the current corpus; this history records individual file outcomes. Queued time is shown only when the actual processing start was not recorded.",
              )}
            </p>
          </HelpPopover>
        </div>
        <Button
          size="sm"
          prominence="tertiary"
          pending={manualRefresh.pending}
          onClick={manualRefresh.refresh}
        >
          <RefreshCw data-icon="inline-start" aria-hidden="true" />
          {ui("Refresh")}
        </Button>
      </div>
      {history.isError ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui("File attempts could not be refreshed. Displayed attempts may be out of date.")}
        </p>
      ) : history.isPending ? (
        <p role="status" className="text-sm text-content-muted">
          {ui("Loading file attempts…")}
        </p>
      ) : null}
      {history.data ? (
        history.data.items.length ? (
          <DataTable
            table={table}
            label={ui("File indexing attempts, newest first")}
            className="min-w-3xl"
            footer={
              <TablePagination
                label={ui("Indexing attempt pages")}
                page={paging.page}
                totalPages={totalPages}
                previousLabel={ui("Previous indexing attempts")}
                nextLabel={ui("Next indexing attempts")}
                previousDisabled={!table.getCanPreviousPage() || history.isPlaceholderData}
                nextDisabled={
                  !history.data.nextCursor || history.isPlaceholderData || history.isError
                }
                onPrevious={() => table.previousPage()}
                onNext={() => table.nextPage()}
              >
                <PageSizeSelect
                  label={ui("Rows per page")}
                  rowsLabel={ui("Rows")}
                  value={size}
                  sizes={[5, 10, 25, 50]}
                  disabled={history.isPlaceholderData}
                  onSizeChange={(next) => {
                    setSize(next);
                    paging.reset();
                  }}
                />
              </TablePagination>
            }
          />
        ) : (
          <EmptyState title={ui("No file indexing attempts on this page.")} />
        )
      ) : null}
      <Sheet open={detailOpen} onOpenChange={setDetailOpen}>
        <SheetContent
          className="w-full overflow-y-auto sm:max-w-xl"
          onCloseAutoFocus={(event) => {
            // Opened without a SheetTrigger, so Radix has no trigger to refocus.
            event.preventDefault();
            detailOpener.current?.focus();
          }}
        >
          {/* The header leaves room for the sheet's close button. */}
          <div className="border-b border-border-subtle pr-8">
            <SheetHeader>
              <SheetTitle>{ui("Indexing attempt details")}</SheetTitle>
              <SheetDescription className="break-words">
                {detailAttempt?.filename ?? ui("File name unavailable")}
              </SheetDescription>
            </SheetHeader>
          </div>
          {detailAttempt ? <AttemptDetails attempt={detailAttempt} /> : null}
        </SheetContent>
      </Sheet>
    </section>
  );
}

function attemptFinished(attempt: SourceIndexAttempt) {
  return Object.hasOwn(terminalOperationStatuses, attempt.status);
}

function AttemptStatus({ attempt }: { attempt: SourceIndexAttempt }) {
  const ui = useAppTranslation();
  const succeeded = attempt.status === "SUCCEEDED";
  return (
    <StatusBadge
      tone={
        succeeded
          ? "success"
          : attempt.status === "FAILED"
            ? "danger"
            : attemptFinished(attempt)
              ? "neutral"
              : "info"
      }
    >
      {succeeded ? <CheckCircle2 aria-hidden="true" /> : null}
      {succeeded ? ui("Indexed") : ui(statusLabel(attempt.status))}
    </StatusBadge>
  );
}

const attemptTimeline = [
  ["Queued", "createdAt"],
  ["Started", "startedAt"],
  ["Completed", "completedAt"],
] as const;

/** One file attempt. Attempts carry no item identity, so reindexing stays in the Files tab. */
function AttemptDetails({ attempt }: { attempt: SourceIndexAttempt }) {
  const ui = useAppTranslation();
  const finished = attemptFinished(attempt);
  return (
    <div className="flex min-w-0 flex-col gap-4 p-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border-subtle bg-surface-sunken px-4 py-3">
        <AttemptStatus attempt={attempt} />
        <span className="text-sm font-medium tabular-nums text-content-primary">
          {historyDuration(attempt.startedAt, attempt.completedAt) ??
            (finished ? ui("Duration unknown") : ui("In progress"))}
        </span>
      </div>
      <dl className="divide-y divide-border-subtle rounded-lg border border-border-subtle text-sm">
        {attemptTimeline.map(([label, field]) => {
          const value = attempt[field];
          return (
            <div
              key={field}
              className="flex flex-wrap items-center justify-between gap-2 px-4 py-3"
            >
              <dt className="text-content-muted">{ui(label)}</dt>
              <dd className="text-content-primary">
                {value ? (
                  <HistoryTime value={value} />
                ) : (
                  <span className="text-content-muted">
                    {finished ? ui("Not recorded") : ui("Not yet")}
                  </span>
                )}
              </dd>
            </div>
          );
        })}
      </dl>
      {attempt.errorCode ? (
        // The attempt's recorded failure, read on demand, so it is not announced.
        <Alert variant="destructive" role="note">
          <AlertTitle>{ui("Error message")}</AlertTitle>
          <AlertDescription className="break-words">
            {ui(sourceStatusMessage(attempt.errorCode))}
          </AlertDescription>
        </Alert>
      ) : null}
      {attempt.status === "FAILED" ? (
        <p className="text-sm text-content-secondary">
          {ui("To index this file again, use Reindex in the Files tab.")}
        </p>
      ) : null}
      <dl className="flex flex-col gap-2 text-xs text-content-secondary">
        {attempt.errorCode ? (
          <div>
            <dt>{ui("Error code")}</dt>
            <dd className="mt-1 select-text wrap-anywhere">
              <code>{attempt.errorCode}</code>
            </dd>
          </div>
        ) : null}
        <div>
          <dt>{ui("Attempt ID")}</dt>
          <dd className="mt-1 select-text wrap-anywhere">
            <code>{attempt.id}</code>
          </dd>
        </div>
      </dl>
    </div>
  );
}
