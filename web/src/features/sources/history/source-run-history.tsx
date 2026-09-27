import { formatUiDate, uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  columnVisibilityFeature,
  createColumnHelper,
  rowPaginationFeature,
  tableFeatures,
  useTable,
} from "@tanstack/react-table";
import { useRef, useState } from "react";
import { ChevronRight, History, RefreshCw } from "lucide-react";
import { DataTable, type DataTableColumnMeta } from "@/components/data-table/data-table";
import { EmptyState } from "@/components/composites/empty-state";
import { Button } from "@/components/ui/button";
import { HelpPopover } from "@/components/ui/help-popover";
import { IconButton } from "@/components/ui/icon-button";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Sheet, SheetContent } from "@/components/ui/sheet";
import { TablePagination } from "@/components/ui/table-pagination";
import { listSourceRunsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceRun } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { useManualRefresh } from "@/lib/use-manual-refresh";
import { runIsActive } from "./source-history";
import { HistoryTime, RunOutcome } from "./source-history-presentation";
import { cursorTablePaging, useCursorPaging } from "@/components/data-table/use-cursor-paging";
import { type SourceFilterOption, SourceFilterMenu } from "./source-filter-menu";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";
import { RunDetails, RunDuration, RunTrigger } from "./source-run-details";
import { IDLE_SOURCE_POLL_MS } from "@/features/sources/shared/source-polling";

const outcomeLegend: Array<
  [label: string, tone: "success" | "danger" | "info" | "neutral", description: string]
> = [
  ["Completed", "success", "All checked files were acquired and indexed."],
  ["No changes", "success", "The run found no new or changed content to index."],
  [
    "Completed with errors",
    "danger",
    "The run finished but some files failed. Open the run for per-file errors.",
  ],
  ["Failed", "danger", "The run stopped before finishing."],
  ["Indexing pending", "info", "Acquisition finished; indexing has not completed."],
  ["Queued", "info", "The run is waiting to start."],
  ["Acquiring", "info", "The run is reading content from the provider."],
  ["Indexing", "info", "The run is publishing acquired content to the index."],
  ["Retry scheduled", "info", "The run resumes automatically after a retry delay."],
  ["Recovery pending", "info", "The run is recovering after an interruption."],
  ["Superseded", "neutral", "A newer run replaced this one before it finished."],
  ["Cancelled", "neutral", "The run was cancelled before it finished."],
  ["Unknown", "neutral", "The outcome was not recorded."],
];

/** Run statuses a reader filters by, labelled and coloured like the outcome legend. */
const runStatusFilter: {
  label: string;
  addLabel: string;
  clearLabel: string;
  options: readonly SourceFilterOption[];
} = {
  label: "Status",
  addLabel: "Filter status",
  clearLabel: "Clear status filter",
  options: [
    { value: "SUCCEEDED", label: "Completed", tone: "success" },
    { value: "COMPLETED_WITH_ERRORS", label: "Completed with errors", tone: "danger" },
    { value: "FAILED", label: "Failed", tone: "danger" },
    { value: "QUEUED", label: "Queued", tone: "info" },
    { value: "ACQUIRING", label: "Acquiring", tone: "info" },
    { value: "INDEXING", label: "Indexing", tone: "info" },
    { value: "RETRY_SCHEDULED", label: "Retry scheduled", tone: "info" },
    { value: "RECOVERY_PENDING", label: "Recovery pending", tone: "info" },
    { value: "SUPERSEDED", label: "Superseded", tone: "neutral" },
    { value: "CANCELLED", label: "Cancelled", tone: "neutral" },
  ],
};

const features = tableFeatures({
  rowPaginationFeature,
  columnVisibilityFeature,
  columnMeta: {} as DataTableColumnMeta,
  tableMeta: {} as { viewDetails: (run: SourceRun, opener: HTMLElement) => void },
});
const column = createColumnHelper<typeof features, SourceRun>();

