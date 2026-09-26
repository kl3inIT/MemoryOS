import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { SourceItem, SourceRun } from "@/lib/hey-api/types.gen";
import { StatusBadge } from "@/components/ui/status-badge";
import {
  CircleCheck,
  CircleHelp,
  CircleX,
  Clock3,
  LoaderCircle,
  Pause,
  Trash2,
} from "lucide-react";
import { historyRelativeTime, runHasNoChanges, runIsActive } from "./source-history";
import { SourceHint } from "@/features/sources/shared/source-hint";
import { Spinner } from "@/components/ui/spinner";

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
    <StatusBadge tone={tone} variant="pill">
      {active ? <Spinner aria-hidden="true" className="size-3.5" /> : <Icon aria-hidden="true" />}
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

/**
 * Extracted content is not the same as searchable content: a reconnected credential invalidates
 * every retrieval mapping, and a search write can fail on its own. A file in either state must not
 * claim to be indexed while the Source reports no indexed documents.
 */
const searchPresentation: Record<
  SourceItem["searchStatus"],
  { label: string; tone: "info" | "danger" | "neutral"; Icon: typeof Clock3 } | null
> = {
  READY: null,
  INDEXING: { label: "Indexing", tone: "info", Icon: LoaderCircle },
  WAITING: { label: "Awaiting re-index", tone: "neutral", Icon: Clock3 },
  FAILED: { label: "Search indexing failed", tone: "danger", Icon: CircleX },
};

export function ItemStatus({
  item,
  sourcePaused = false,
}: {
  item: Pick<SourceItem, "status" | "searchStatus"> & {
    latestAttempt?: SourceItem["latestAttempt"] | null;
  };
  /** A paused Source runs nothing, so its unindexed files are held rather than waiting their turn. */
  sourcePaused?: boolean;
}) {
  const ui = useAppTranslation();

  const search = item.status === "INDEXED" ? searchPresentation[item.searchStatus] : null;
  const attemptStatus = item.latestAttempt?.status;
  const attemptLabel =
    attemptStatus && Object.hasOwn(attemptStatusLabels, attemptStatus)
      ? attemptStatusLabels[attemptStatus]
      : "Unknown";
  const running = attemptStatus === "NOT_STARTED" || attemptStatus === "IN_PROGRESS";
  const held = sourcePaused && item.status === "PENDING" && !running;
  const label = search
    ? search.label
    : held
      ? "Paused"
      : item.status === "PENDING" && running
        ? attemptLabel
        : Object.hasOwn(itemStatusLabels, item.status)
          ? itemStatusLabels[item.status]
          : "Unknown";
  const tone = search
    ? search.tone
    : item.status === "INDEXED"
      ? "success"
      : item.status === "FAILED"
        ? "danger"
        : item.status === "PENDING" && !held
          ? "info"
          : "neutral";
  const Icon = search
    ? search.Icon
    : held
      ? Pause
      : item.status === "INDEXED"
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
  const spinning = search
    ? search.Icon === LoaderCircle
    : item.status === "PENDING" && attemptStatus === "IN_PROGRESS";
  return (
    <>
      <StatusBadge tone={tone} variant="pill">
        {spinning ? (
          <Spinner aria-hidden="true" className="size-3.5" />
        ) : (
          <Icon aria-hidden="true" />
        )}
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
