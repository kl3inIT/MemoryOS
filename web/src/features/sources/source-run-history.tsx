import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { formatUiDate, uiLocale } from "@/i18n/format";
import { useAppTranslation, type AppTranslate } from "@/i18n/use-app-translation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { type ReactNode, useRef, useState } from "react";
import {
  CalendarCheck,
  CalendarClock,
  ChevronRight,
  CircleAlert,
  CircleCheck,
  CircleHelp,
  CircleMinus,
  CircleSlash,
  CircleX,
  Clock3,
  Hand,
  Hash,
  History,
  LoaderCircle,
  RefreshCw,
  RotateCw,
  Sparkles,
  Timer,
  Zap,
  type LucideIcon,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { HelpPopover } from "@/components/ui/help-popover";
import { IconButton } from "@/components/ui/icon-button";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import { StatusBadge, type StatusTone } from "@/components/ui/status-badge";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { TablePagination } from "@/components/ui/table-pagination";
import {
  getSourceRunOptions,
  listSourceRunErrorsOptions,
  listSourceRunsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceRun, SourceRunCounts, SourceRunError } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { sourceStatusMessage } from "./source-errors";
import { historyDuration, runIsActive } from "./source-history";
import { HistoryTime, RunOutcome } from "./source-history-presentation";
import { statusPill } from "./source-status-presentation";
import { ExpandableRow } from "./expandable-row";
import { type SourceFilterOption, SourceFilterMenu } from "./source-filter-menu";
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
const failureCounts = new Set<keyof SourceRunCounts>(["acquisitionFailed", "indexingFailed"]);

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
  clearLabel: string;
  options: readonly SourceFilterOption[];
} = {
  label: "Status",
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

const runTriggers: Record<NonNullable<SourceRun["trigger"]>, [label: string, icon: LucideIcon]> = {
  SCHEDULED: ["Automatic schedule", CalendarClock],
  MANUAL: ["Manual", Hand],
  INITIAL: ["Initial synchronization", Sparkles],
};

type StageState = [label: string, tone: StatusTone, icon: LucideIcon];
const unknownStage: StageState = ["Unknown", "neutral", CircleHelp];
/** Acquisition and indexing phases share these states; indexing adds Not required. */
const stageStates: Record<string, StageState> = {
  QUEUED: ["Queued", "neutral", Clock3],
  PENDING: ["Pending", "neutral", Clock3],
  ACQUIRING: ["In progress", "info", LoaderCircle],
  INDEXING: ["In progress", "info", LoaderCircle],
  RETRY_SCHEDULED: ["Retry scheduled", "info", RotateCw],
  RECOVERY_PENDING: ["Recovery pending", "info", RotateCw],
  SUCCEEDED: ["Completed", "success", CircleCheck],
  COMPLETED_WITH_ERRORS: ["Completed with errors", "danger", CircleAlert],
  FAILED: ["Failed", "danger", CircleX],
  SUPERSEDED: ["Superseded", "neutral", CircleSlash],
  CANCELLED: ["Cancelled", "neutral", CircleSlash],
  NOT_REQUIRED: ["Not required", "neutral", CircleMinus],
  UNKNOWN: unknownStage,
};
const toneText: Record<StatusTone, string> = {
  success: "text-status-success-content",
  warning: "text-status-warning-content",
  danger: "text-status-danger-content",
  info: "text-status-info-content",
  neutral: "text-content-muted",
};

export function SourceRunHistory({ sourceId }: { sourceId: string }) {
  const ui = useAppTranslation();
  const [size, setSize] = useState(5);
  const [statuses, setStatuses] = useState<SourceRun["status"][]>([]);
  const [cursor, setCursor] = useState<string>();
  const [previous, setPrevious] = useState<Array<string | undefined>>([]);
  const [detailRun, setDetailRun] = useState<SourceRun | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const detailOpener = useRef<HTMLElement | null>(null);
  const history = useQuery({
    ...listSourceRunsOptions({
      path: { sourceId },
      query: { size, cursor, status: statuses.length ? statuses : undefined },
    }),
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
  const firstPage = () => {
    setCursor(undefined);
    setPrevious([]);
  };
  const viewDetails = (run: SourceRun, opener: HTMLElement) => {
    detailOpener.current = opener;
    setDetailRun(run);
    setDetailOpen(true);
  };
  const latestRun = history.data?.current ?? history.data?.lastCompleted;
  const overview = (
    [
      [history.data?.current ? "Current run" : "Latest run", latestRun],
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
                      <StatusBadge tone={tone} className={statusPill(tone)}>
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
          {overview.length ? (
            <dl className="grid overflow-hidden rounded-xl border border-border-subtle bg-surface-raised sm:auto-cols-fr sm:grid-flow-col">
              {overview.map(([label, run], index) => (
                <div
                  key={label}
                  className={cn(
                    "grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 px-4 py-3",
                    index > 0 && "border-t border-border-subtle sm:border-t-0 sm:border-l",
                  )}
                >
                  <dt className="text-xs text-content-muted">{ui(label)}</dt>
                  <dd className="col-start-1 mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm font-medium text-content-primary">
                    <HistoryTime value={run.startedAt} relative />
                    <RunOutcome run={run} />
                  </dd>
                  <dd className="col-start-2 row-span-2 row-start-1">
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
          <div className="overflow-hidden rounded-xl border border-border-subtle">
            <div className="flex flex-wrap items-center gap-2 border-b border-border-subtle bg-surface-raised px-3 py-2">
              <SourceFilterMenu
                {...runStatusFilter}
                value={statuses}
                onValueChange={(next) => {
                  setStatuses(next as SourceRun["status"][]);
                  firstPage();
                }}
              />
            </div>
            <div
              role="region"
              aria-label={ui("Sync history")}
              tabIndex={0}
              className="overflow-x-auto outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-focus-ring"
            >
              <Table
                aria-label={ui("Source indexing attempts, newest first")}
                className="min-w-[48rem] text-left text-sm"
              >
                <TableHeader className="bg-surface-sunken text-content-muted">
                  <TableRow>
                    <TableHead scope="col" className="px-4 font-medium">
                      {ui("Started")}
                    </TableHead>
                    <TableHead scope="col" className="px-4 font-medium">
                      {ui("Status")}
                    </TableHead>
                    <TableHead scope="col" className="px-4 font-medium">
                      {ui("Trigger")}
                    </TableHead>
                    <TableHead scope="col" className="px-4 font-medium">
                      {ui("Duration")}
                    </TableHead>
                    <TableHead scope="col" className="px-4 font-medium">
                      {ui("Activity")}
                    </TableHead>
                    <TableHead scope="col" className="w-12 px-2">
                      <span className="sr-only">{ui("Run details")}</span>
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {history.data.items.map((run) => (
                    <TableRow
                      key={run.id}
                      data-state={detailOpen && detailRun?.id === run.id ? "selected" : undefined}
                      className="cursor-pointer"
                      // The row is a larger pointer target for its details button, which stays
                      // the keyboard and assistive-technology control.
                      onClick={(event) => {
                        if ((event.target as Element).closest("button, a")) return;
                        event.currentTarget
                          .querySelector<HTMLButtonElement>("[data-run-details]")
                          ?.click();
                      }}
                    >
                      <TableCell className="whitespace-nowrap px-4 py-3">
                        <span className="block font-medium text-content-primary">
                          <HistoryTime value={run.startedAt} relative />
                        </span>
                        {run.startedAt ? (
                          <span className="block text-xs text-content-muted">
                            {formatUiDate(run.startedAt)}
                          </span>
                        ) : null}
                      </TableCell>
                      <TableCell className="px-4 py-3">
                        <RunOutcome run={run} />
                      </TableCell>
                      <TableCell className="whitespace-nowrap px-4 py-3 text-content-secondary">
                        <RunTrigger run={run} />
                      </TableCell>
                      <TableCell className="whitespace-nowrap px-4 py-3 text-content-muted">
                        <RunDuration run={run} />
                      </TableCell>
                      <TableCell className="px-4 py-3 whitespace-normal">
                        <RunActivity run={run} />
                      </TableCell>
                      <TableCell className="px-2 py-3 text-right">
                        <IconButton
                          size="sm"
                          data-run-details
                          aria-label={ui("View details for run started {{v1}}", {
                            v1: run.startedAt
                              ? new Date(run.startedAt).toLocaleString(uiLocale())
                              : ui("at an unknown time"),
                          })}
                          onClick={(event) => viewDetails(run, event.currentTarget)}
                        >
                          <ChevronRight aria-hidden="true" />
                        </IconButton>
                      </TableCell>
                    </TableRow>
                  ))}
                  {!history.data.items.length ? (
                    <TableRow>
                      <TableCell colSpan={6} className="px-4 py-8 text-center text-content-muted">
                        {ui("No Source executions on this page.")}
                      </TableCell>
                    </TableRow>
                  ) : null}
                </TableBody>
              </Table>
            </div>
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
                  firstPage();
                }}
              />
            </TablePagination>
          </div>
        </>
      ) : null}
      <Sheet open={detailOpen} onOpenChange={setDetailOpen}>
        <SheetContent
          className="w-full gap-0 overflow-y-auto sm:max-w-xl"
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

function RunDuration({ run }: { run: SourceRun }) {
  const ui = useAppTranslation();
  const duration = historyDuration(run.startedAt, run.completedAt);
  if (duration) return <>{duration}</>;
  return <>{runIsActive(run) ? ui("In progress") : ui("Not recorded")}</>;
}

function RunTrigger({ run }: { run: SourceRun }) {
  const ui = useAppTranslation();
  const trigger = run.trigger ? runTriggers[run.trigger] : undefined;
  if (!trigger) return <span className="text-content-muted">{ui("Not recorded")}</span>;
  const [label, Icon] = trigger;
  return (
    <span className="inline-flex items-center gap-1.5">
      <Icon aria-hidden="true" className="size-4 shrink-0 text-content-muted" />
      {ui(label)}
    </span>
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

function RunSection({
  title,
  help,
  children,
}: {
  title: string;
  help?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="px-5 py-4">
      <h3 className="mb-3 flex items-center gap-1.5 text-sm font-medium text-content-primary">
        {title}
        {help}
      </h3>
      {children}
    </section>
  );
}

function DetailRow({
  icon: Icon,
  label,
  children,
}: {
  icon: LucideIcon;
  label: string;
  children: ReactNode;
}) {
  return (
    <div className="flex items-start justify-between gap-4 py-1.5">
      <dt className="flex shrink-0 items-center gap-2 text-content-muted">
        <Icon aria-hidden="true" className="size-4" />
        {label}
      </dt>
      <dd className="min-w-0 text-right text-content-primary">{children}</dd>
    </div>
  );
}

function RunStage({
  name,
  state,
  duration,
}: {
  name: string;
  state: string;
  duration: string | null;
}) {
  const ui = useAppTranslation();
  const [label, tone, Icon] = stageStates[state] ?? unknownStage;
  return (
    <li className="flex items-center gap-3 rounded-lg border border-border-subtle px-3 py-2.5">
      <Icon
        aria-hidden="true"
        className={cn(
          "size-4 shrink-0",
          toneText[tone],
          Icon === LoaderCircle && "motion-safe:animate-spin",
        )}
      />
      <span className="min-w-0 flex-1 font-medium text-content-primary">{name}</span>
      {duration ? (
        <span className="text-xs tabular-nums text-content-muted">{duration}</span>
      ) : null}
      <StatusBadge tone={tone} className={statusPill(tone)}>
        {ui(label)}
      </StatusBadge>
    </li>
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
  const counts = [
    ...primaryCounts,
    ...additionalCounts.filter(([field]) => run.counts[field] !== 0),
  ];
  return (
    <>
      <SheetHeader className="gap-1.5 border-b border-border-subtle px-5 py-4 pr-12">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <SheetTitle className="font-heading-h3 text-content-primary">
            {ui("Run details")}
          </SheetTitle>
          <RunOutcome run={run} />
        </div>
        <SheetDescription className="text-content-muted">
          {run.startedAt ? <HistoryTime value={run.startedAt} /> : ui("at an unknown time")}
        </SheetDescription>
      </SheetHeader>
      <div className="min-w-0 divide-y divide-border-subtle">
        {detail.isError ? (
          <div
            role="alert"
            className="flex flex-wrap items-center gap-2 px-5 py-3 text-sm text-status-danger-content"
          >
            {ui("This run could not be refreshed. Displayed details may be out of date.")}
            <Button size="sm" prominence="tertiary" onClick={() => void detail.refetch()}>
              {ui("Retry")}
            </Button>
          </div>
        ) : null}
        <RunSection title={ui("Overview")}>
          <dl className="text-sm">
            <DetailRow icon={Hash} label={ui("Run ID")}>
              <code className="text-xs select-text [overflow-wrap:anywhere]">{run.id}</code>
            </DetailRow>
            <DetailRow icon={Zap} label={ui("Trigger")}>
              <RunTrigger run={run} />
            </DetailRow>
            <DetailRow icon={CalendarClock} label={ui("Started")}>
              <HistoryTime value={run.startedAt} />
            </DetailRow>
            <DetailRow icon={CalendarCheck} label={ui("Finished")}>
              {run.completedAt ? (
                <HistoryTime value={run.completedAt} />
              ) : runIsActive(run) ? (
                ui("In progress")
              ) : (
                ui("Not recorded")
              )}
            </DetailRow>
            <DetailRow icon={Timer} label={ui("Duration")}>
              <RunDuration run={run} />
            </DetailRow>
            {run.nextRetryAt ? (
              <DetailRow icon={RotateCw} label={ui("Next retry")}>
                <span className="text-status-warning-content">
                  <HistoryTime value={run.nextRetryAt} />
                </span>
              </DetailRow>
            ) : null}
          </dl>
        </RunSection>
        <RunSection title={ui("Stages")}>
          <ol className="space-y-2 text-sm">
            <RunStage
              name={ui("Read content")}
              state={run.acquisitionStatus}
              duration={historyDuration(run.startedAt, run.acquisitionCompletedAt)}
            />
            <RunStage
              name={ui("Index content")}
              state={run.indexingStatus}
              duration={
                run.indexingStatus === "NOT_REQUIRED"
                  ? null
                  : historyDuration(run.acquisitionCompletedAt, run.completedAt)
              }
            />
          </ol>
        </RunSection>
        <RunSection
          title={ui("Files")}
          help={
            <HelpPopover label={ui("File counts")}>
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
            </HelpPopover>
          }
        >
          <dl className="divide-y divide-border-subtle rounded-lg border border-border-subtle px-3 text-sm">
            {counts.map(([field, label]) => {
              const value = run.counts[field];
              const failed = failureCounts.has(field) && (value ?? 0) > 0;
              return (
                <div key={field} className="flex items-center justify-between gap-4 py-2">
                  <dt className="text-content-secondary">{ui(label)}</dt>
                  <dd
                    className={cn(
                      "font-medium tabular-nums",
                      failed ? "text-status-danger-content" : "text-content-primary",
                    )}
                  >
                    {value?.toLocaleString(uiLocale()) ?? ui("Unknown")}
                  </dd>
                </div>
              );
            })}
          </dl>
        </RunSection>
        {run.errorCode ? (
          <section className="px-5 py-4">
            <div className="rounded-xl bg-status-danger-surface p-4">
              <h3 className="text-sm font-medium text-status-danger-content">
                {ui("Historical run error")}
              </h3>
              <p className="mt-2 text-sm break-words text-content-primary">
                {runErrorMessage(ui, run.errorCode)}
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
                  </dl>
                </CollapsibleContent>
              </Collapsible>
            </div>
          </section>
        ) : null}
        {run.detailsExpired ? (
          <RunSection title={ui("Error details")}>
            <p className="text-sm text-content-muted">
              {ui("Detailed errors expired; retained totals are shown.")}
            </p>
          </RunSection>
        ) : hasErrors || runIsActive(run) ? (
          <RunSection title={ui("Error details")}>
            <RunErrors key={run.id} run={run} />
          </RunSection>
        ) : null}
      </div>
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
  const tone =
    status === "INDEXED"
      ? "success"
      : status === "FAILED"
        ? "danger"
        : status === "PENDING"
          ? "info"
          : "neutral";
  return (
    <StatusBadge tone={tone} className={statusPill(tone)}>
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