const startedColumn = column.display({
  id: "started",
  header: function StartedHeader() {
    const ui = useAppTranslation();
    return ui("Started");
  },
  meta: { width: "w-44" },
  cell: ({ row }) => (
    <span className="whitespace-nowrap">
      <span className="block font-medium text-content-primary">
        <HistoryTime value={row.original.startedAt} relative />
      </span>
      {row.original.startedAt ? (
        <span className="block text-xs text-content-muted">
          {formatUiDate(row.original.startedAt)}
        </span>
      ) : null}
    </span>
  ),
});
const statusColumn = column.display({
  id: "status",
  header: function StatusHeader() {
    const ui = useAppTranslation();
    return ui("Status");
  },
  meta: { width: "w-52" },
  cell: ({ row }) => <RunOutcome run={row.original} />,
});
const kindColumn = column.display({
  id: "kind",
  header: function KindHeader() {
    const ui = useAppTranslation();
    return ui("Kind");
  },
  meta: { width: "w-28" },
  cell: function KindCell({ row }) {
    const ui = useAppTranslation();
    return row.original.runKind ? (
      <StatusBadge tone="neutral">
        {row.original.runKind === "PRUNE" ? ui("Prune") : ui("Refresh")}
      </StatusBadge>
    ) : (
      <span className="text-content-muted">—</span>
    );
  },
});
/* Columns live at module scope, because a cell renderer is a component and a rebuilt list remounts every cell. */
const columns = column.columns([
  startedColumn,
  statusColumn,
  kindColumn,
  column.display({
    id: "trigger",
    header: function TriggerHeader() {
      const ui = useAppTranslation();
      return ui("Trigger");
    },
    meta: { width: "w-36" },
    cell: ({ row }) => (
      <span className="whitespace-nowrap text-content-secondary">
        <RunTrigger run={row.original} />
      </span>
    ),
  }),
  column.display({
    id: "duration",
    header: function DurationHeader() {
      const ui = useAppTranslation();
      return ui("Duration");
    },
    meta: { width: "w-28" },
    cell: ({ row }) => (
      <span className="whitespace-nowrap text-content-muted">
        <RunDuration run={row.original} />
      </span>
    ),
  }),
  column.display({
    id: "activity",
    header: function ActivityHeader() {
      const ui = useAppTranslation();
      return ui("Activity");
    },
    cell: ({ row }) => <RunActivity run={row.original} />,
  }),
  column.display({
    id: "details",
    header: function DetailsHeader() {
      const ui = useAppTranslation();
      return <span className="sr-only">{ui("Run details")}</span>;
    },
    meta: { width: "w-16", align: "end" },
    cell: function DetailsCell({ row, table }) {
      const ui = useAppTranslation();
      const run = row.original;
      return (
        <IconButton
          size="sm"
          data-run-details
          aria-label={ui("View details for run started {{v1}}", {
            v1: run.startedAt
              ? new Date(run.startedAt).toLocaleString(uiLocale())
              : ui("at an unknown time"),
          })}
          onClick={(event) => table.options.meta?.viewDetails(run, event.currentTarget)}
        >
          <ChevronRight aria-hidden="true" />
        </IconButton>
      );
    },
  }),
]);

