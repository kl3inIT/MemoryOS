import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation, type AppTranslate } from "@/i18n/use-app-translation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useLayoutEffect, useRef, useState } from "react";
import { History, RefreshCw, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { HelpPopover } from "@/components/ui/help-popover";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import { StatusBadge } from "@/components/ui/status-badge";
import { TablePagination } from "@/components/ui/table-pagination";
import {
  getSourceRunOptions,
  listSourceRunErrorsOptions,
  listSourceRunsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceRun, SourceRunCounts, SourceRunError } from "@/lib/hey-api/types.gen";
import { sourceStatusMessage } from "./source-errors";
import { historyDuration, runIsActive } from "./source-history";
import { HistoryTime, RunOutcome } from "./source-history-presentation";
import { ExpandableRow, ListDetailLayout } from "./list-detail-layout";
import { SourceSectionIcon } from "./source-section-icon";

const primaryCounts: Array<[keyof SourceRunCounts, string]> = [
  ["scanned", "Checked"],
  ["published", "Indexed"],
  ["unchanged", "Unchanged"],
];
const additionalCounts: Array<[keyof SourceRunCounts, string]> = [
  ["acquired", "Acquired"],
  ["removed", "Removed"],
  ["acquisitionFailed", "Acquisition failed"],
  ["indexingFailed", "Indexing failed"],
  ["indexingPending", "Indexing pending"],
  ["alreadyPending", "Already pending"],
  ["skipped", "Skipped"],
  ["indexingSuperseded", "Superseded"],
  ["indexingCancelled", "Cancelled"],
];
const summaryCounts: Array<[keyof SourceRunCounts, string]> = [
  ["scanned", "Checked"],
  ["published", "Indexed"],
  ["unchanged", "Unchanged"],
  ["acquisitionFailed", "Acquisition failed"],
  ["indexingFailed", "Indexing failed"],
];

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

export function SourceRunHistory({ sourceId }: { sourceId: string }) {
  const ui = useAppTranslation();
  const [size, setSize] = useState(5);
  const [cursor, setCursor] = useState<string>();
  const [previous, setPrevious] = useState<Array<string | undefined>>([]);
  const [selectedRun, setSelectedRun] = useState<SourceRun | null>(null);
  const detailsTrigger = useRef<HTMLElement | null>(null);
  const closeButton = useRef<HTMLButtonElement | null>(null);
  const history = useQuery({
    ...listSourceRunsOptions({ path: { sourceId }, query: { size, cursor } }),
    retry: false,
    staleTime: 0,
    placeholderData: keepPreviousData,
    refetchInterval: 5_000,
  });
  const totalPages = history.data ? Math.ceil(history.data.totalItems / size) : undefined;
  if (totalPages !== undefined && previous.length >= Math.max(totalPages, 1)) {
    setCursor(undefined);
    setPrevious([]);
  }
  const viewDetails = (run: SourceRun, trigger: HTMLElement) => {
    detailsTrigger.current = trigger;
    setSelectedRun(run);
  };
  const selectedRunId = selectedRun?.id;
  useLayoutEffect(() => {
    if (selectedRunId) closeButton.current?.focus();
  }, [selectedRunId]);
  const summaryRuns = (
    [
      ["Current run", history.data?.current],
      ["Last successful run", history.data?.lastSuccessful],
    ] as const
  ).flatMap(([label, run]) => (run ? [[label, run] as const] : []));

  return (
    <section aria-label={ui("Sync history")} className="min-w-0 space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-3 font-heading-h3 text-content-primary">
            <SourceSectionIcon icon={History} />
            {ui("Sync history")}
            <HelpPopover label={ui("Run statuses")}>
              <dl className="space-y-2">
                {outcomeLegend.map(([label, tone, description]) => (
                  <div key={label} className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <dt>
                      <StatusBadge tone={tone} size="sm">
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
          pending={history.isFetching}
          onClick={() => void history.refetch()}
        >
          <RefreshCw aria-hidden="true" /> {ui("Refresh")}
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
          {summaryRuns.length ? (
            <dl className={summaryRuns.length > 1 ? "grid gap-3 sm:grid-cols-2" : "grid gap-3"}>
              {summaryRuns.map(([label, run]) => (
                <div
                  key={label}
                  className="min-w-0 rounded-xl border border-border-subtle bg-surface-raised p-4"
                >
                  <dt className="text-xs text-content-muted">{ui(label)}</dt>
                  <dd className="mt-2 flex flex-wrap items-center justify-between gap-3">
                    <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-sm text-content-primary">
                      <HistoryTime value={run.startedAt} />
                      <RunOutcome run={run} />
                    </div>
                    <Button
                      size="sm"
                      prominence="tertiary"
                      onClick={(event) => viewDetails(run, event.currentTarget)}
                    >
                      {ui("View details")}
                      <span className="sr-only"> — {ui(label)}</span>
                    </Button>
                  </dd>
                </div>
              ))}
            </dl>
          ) : null}
          <ListDetailLayout
            detailLabel={ui("Run details")}
            list={
              <div className="overflow-hidden rounded-xl border border-border-subtle">
                <ul
                  aria-label={ui("Source indexing attempts, newest first")}
                  className="divide-y divide-border-subtle"
                >
                  {history.data.items.map((run) => (
                    <li key={run.id} className="min-w-0">
                      <button
                        type="button"
                        aria-current={selectedRun?.id === run.id || undefined}
                        aria-label={ui("View details for run started {{v1}}", {
                          v1: run.startedAt
                            ? new Date(run.startedAt).toLocaleString(uiLocale())
                            : ui("at an unknown time"),
                        })}
                        className={`block min-h-11 w-full min-w-0 px-4 py-2.5 text-left transition-colors focus-visible:outline-2 focus-visible:outline-focus-ring ${
                          selectedRun?.id === run.id
                            ? "bg-surface-subtle"
                            : "hover:bg-surface-subtle/40"
                        }`}
                        onClick={(event) => viewDetails(run, event.currentTarget)}
                      >
                        <span className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-content-primary">
                          <HistoryTime value={run.startedAt} />
                          <RunOutcome run={run} />
                          <span className="text-xs text-content-muted">
                            <RunDuration run={run} />
                          </span>
                          <span className="min-w-0 flex-1">
                            <RunActivity run={run} />
                          </span>
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
                {!history.data.items.length ? (
                  <p className="px-4 py-8 text-center text-sm text-content-muted">
                    {ui("No Source executions on this page.")}
                  </p>
                ) : null}
                <TablePagination
                  label={ui("Source attempt pages")}
                  page={previous.length}
                  totalPages={totalPages}
                  previousLabel={ui("Previous source attempts")}
                  nextLabel={ui("Next source attempts")}
                  previousDisabled={!previous.length || history.isFetching}
                  nextDisabled={!history.data.nextCursor || history.isFetching || history.isError}
                  onPrevious={() => {
                    setCursor(previous.at(-1));
                    setPrevious((pages) => pages.slice(0, -1));
                  }}
                  onNext={() => {
                    setPrevious((pages) => [...pages, cursor]);
                    setCursor(history.data?.nextCursor ?? undefined);
                  }}
                >
                  <PageSizeSelect
                    label={ui("Source attempts per page")}
                    rowsLabel={ui("Rows")}
                    value={size}
                    sizes={[5, 10, 25, 50]}
                    disabled={history.isFetching}
                    onSizeChange={(next) => {
                      setSize(next);
                      setCursor(undefined);
                      setPrevious([]);
                    }}
                  />
                </TablePagination>
              </div>
            }
            detail={
              selectedRun ? (
                <div className="min-w-0">
                  <div className="mb-4 flex items-start justify-between gap-3">
                    <h3 className="font-heading-h3 text-content-primary">{ui("Run details")}</h3>
                    <IconButton
                      ref={closeButton}
                      prominence="tertiary"
                      aria-label={ui("Close run details")}
                      onClick={() => {
                        setSelectedRun(null);
                        detailsTrigger.current?.focus();
                      }}
                    >
                      <X />
                    </IconButton>
                  </div>
                  <RunDetails key={selectedRun.id} initialRun={selectedRun} />
                </div>
              ) : null
            }
          />
          <Collapsible className="rounded-lg border border-border-subtle px-4 py-3 text-sm">
            <CollapsibleTrigger className="min-h-11 cursor-pointer py-2 text-content-secondary focus-visible:outline-2 focus-visible:outline-focus-ring">
              {ui("What do the different statuses mean?")}
            </CollapsibleTrigger>
            <CollapsibleContent>
              <dl className="mt-2 space-y-2 pb-1">
                {outcomeLegend.map(([label, tone, description]) => (
                  <div key={label} className="flex flex-wrap items-center gap-x-3 gap-y-1">
                    <dt>
                      <StatusBadge tone={tone} size="sm">
                        {ui(label)}
                      </StatusBadge>
                    </dt>
                    <dd className="min-w-0 flex-1 text-content-muted">{ui(description)}</dd>
                  </div>
                ))}
              </dl>
            </CollapsibleContent>
          </Collapsible>
        </>
      ) : null}
    </section>
  );
}

function RunDuration({ run }: { run: SourceRun }) {
  const ui = useAppTranslation();
  const duration = historyDuration(run.startedAt, run.completedAt);
  if (duration) return <>{duration}</>;
  return <>{runIsActive(run) ? ui("In progress") : ui("Not recorded")}</>;
}

function RunActivity({ run }: { run: SourceRun }) {
  const ui = useAppTranslation();
  const counts = summaryCounts
    .map(([field, label]) => ({ field, label, value: run.counts[field] }))
    .filter(({ value }) => value !== null && value !== 0);
  if (!counts.length) {
    return (
      <span className="text-xs text-content-muted">
        {runIsActive(run)
          ? ui("Counters appear as the run progresses.")
          : ui("No recorded activity.")}
      </span>
    );
  }
  return (
    <span className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-content-muted">
      {counts.map(({ field, label, value }) => (
        <span
          key={field}
          className={
            (field === "acquisitionFailed" || field === "indexingFailed") && (value ?? 0) > 0
              ? "text-status-danger-content"
              : undefined
          }
        >
          <span className="tabular-nums">{value?.toLocaleString(uiLocale())}</span> {ui(label)}
        </span>
      ))}
    </span>
  );
}

function RunDetails({ initialRun }: { initialRun: SourceRun }) {
  const ui = useAppTranslation();
  const detail = useQuery({
    ...getSourceRunOptions({ path: { sourceId: initialRun.sourceId, runId: initialRun.id } }),
    initialData: initialRun,
    retry: false,
    staleTime: 0,
    refetchInterval: (query) => (runIsActive(query.state.data ?? initialRun) ? 5_000 : false),
  });
  const run = detail.data;
  const hasErrors =
    Boolean(run.errorCode) ||
    run.status === "FAILED" ||
    run.status === "COMPLETED_WITH_ERRORS" ||
    run.indexingStatus === "COMPLETED_WITH_ERRORS" ||
    (run.counts.acquisitionFailed ?? 0) > 0 ||
    (run.counts.indexingFailed ?? 0) > 0;
  return (
    <>
      {detail.isError ? (
        <div role="alert" className="text-sm text-status-danger-content">
          {ui("This run could not be refreshed. Displayed details may be out of date.")}
          <Button size="sm" prominence="tertiary" onClick={() => void detail.refetch()}>
            {ui("Retry")}
          </Button>
        </div>
      ) : null}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border-subtle bg-surface-sunken px-4 py-3">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
          <RunOutcome run={run} />
          <span className="text-sm text-content-secondary">
            <HistoryTime value={run.startedAt} />
            {" → "}
            {run.completedAt ? (
              <HistoryTime value={run.completedAt} />
            ) : runIsActive(run) ? (
              ui("In progress")
            ) : (
              ui("Not recorded")
            )}
          </span>
        </div>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
          {run.nextRetryAt ? (
            <span className="text-sm text-status-warning-content">
              {ui("Next retry")}: <HistoryTime value={run.nextRetryAt} />
            </span>
          ) : null}
          <span className="text-sm font-medium tabular-nums text-content-primary">
            <RunDuration run={run} />
          </span>
        </div>
      </div>
      {run.errorCode ? (
        <section className="rounded-xl bg-status-danger-surface p-4">
          <h3 className="text-sm font-medium text-status-danger-content">
            {ui("Historical run error")}
          </h3>
          <p className="mt-2 text-sm break-words text-content-primary">
            {run.errorCode === "SOURCE_EXTRACTION_TIMEOUT"
              ? ui(
                  "Extraction timed out during this run. The retained error does not identify the underlying cause.",
                )
              : ui(sourceStatusMessage(run.errorCode))}
          </p>
          <Collapsible className="mt-2 text-xs text-content-secondary">
            <CollapsibleTrigger className="min-h-11 cursor-pointer py-3 focus-visible:outline-2 focus-visible:outline-focus-ring">
              {ui("Technical details")}
            </CollapsibleTrigger>
            <CollapsibleContent>
              <dl className="space-y-2">
                <div>
                  <dt>{ui("Error code")}</dt>
                  <dd className="mt-1 select-text [overflow-wrap:anywhere]">
                    <code>{run.errorCode}</code>
                  </dd>
                </div>
                <div>
                  <dt>{ui("Run ID")}</dt>
                  <dd className="mt-1 select-text [overflow-wrap:anywhere]">
                    <code>{run.id}</code>
                  </dd>
                </div>
              </dl>
            </CollapsibleContent>
          </Collapsible>
        </section>
      ) : null}
      <section className="space-y-3">
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {(
            [
              ...primaryCounts,
              ["indexingFailed" as keyof SourceRunCounts, "Indexing failed"] as [
                keyof SourceRunCounts,
                string,
              ],
            ] as Array<[keyof SourceRunCounts, string]>
          ).map(([field, label]) => {
            const value = run.counts[field];
            const failed = field === "indexingFailed" && (value ?? 0) > 0;
            return (
              <div
                key={field}
                className={
                  failed
                    ? "rounded-lg bg-status-danger-surface p-3"
                    : "rounded-lg bg-surface-base p-3"
                }
              >
                <dt className="text-xs text-content-muted">{ui(label)}</dt>
                <dd
                  className={
                    failed
                      ? "mt-1 text-lg font-medium tabular-nums text-status-danger-content"
                      : "mt-1 text-lg font-medium tabular-nums text-content-primary"
                  }
                >
                  {value?.toLocaleString(uiLocale()) ?? ui("Unknown")}
                </dd>
              </div>
            );
          })}
        </dl>
        <Collapsible className="text-xs text-content-muted">
          <CollapsibleTrigger className="min-h-11 cursor-pointer py-3 focus-visible:outline-2 focus-visible:outline-focus-ring">
            {ui("More counts and definitions")}
          </CollapsibleTrigger>
          <CollapsibleContent>
            <dl className="mb-3 grid grid-cols-2 gap-3 sm:grid-cols-3">
              {additionalCounts
                .filter(([field]) => run.counts[field] !== 0)
                .map(([field, label]) => (
                  <div key={field}>
                    <dt>{ui(label)}</dt>
                    <dd className="mt-1 tabular-nums text-content-primary">
                      {run.counts[field]?.toLocaleString(uiLocale()) ?? ui("Unknown")}
                    </dd>
                  </div>
                ))}
            </dl>
            <p className="leading-relaxed">
              {ui(
                "Checked counts distinct files observed, Indexed counts successful publications (new or replaced), and Unchanged counts files needing no new indexing. Counts can overlap and are not a corpus total. Unknown means not recorded.",
              )}
            </p>
            {run.counts.alreadyPending !== 0 ? (
              <p className="mt-2 leading-relaxed">
                {ui("Already pending belongs to earlier work, not indexing owned by this run.")}
              </p>
            ) : null}
          </CollapsibleContent>
        </Collapsible>
      </section>
      {run.detailsExpired ? (
        <section className="space-y-3">
          <h3 className="text-sm font-medium text-content-primary">{ui("Error details")}</h3>
          <p className="text-sm text-content-muted">
            {ui("Detailed errors expired; retained totals are shown.")}
          </p>
        </section>
      ) : hasErrors || runIsActive(run) ? (
        <section className="space-y-3">
          <h3 className="text-sm font-medium text-content-primary">{ui("Error details")}</h3>
          <RunErrors key={run.id} run={run} />
        </section>
      ) : null}
    </>
  );
}

const runErrorStages = {
  PROVIDER: "Provider",
  STORAGE_READ: "Reading storage",
  STORAGE_WRITE: "Writing storage",
  EXTRACTION: "Extraction",
  PUBLICATION: "Publication",
  SYSTEM: "System",
} as const;

function runErrorMessage(ui: AppTranslate, code: string) {
  return code === "SOURCE_EXTRACTION_TIMEOUT"
    ? ui(
        "Extraction timed out during this run. The retained error does not identify the underlying cause.",
      )
    : ui(sourceStatusMessage(code));
}

function RunErrors({ run }: { run: SourceRun }) {
  const ui = useAppTranslation();
  const [cursor, setCursor] = useState<string>();
  const [previous, setPrevious] = useState<Array<string | undefined>>([]);
  const errors = useQuery({
    ...listSourceRunErrorsOptions({
      path: { sourceId: run.sourceId, runId: run.id },
      query: { size: 5, cursor },
    }),
    retry: false,
    staleTime: 0,
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      runIsActive(run) ||
      query.state.data?.items.some(
        (error) => error.currentItemStatus === "PENDING" || error.currentItemStatus === "DELETING",
      )
        ? 5_000
        : false,
  });
  return (
    <div className="space-y-3 text-sm text-content-secondary" aria-busy={errors.isFetching}>
      <div className="flex justify-end">
        <Button
          size="sm"
          prominence="tertiary"
          pending={errors.isFetching}
          onClick={() => void errors.refetch()}
        >
          <RefreshCw aria-hidden="true" /> {ui("Refresh file states")}
        </Button>
      </div>
      {errors.isError ? (
        <p role="alert" className="text-status-danger-content">
          {ui(
            "Error details and current file states could not be refreshed. Displayed states may be out of date.",
          )}{" "}
          <Button size="sm" prominence="tertiary" onClick={() => void errors.refetch()}>
            {ui("Retry")}
          </Button>
        </p>
      ) : errors.isPending ? (
        <p role="status">{ui("Loading errors…")}</p>
      ) : null}
      {errors.data ? (
        <>
          {errors.data.items.length ? (
            <ul
              aria-label={ui("Run errors")}
              className="divide-y divide-border-subtle rounded-lg border border-border-subtle"
            >
              {errors.data.items.map((error) => (
                <li key={error.id} className="min-w-0">
                  <RunErrorRow error={error} />
                </li>
              ))}
            </ul>
          ) : (
            <p>{ui("No retained error details on this page.")}</p>
          )}
          {previous.length || errors.data.nextCursor ? (
            <TablePagination
              label={ui("Run error pages")}
              page={previous.length}
              totalPages={undefined}
              previousLabel={ui("Previous errors")}
              nextLabel={ui("Next errors")}
              previousDisabled={!previous.length || errors.isFetching}
              nextDisabled={!errors.data.nextCursor || errors.isFetching || errors.isError}
              onPrevious={() => {
                setCursor(previous.at(-1));
                setPrevious((pages) => pages.slice(0, -1));
              }}
              onNext={() => {
                setPrevious((pages) => [...pages, cursor]);
                setCursor(errors.data?.nextCursor ?? undefined);
              }}
            />
          ) : null}
        </>
      ) : null}
    </div>
  );
}

function RunErrorRow({ error }: { error: SourceRunError }) {
  const ui = useAppTranslation();
  const name = error.fileName ?? error.fileId ?? ui("Source execution");
  return (
    <ExpandableRow
      label={ui("Error details for {{v1}}", { v1: name })}
      summary={
        <span className="min-w-0">
          <span className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
            <span className="min-w-0 break-words font-medium whitespace-pre-wrap [overflow-wrap:anywhere] text-content-primary">
              {name}
            </span>
            <CurrentFileStateBadge error={error} />
          </span>
          <span className="mt-0.5 block text-xs text-content-muted">
            {ui(runErrorStages[error.stage])}
            {" · "}
            <HistoryTime value={error.occurredAt} />
          </span>
          <span className="mt-1 block text-sm leading-relaxed [overflow-wrap:anywhere] text-content-primary">
            {error.errorMessage ?? runErrorMessage(ui, error.code)}
          </span>
        </span>
      }
    >
      <dl className="grid gap-3 text-xs sm:grid-cols-2">
        <div>
          <dt className="text-content-muted">{ui("Stage")}</dt>
          <dd className="mt-1">{ui(runErrorStages[error.stage])}</dd>
        </div>
        <div>
          <dt className="text-content-muted">{ui("Occurred at")}</dt>
          <dd className="mt-1">
            <HistoryTime value={error.occurredAt} />
          </dd>
        </div>
        <div>
          <dt className="text-content-muted">{ui("Error code")}</dt>
          <dd className="mt-1 select-text [overflow-wrap:anywhere]">
            <code>{error.code}</code>
          </dd>
        </div>
        <div>
          <dt className="text-content-muted">{ui("Operation ID")}</dt>
          <dd className="mt-1 select-text [overflow-wrap:anywhere]">
            <code>{error.operationId ?? ui("Not recorded")}</code>
          </dd>
        </div>
        <div>
          <dt className="text-content-muted">{ui("Run ID")}</dt>
          <dd className="mt-1 select-text [overflow-wrap:anywhere]">
            <code>{error.runId}</code>
          </dd>
        </div>
        {error.fileId ? (
          <div>
            <dt className="text-content-muted">{ui("File ID")}</dt>
            <dd className="mt-1 select-text [overflow-wrap:anywhere]">
              <code>{error.fileId}</code>
            </dd>
          </div>
        ) : null}
      </dl>
      {error.errorDetail ? (
        <div className="mt-3">
          <p className="text-xs text-content-muted">{ui("Technical details")}</p>
          <pre className="mt-1 max-h-64 overflow-auto rounded-md bg-surface-sunken p-3 text-xs whitespace-pre-wrap [overflow-wrap:anywhere] text-content-secondary select-text">
            {error.errorDetail}
          </pre>
        </div>
      ) : null}
      <div className="mt-3">
        <p className="text-xs text-content-muted">{ui("Current state")}</p>
        <div className="mt-1">
          {error.itemId || error.fileId || error.fileName ? (
            <CurrentFileState error={error} />
          ) : (
            <span className="text-content-muted">{ui("Not recorded")}</span>
          )}
        </div>
      </div>
    </ExpandableRow>
  );
}

function CurrentFileStateBadge({ error }: { error: SourceRunError }) {
  const ui = useAppTranslation();
  const status = error.currentItemStatus;
  return (
    <StatusBadge
      tone={
        status === "INDEXED"
          ? "success"
          : status === "FAILED"
            ? "danger"
            : status === "PENDING"
              ? "info"
              : "neutral"
      }
    >
      {ui(
        status === "INDEXED"
          ? "Indexed"
          : status === "FAILED"
            ? "Failed"
            : status === "PENDING"
              ? "Pending"
              : status === "DELETING"
                ? "Deleting"
                : "Unknown",
      )}
    </StatusBadge>
  );
}

function CurrentFileState({ error }: { error: SourceRunError }) {
  const ui = useAppTranslation();
  const status = error.currentItemStatus;
  const indexedSinceError =
    status === "INDEXED" &&
    error.currentItemLastIndexedAt !== null &&
    new Date(error.currentItemLastIndexedAt).getTime() > new Date(error.occurredAt).getTime();
  return (
    <div className="space-y-1">
      <CurrentFileStateBadge error={error} />
      <p className="text-xs text-content-muted">
        {status === "INDEXED"
          ? indexedSinceError
            ? ui("Content indexed since this error.")
            : ui("Current content is indexed.")
          : status === "PENDING"
            ? ui("Indexing is pending or in progress.")
            : status === "DELETING"
              ? ui("Removal is in progress.")
              : status === "FAILED"
                ? error.currentItemErrorCode && error.currentItemErrorCode !== error.code
                  ? ui(sourceStatusMessage(error.currentItemErrorCode))
                  : ui("This file still needs attention.")
                : error.itemId
                  ? ui("Current file state is unavailable; the file may have been removed.")
                  : ui("No current file is linked to this error.")}
        {status === "INDEXED" && error.currentItemLastIndexedAt ? (
          <>
            {" "}
            {ui("Last indexed")} <HistoryTime value={error.currentItemLastIndexedAt} />
          </>
        ) : null}
      </p>
    </div>
  );
}
