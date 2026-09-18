import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { SourceItem, SourceRun } from "@/lib/hey-api/types.gen";
import { StatusBadge } from "@/components/ui/status-badge";
import { CircleCheck, CircleHelp, CircleX, Clock3, LoaderCircle, Trash2 } from "lucide-react";
import { historyRelativeTime, runHasNoChanges, runIsActive } from "./source-history";
import { SourceHint } from "./source-hint";
import { statusPill } from "./source-status-presentation";

/** A local time; `relative` words times within the last week as "6 minutes ago". */
export function HistoryTime({
  value,
  relative = false,
}: {
  value: string | null;
  relative?: boolean;
}) {
  const ui = useAppTranslation();

  if (!value) return <span>{ui("Unknown")}</span>;
  const date = new Date(value);
  const full = date.toLocaleString(uiLocale(), { dateStyle: "full", timeStyle: "long" });
  return (
    <SourceHint hint={full}>
      <time dateTime={value} aria-label={full}>
        {(relative && historyRelativeTime(value)) || date.toLocaleString(uiLocale())}
      </time>
    </SourceHint>
  );
}

export function RunOutcome({ run }: { run: SourceRun }) {
  const ui = useAppTranslation();
  const failed =
    run.status === "FAILED" ||
    run.status === "COMPLETED_WITH_ERRORS" ||
    run.indexingStatus === "COMPLETED_WITH_ERRORS";
  const active = runIsActive(run);
  const knownComplete =
    run.status === "SUCCEEDED" && ["SUCCEEDED", "NOT_REQUIRED"].includes(run.indexingStatus);
  const label = runHasNoChanges(run)
    ? "No changes"
    : active
      ? run.status === "SUCCEEDED"
        ? "Indexing pending"
        : (runStatusLabels[run.status] ?? "Unknown")
      : failed
        ? run.status === "FAILED"
          ? "Failed"
          : "Completed with errors"
        : knownComplete
          ? "Completed"
          : run.status === "SUCCEEDED"
            ? "Outcome unknown"
            : (runStatusLabels[run.status] ?? "Unknown");
  const tone = failed ? "danger" : active ? "info" : knownComplete ? "success" : "neutral";
  const Icon = failed ? CircleX : active ? LoaderCircle : knownComplete ? CircleCheck : CircleHelp;
  return (
    <StatusBadge tone={tone} className={`${statusPill(tone)} px-2.5 py-0.5`}>
      <Icon
        aria-hidden="true"
        className={`size-3.5 shrink-0 ${active ? "motion-safe:animate-spin" : ""}`}
      />
      {ui(label)}
    </StatusBadge>
  );
}

const itemStatusLabels: Record<string, string> = {
  PENDING: "Pending",
  INDEXED: "Indexed",
  FAILED: "Failed",
  DELETING: "Deleting",
};

const runStatusLabels: Record<string, string> = {
  QUEUED: "Queued",
  ACQUIRING: "Acquiring",
  RETRY_SCHEDULED: "Retry scheduled",
  RECOVERY_PENDING: "Recovery pending",
  INDEXING: "Indexing",
  CANCELLED: "Cancelled",
  SUPERSEDED: "Superseded",
  UNKNOWN: "Unknown",
};

const attemptStatusLabels: Record<string, string> = {
  NOT_STARTED: "Queued",
  IN_PROGRESS: "Processing",
  SUCCEEDED: "Indexed",
  FAILED: "Failed",
  SUPERSEDED: "Superseded",
  CANCELLED: "Cancelled",
};

export function ItemStatus({
  item,
}: {
  item: Pick<SourceItem, "status"> & {
    latestAttempt?: SourceItem["latestAttempt"] | null;
  };
}) {
  const ui = useAppTranslation();

  const attemptStatus = item.latestAttempt?.status;
  const attemptLabel =
    attemptStatus && Object.hasOwn(attemptStatusLabels, attemptStatus)
      ? attemptStatusLabels[attemptStatus]
      : "Unknown";
  const label =
    item.status === "PENDING" &&
    (attemptStatus === "NOT_STARTED" || attemptStatus === "IN_PROGRESS")
      ? attemptLabel
      : Object.hasOwn(itemStatusLabels, item.status)
        ? itemStatusLabels[item.status]
        : "Unknown";
  const tone =
    item.status === "INDEXED"
      ? "success"
      : item.status === "FAILED"
        ? "danger"
        : item.status === "PENDING"
          ? "info"
          : "neutral";
  const Icon =
    item.status === "INDEXED"
      ? CircleCheck
      : item.status === "FAILED"
        ? CircleX
        : item.status === "DELETING"
          ? Trash2
          : item.status === "PENDING"
            ? attemptStatus === "IN_PROGRESS"
              ? LoaderCircle
              : Clock3
            : CircleHelp;
  return (
    <>
      <StatusBadge tone={tone} className={`${statusPill(tone)} px-2.5 py-1`}>
        <Icon
          aria-hidden="true"
          className={`size-3.5 shrink-0 ${item.status === "PENDING" && attemptStatus === "IN_PROGRESS" ? "motion-safe:animate-spin" : ""}`}
        />
        {ui(label)}
      </StatusBadge>
      {item.latestAttempt && attemptLabel !== label ? (
        <p className="mt-1 font-secondary-body text-content-muted">
          {ui("Latest attempt:")} {ui(attemptLabel)}
        </p>
      ) : null}
    </>
  );
}