export function SourceRunHistory({
  sourceId,
  kinds = false,
}: {
  sourceId: string;
  /** SharePoint alone runs refreshes and prunes; one kind of run needs no column. */
  kinds?: boolean;
}) {
  const ui = useAppTranslation();
  const [size, setSize] = useState(5);
  const [statuses, setStatuses] = useState<SourceRun["status"][]>([]);
  const paging = useCursorPaging();
  const [detailRun, setDetailRun] = useState<SourceRun | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const detailOpener = useRef<HTMLElement | null>(null);
  const history = useQuery({
    ...listSourceRunsOptions({
      path: { sourceId },
      query: { size, cursor: paging.cursor, status: statuses.length ? statuses : undefined },
    }),
    retry: false,
    staleTime: 0,
    placeholderData: keepPreviousData,
    // Only a run still in progress changes; settled history is read again on Refresh.
    refetchInterval: (query) =>
      query.state.data?.current || query.state.data?.items.some(runIsActive)
        ? 5_000
        : IDLE_SOURCE_POLL_MS,
  });
  // Active runs are polled, so the refresh control follows the press, not the poll.
  const runsRefresh = useManualRefresh(history.refetch);
  const totalPages = history.data ? Math.ceil(history.data.totalItems / size) : undefined;
  paging.clamp(totalPages);
  const viewDetails = (run: SourceRun, opener: HTMLElement) => {
    detailOpener.current = opener;
    setDetailRun(run);
    setDetailOpen(true);
  };
  const cursorPaging = cursorTablePaging(paging, size, history.data?.nextCursor, totalPages);
  const table = useTable({
    features,
    columns,
    data: history.data?.items ?? [],
    getRowId: (run) => run.id,
    meta: { viewDetails },
    ...cursorPaging,
    // SharePoint alone runs refreshes and prunes; one kind of run needs no column.
    state: { ...cursorPaging.state, columnVisibility: { kind: kinds } },
  });
  const latestRun = history.data?.current ?? history.data?.lastCompleted;
  const overview = (
    [
      [history.data?.current ? "Current run" : "Latest run", latestRun],
      ["Last successful run", history.data?.lastSuccessful],
    ] as const
  ).flatMap(([label, run]) => (run ? [[label, run] as const] : []));

  return (
    <section aria-label={ui("Sync history")} className="flex min-w-0 flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-3 font-heading-h3 text-content-primary">
            <SourceSectionIcon icon={History} />
            {ui("Sync history")}
            <HelpPopover label={ui("Run statuses")}>
              <dl className="flex flex-col gap-2">
                {outcomeLegend.map(([label, tone, description]) => (
                  <div key={label} className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <dt>
                      <StatusBadge tone={tone} variant="pill">
                        {ui(label)}
                      </StatusBadge>
                    </dt>
                    <dd className="min-w-0 flex-1 text-content-muted">{ui(description)}</dd>
                  </div>
                ))}
              </dl>
            </HelpPopover>
          </h2>
          <p className="mt-2 text-sm text-content-secondary">
            {ui(
              "Review what each run changed and open its details for errors that need attention.",
            )}
          </p>
        </div>
        <Button
          size="sm"
          prominence="secondary"
          pending={runsRefresh.pending}
          onClick={runsRefresh.refresh}
        >
          <RefreshCw data-icon="inline-start" aria-hidden="true" />
          {ui("Refresh")}
        </Button>
      </div>
      {history.isError ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui(
            "Source attempts could not be refreshed. Displayed outcomes may be out of date. Retry with Refresh.",
          )}
        </p>
      ) : history.isPending ? (
        <p role="status" className="text-sm text-content-muted">
          {ui("Loading source attempts…")}
        </p>
      ) : null}
      {history.data ? (
        <>
          {overview.length ? (
            <div className="grid overflow-hidden rounded-xl border border-border-subtle bg-surface-raised sm:auto-cols-fr sm:grid-flow-col">
              {overview.map(([label, run], index) => (
                <div
                  key={label}
                  className={cn(
                    "flex min-w-0 items-center gap-3 px-4 py-3",
                    index > 0 && "border-t border-border-subtle sm:border-t-0 sm:border-l",
                  )}
                >
                  <dl className="min-w-0 flex-1">
                    <dt className="text-xs text-content-muted">{ui(label)}</dt>
                    <dd className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm font-medium text-content-primary">
                      <HistoryTime value={run.startedAt} relative />
                      <RunOutcome run={run} />
                    </dd>
                  </dl>
                  <Button
                    size="sm"
                    prominence="tertiary"
                    onClick={(event) => viewDetails(run, event.currentTarget)}
                  >
                    {ui("View details")}
                    <span className="sr-only"> — {ui(label)}</span>
                  </Button>
                </div>
              ))}
            </div>
          ) : null}
          <div className="flex flex-wrap items-center gap-2">
            <SourceFilterMenu
              {...runStatusFilter}
              value={statuses}
              onValueChange={(next) => {
                setStatuses(next as SourceRun["status"][]);
                paging.reset();
              }}
            />
          </div>
          <DataTable
            table={table}
            label={ui("Source indexing attempts, newest first")}
            className="min-w-3xl"
            empty={<EmptyState title={ui("No Source executions on this page.")} />}
            rowProps={(row) => ({
              selected: detailOpen && detailRun?.id === row.id,
              // The row is a larger pointer target for its details button, which stays the
              // keyboard and assistive-technology control.
              onClick: (event) => {
                if ((event.target as Element).closest("button, a")) return;
                event.currentTarget.querySelector<HTMLElement>("[data-run-details]")?.click();
              },
            })}
            footer={
              <TablePagination
                label={ui("Source attempt pages")}
                page={paging.page}
                totalPages={totalPages}
                previousLabel={ui("Previous source attempts")}
                nextLabel={ui("Next source attempts")}
                previousDisabled={!table.getCanPreviousPage() || history.isPlaceholderData}
                nextDisabled={
                  !history.data.nextCursor || history.isPlaceholderData || history.isError
                }
                onPrevious={() => table.previousPage()}
                onNext={() => table.nextPage()}
              >
                <PageSizeSelect
                  label={ui("Source attempts per page")}
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
        </>
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
          {detailRun ? <RunDetails key={detailRun.id} initialRun={detailRun} /> : null}
        </SheetContent>
      </Sheet>
    </section>
  );
}

/** What a run changed at a glance: files checked, then only the changes and failures it made. */
function RunActivity({ run }: { run: SourceRun }) {
  const ui = useAppTranslation();
  const number = (value: number) => value.toLocaleString(uiLocale());
  const { scanned, published, removed, indexingPending } = run.counts;
  const failed = (run.counts.acquisitionFailed ?? 0) + (run.counts.indexingFailed ?? 0);
  const parts: Array<{ key: string; text: string; tone: string }> = [];
  if (scanned !== null)
    parts.push({
      key: "scanned",
      text: ui("{{v1}} checked", { v1: number(scanned) }),
      tone: "text-content-secondary",
    });
  if (published)
    parts.push({
      key: "published",
      text: ui("+{{v1}} indexed", { v1: number(published) }),
      tone: "text-status-success-content",
    });
  if (removed)
    parts.push({
      key: "removed",
      text: ui("−{{v1}} removed", { v1: number(removed) }),
      tone: "text-content-secondary",
    });
  if (indexingPending)
    parts.push({
      key: "pending",
      text: ui("{{v1}} pending", { v1: number(indexingPending) }),
      tone: "text-status-info-content",
    });
  if (failed > 0)
    parts.push({
      key: "failed",
      text: ui("{{v1}} failed", { v1: number(failed) }),
      tone: "text-status-danger-content",
    });
  if (!parts.length) {
    return (
      <span className="text-xs text-content-muted">
        {runIsActive(run)
          ? ui("Counters appear as the run progresses.")
          : ui("No recorded activity.")}
      </span>
    );
  }
  return (
    <span className="flex flex-wrap gap-x-3 gap-y-1 text-xs tabular-nums">
      {parts.map((part) => (
        <span key={part.key} className={part.tone}>
          {part.text}
        </span>
      ))}
    </span>
  );
}
