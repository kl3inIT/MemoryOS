import type { SourceItem, SourceRun } from "@/lib/hey-api/types.gen";
import { StatusBadge } from "@/components/ui/status-badge";
import { runHasNoChanges, runIsActive } from "./source-history";

export function HistoryTime({ value }: { value: string | null }) {
  if (!value) return <span>Unknown</span>;
  const date = new Date(value);
  const full = date.toLocaleString(undefined, { dateStyle: "full", timeStyle: "long" });
  return (
    <time dateTime={value} title={full} aria-label={full}>
      {date.toLocaleString()}
    </time>
  );
}

export function RunOutcome({ run }: { run: SourceRun }) {
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
        : run.status.replaceAll("_", " ").toLowerCase()
      : failed
        ? run.status === "FAILED"
          ? "Failed"
          : "Completed with errors"
        : knownComplete
          ? "Completed"
          : run.status === "SUCCEEDED"
            ? "Outcome unknown"
            : run.status.replaceAll("_", " ").toLowerCase();
  return (
    <StatusBadge
      tone={failed ? "danger" : active ? "info" : knownComplete ? "success" : "neutral"}
      className="capitalize"
    >
      {label}
    </StatusBadge>
  );
}

const itemStatusLabels: Record<string, string> = {
  PENDING: "Pending",
  INDEXED: "Indexed",
  FAILED: "Failed",
  DELETING: "Deleting",
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
      <span>{label}</span>
      {item.latestAttempt && attemptLabel !== label ? (
        <p className="mt-1 font-secondary-body text-content-muted">
          Latest attempt: {attemptLabel}
        </p>
      ) : null}
      <p className="mt-1 font-secondary-body text-content-muted">
        Search index:{" "}
        {Object.hasOwn(searchIndexStatusLabels, item.searchStatus)
          ? searchIndexStatusLabels[item.searchStatus]
          : "Unknown"}
      </p>
    </>
  );
}
