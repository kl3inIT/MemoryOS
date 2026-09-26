import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import {
  CalendarCheck,
  CalendarClock,
  CircleAlert,
  CircleCheck,
  CircleHelp,
  CircleMinus,
  CircleSlash,
  CircleX,
  Clock3,
  Hand,
  Hash,
  LoaderCircle,
  Play,
  RefreshCw,
  RotateCw,
  Sparkles,
  Timer,
  Zap,
  type LucideIcon,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { HelpPopover } from "@/components/ui/help-popover";
import { StatusBadge, type StatusTone } from "@/components/ui/status-badge";
import { SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { getSourceRunOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceRun, SourceRunCounts } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { statusPill } from "@/features/sources/shared/source-status-presentation";
import { historyDuration, runErrorMessage, runIsActive } from "./source-history";
import { RunErrors } from "./source-run-errors";
import { HistoryTime, RunOutcome } from "./source-history-presentation";

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

const runTriggers: Record<NonNullable<SourceRun["trigger"]>, [label: string, icon: LucideIcon]> = {
  SCHEDULED: ["Automatic schedule", CalendarClock],
  MANUAL: ["Manual", Hand],
  INITIAL: ["Initial synchronization", Sparkles],
  RESUMED: ["Resumed after pause", Play],
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

export function RunDuration({ run }: { run: SourceRun }) {
  const ui = useAppTranslation();
  const duration = historyDuration(run.startedAt, run.completedAt);
  if (duration) return <>{duration}</>;
  return <>{runIsActive(run) ? ui("In progress") : ui("Not recorded")}</>;
}

export function RunTrigger({ run }: { run: SourceRun }) {
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

export function RunDetails({ initialRun }: { initialRun: SourceRun }) {
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
            {run.runKind ? (
              <DetailRow icon={RefreshCw} label={ui("Kind")}>
                {run.runKind === "PRUNE" ? ui("Prune") : ui("Refresh")}
              </DetailRow>
            ) : null}
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
        ) : hasErrors ? (
          <RunSection title={ui("Error details")}>
            <RunErrors key={run.id} run={run} />
          </RunSection>
        ) : null}
      </div>
    </>
  );
}
