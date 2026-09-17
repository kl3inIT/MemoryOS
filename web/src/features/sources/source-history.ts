import type { SourceRun } from "@/lib/hey-api/types.gen";
import { uiLocale } from "@/i18n/format";

export function historyDuration(start: string | null, end: string | null) {
  if (!start || !end) return null;
  const milliseconds = new Date(end).getTime() - new Date(start).getTime();
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return null;
  const unit = (value: number, unit: string) =>
    new Intl.NumberFormat(uiLocale(), { style: "unit", unit, unitDisplay: "short" }).format(value);
  if (milliseconds < 1_000) return "<" + unit(1, "second");
  const seconds = Math.floor(milliseconds / 1_000);
  if (seconds < 60) return unit(seconds, "second");
  const minutes = Math.floor(seconds / 60);
  return minutes < 60
    ? `${unit(minutes, "minute")} ${unit(seconds % 60, "second")}`
    : `${unit(Math.floor(minutes / 60), "hour")} ${unit(minutes % 60, "minute")}`;
}

const relativeUnits: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ["day", 86_400],
  ["hour", 3_600],
  ["minute", 60],
];
const relativeLimitSeconds = 7 * 86_400;
/** Tolerates a server clock slightly ahead of the browser's. */
const clockSkewSeconds = 60;

/** "6 minutes ago" for times within the last week; null for older, future or invalid times. */
export function historyRelativeTime(value: string, now = Date.now()) {
  const seconds = Math.round((now - new Date(value).getTime()) / 1_000);
  if (!Number.isFinite(seconds) || seconds < -clockSkewSeconds || seconds >= relativeLimitSeconds)
    return null;
  const elapsed = Math.max(seconds, 0);
  const [unit, size] = relativeUnits.find(([, size]) => elapsed >= size) ?? ["second", 1];
  const amount = Math.floor(elapsed / size);
  return new Intl.RelativeTimeFormat(uiLocale(), { numeric: "auto" }).format(
    amount === 0 ? 0 : -amount,
    unit,
  );
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
