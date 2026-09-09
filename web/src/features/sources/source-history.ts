import type { SourceRun } from "@/lib/hey-api/types.gen";

export function historyDuration(start: string | null, end: string | null) {
  if (!start || !end) return null;
  const milliseconds = new Date(end).getTime() - new Date(start).getTime();
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return null;
  if (milliseconds < 1_000) return "<1s";
  const seconds = Math.floor(milliseconds / 1_000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  return minutes < 60
    ? `${minutes}m ${seconds % 60}s`
    : `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

export function runHasNoChanges(run: SourceRun) {
  return (
    run.status === "SUCCEEDED" &&
    run.indexingStatus === "NOT_REQUIRED" &&
    run.counts.scanned !== null &&
    run.counts.unchanged !== null &&
    run.counts.acquired === 0 &&
    run.counts.published === 0 &&
    run.counts.removed === 0 &&
    run.counts.alreadyPending === 0 &&
    run.counts.acquisitionFailed === 0 &&
    run.counts.indexingFailed === 0 &&
    run.counts.indexingPending === 0 &&
    run.counts.indexingCancelled === 0 &&
    run.counts.indexingSuperseded === 0 &&
    run.counts.skipped === 0
  );
}

export function runIsActive(run: SourceRun) {
  return (
    ["QUEUED", "ACQUIRING", "RETRY_SCHEDULED", "RECOVERY_PENDING", "INDEXING"].includes(
      run.status,
    ) || ["PENDING", "RETRY_SCHEDULED", "RECOVERY_PENDING"].includes(run.indexingStatus)
  );
}
