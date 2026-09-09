import type { SourceRun } from "@/lib/hey-api/types.gen";
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
