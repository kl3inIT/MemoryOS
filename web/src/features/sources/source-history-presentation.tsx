import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { SourceItem, SourceRun } from "@/lib/hey-api/types.gen";
import { StatusBadge } from "@/components/ui/status-badge";
import { runHasNoChanges, runIsActive } from "./source-history";

export function HistoryTime({ value }: { value: string | null }) {
  const ui = useAppTranslation();

  if (!value) return <span>{ui("Unknown")}</span>;
  const date = new Date(value);
  const full = date.toLocaleString(uiLocale(), { dateStyle: "full", timeStyle: "long" });
  return (
    <time dateTime={value} title={full} aria-label={full}>
      {date.toLocaleString(uiLocale())}
    </time>
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
  return (
    <StatusBadge
      tone={failed ? "danger" : active ? "info" : knownComplete ? "success" : "neutral"}
      className="capitalize"
    >
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

const searchIndexStatusLabels: Record<string, string> = {
  READY: "Ready",
  FAILED: "Failed",
  INDEXING: "Indexing",
  WAITING: "Waiting",
};

export function ItemStatus({
  item,
}: {
  item: Pick<SourceItem, "status" | "searchStatus"> & {
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
  return (
    <>
      <span>{ui(label)}</span>
      {item.latestAttempt && attemptLabel !== label ? (
        <p className="mt-1 font-secondary-body text-content-muted">
          {ui("Latest attempt:")} {ui(attemptLabel)}
        </p>
      ) : null}
      <p className="mt-1 font-secondary-body text-content-muted">
        {ui("Search index:")}{" "}
        {Object.hasOwn(searchIndexStatusLabels, item.searchStatus)
          ? ui(searchIndexStatusLabels[item.searchStatus])
          : ui("Unknown")}
      </p>
    </>
  );
}
