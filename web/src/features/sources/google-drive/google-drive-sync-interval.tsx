import { appText } from "@/i18n/app-text";
import type { AppCopy } from "@/i18n/app-text";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/radix-select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { updateGoogleDriveScheduleMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GetGoogleDriveConfigurationResponse } from "@/lib/hey-api/types.gen";
import {
  isGoogleDriveRevisionConflict,
  sourceMutationError,
} from "@/features/sources/shared/source-errors";
import {
  formatSyncInterval,
  maxSyncIntervalValue,
  splitSyncInterval,
  syncIntervalMinutes,
  syncIntervalUnitName,
  syncIntervalUnits,
  type SyncIntervalUnit,
} from "@/features/sources/shared/sync-interval";

type IntervalDraft = Pick<GetGoogleDriveConfigurationResponse, "scheduleRevision"> & {
  value: string;
  unit: SyncIntervalUnit;
};

function intervalDraftOf({
  syncIntervalMinutes: minutes,
  scheduleRevision,
}: GetGoogleDriveConfigurationResponse): IntervalDraft {
  const { value, unit } = splitSyncInterval(minutes);
  return { value: String(value), unit, scheduleRevision };
}

type IntervalAction = "save-interval" | "reload-interval" | "pause";

/**
 * Automatic synchronization of a Drive Source: the pause switch and the interval, edited in place
 * against the schedule revision it was loaded from.
 */
export function GoogleDriveSyncInterval({
  sourceId,
  resourceKey,
  configuration,
  canSchedule,
  canPause,
  disabled,
  busy,
  stale,
  activeAction,
  perform,
  onTogglePause,
  incorporate,
  refresh,
  reloadConfiguration,
}: {
  sourceId: string;
  /** Changes with the Source and the person's authority; a change drops the draft. */
  resourceKey: string;
  configuration: GetGoogleDriveConfigurationResponse;
  canSchedule: boolean;
  canPause: boolean;
  disabled: boolean;
  /** Another action of the panel runs. */
  busy: boolean;
  stale: boolean;
  activeAction: string | null;
  perform: (action: IntervalAction, task: (signal: AbortSignal) => Promise<void>) => Promise<void>;
  onTogglePause: () => void;
  /** Takes a saved configuration into the cache. */
  incorporate: (saved: GetGoogleDriveConfigurationResponse) => Promise<void>;
  refresh: () => Promise<void>;
  /** Reads the saved configuration again, failing when it cannot. */
  reloadConfiguration: () => Promise<GetGoogleDriveConfigurationResponse | undefined>;
}) {
  const ui = useAppTranslation();
  const notify = useActionNotifications();
  const updateSchedule = useMutation({ ...updateGoogleDriveScheduleMutation(), retry: false });
  const [intervalDraft, setIntervalDraft] = useState<IntervalDraft | null>(null);
  const [intervalRevisionConflict, setIntervalRevisionConflict] = useState(false);
  const [intervalError, setIntervalError] = useState<AppCopy | null>(null);
  const intervalInput = useRef<HTMLInputElement>(null);
  const intervalEditButton = useRef<HTMLButtonElement>(null);
  const wasEditingInterval = useRef(false);
  const controlsDisabled = disabled || busy || stale;
  const editingInterval = intervalDraft !== null;
  const intervalMinutes = intervalDraft
    ? syncIntervalMinutes(intervalDraft.value, intervalDraft.unit)
    : null;
  const intervalValidation =
    intervalDraft && intervalMinutes === null
      ? appText("Enter a whole number from 1 to {{v1}}.", {
          v1: maxSyncIntervalValue(intervalDraft.unit).toLocaleString(uiLocale()),
        })
      : null;
  const intervalConflicted =
    intervalRevisionConflict ||
    Boolean(intervalDraft && configuration.scheduleRevision !== intervalDraft.scheduleRevision);

  const [previous, setPrevious] = useState({ resourceKey, canSchedule });
  if (previous.resourceKey !== resourceKey || previous.canSchedule !== canSchedule) {
    setPrevious({ resourceKey, canSchedule });
    if (previous.resourceKey !== resourceKey || !canSchedule) {
      setIntervalDraft(null);
      setIntervalError(null);
      setIntervalRevisionConflict(false);
    }
  }

  useLayoutEffect(() => {
    if (busy) return;
    if (editingInterval) intervalInput.current?.focus();
    else if (wasEditingInterval.current) intervalEditButton.current?.focus();
    wasEditingInterval.current = editingInterval;
  }, [editingInterval, busy]);

  function runInterval(
    action: "save-interval" | "reload-interval",
    task: (signal: AbortSignal) => Promise<void>,
  ) {
    setIntervalError(null);
    void perform(action, task).catch((cause: unknown) => {
      if (isGoogleDriveRevisionConflict(cause)) {
        if (action === "save-interval") setIntervalRevisionConflict(true);
      } else setIntervalError(sourceMutationError(cause, "google-drive-schedule"));
    });
  }

  async function saveInterval(signal: AbortSignal) {
    if (!intervalDraft || intervalMinutes === null || intervalConflicted || stale) return;
    const saved = await updateSchedule.mutateAsync({
      path: { sourceId },
      headers: { "If-Match": `"${intervalDraft.scheduleRevision}"` },
      body: { syncIntervalMinutes: intervalMinutes },
      signal,
    });
    signal.throwIfAborted();
    await incorporate(saved);
    signal.throwIfAborted();
    setIntervalDraft(null);
    setIntervalRevisionConflict(false);
    notify({
      tone: "success",
      title: "Automatic interval saved",
      description: appText("Synchronizes every {{v1}}. Current work is unchanged.", {
        v1: formatSyncInterval(saved.syncIntervalMinutes),
      }),
    });
    await refresh();
  }

  async function reloadInterval(signal: AbortSignal) {
    const saved = await reloadConfiguration();
    signal.throwIfAborted();
    if (!saved) return;
    setIntervalDraft(intervalDraftOf(saved));
    setIntervalRevisionConflict(false);
    notify({
      tone: "info",
      title: "Saved interval loaded",
      description: appText("Local interval changes were discarded."),
    });
    intervalInput.current?.focus();
  }

  function cancelInterval() {
    setIntervalDraft(null);
    setIntervalRevisionConflict(false);
    setIntervalError(null);
  }

  return (
    <div className="min-w-0" aria-live="polite">
      <dt className="text-content-muted">{ui("Automatic synchronization")}</dt>
      <dd className="mt-1 min-w-0 space-y-3 text-content-primary">
        <div className="flex min-h-8 flex-wrap items-center gap-2">
          {canPause ? (
            <Switch
              checked={!configuration.syncPaused}
              disabled={controlsDisabled}
              aria-label={ui("Automatic synchronization")}
              onCheckedChange={onTogglePause}
            />
          ) : null}
          <span>
            {ui("Every {{v1}}", {
              v1: formatSyncInterval(configuration.syncIntervalMinutes),
            })}
          </span>
          {configuration.syncPaused ? (
            <StatusBadge tone="neutral" size="sm">
              {ui("Paused")}
            </StatusBadge>
          ) : null}
          {canSchedule && !editingInterval ? (
            <IconButton
              ref={intervalEditButton}
              size="sm"
              aria-label={ui("Edit interval")}
              prominence="tertiary"
              disabled={controlsDisabled}
              onClick={() => {
                setIntervalDraft(intervalDraftOf(configuration));
                setIntervalError(null);
              }}
            >
              <Pencil aria-hidden="true" />
            </IconButton>
          ) : null}
        </div>
        {canSchedule && intervalDraft ? (
          <form
            className="space-y-3"
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              if (!controlsDisabled && !intervalConflicted && !intervalValidation)
                runInterval("save-interval", saveInterval);
            }}
            onKeyDown={(event) => {
              if (event.key === "Escape" && !busy) {
                event.preventDefault();
                cancelInterval();
              }
            }}
          >
            <div className="space-y-2">
              <span
                id={`sync-interval-label-${sourceId}`}
                className="block text-sm font-medium text-content-primary"
              >
                {ui("Sync every")}
              </span>
              <div className="flex items-center gap-2">
                <Input
                  ref={intervalInput}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={maxSyncIntervalValue(intervalDraft.unit)}
                  step={1}
                  required
                  value={intervalDraft.value}
                  disabled={controlsDisabled}
                  aria-labelledby={`sync-interval-label-${sourceId}`}
                  aria-invalid={Boolean(intervalValidation)}
                  aria-describedby={
                    intervalValidation ? `sync-interval-error-${sourceId}` : undefined
                  }
                  className="w-24"
                  onChange={(event) => {
                    setIntervalDraft({ ...intervalDraft, value: event.target.value });
                    setIntervalError(null);
                  }}
                />
                <Select
                  value={intervalDraft.unit}
                  disabled={controlsDisabled}
                  onValueChange={(next) => {
                    const unit = syncIntervalUnits.find((entry) => entry === next);
                    if (!unit) return;
                    setIntervalDraft({ ...intervalDraft, unit });
                    setIntervalError(null);
                  }}
                >
                  <SelectTrigger
                    aria-label={ui("Interval unit")}
                    className="h-(--control-height-md) min-w-28"
                  >
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent position="popper">
                    {syncIntervalUnits.map((unit) => (
                      <SelectItem key={unit} value={unit}>
                        {syncIntervalUnitName(unit, Number(intervalDraft.value) || 0)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
            {intervalValidation ? (
              <p
                id={`sync-interval-error-${sourceId}`}
                role="alert"
                className="text-status-danger-content"
              >
                {ui(intervalValidation)}
              </p>
            ) : null}
            {intervalConflicted ? (
              <p role="alert" className="text-status-warning-content">
                {ui(
                  "The automatic interval changed while you were editing. Your interval draft has not been saved. Reload the saved interval before continuing.",
                )}
              </p>
            ) : null}
            <div className="flex flex-wrap gap-2">
              <Button
                type="submit"
                disabled={controlsDisabled || intervalConflicted || Boolean(intervalValidation)}
                pending={activeAction === "save-interval"}
              >
                {ui("Save interval")}
              </Button>
              <Button prominence="tertiary" disabled={busy} onClick={cancelInterval}>
                {ui("Cancel")}
              </Button>
              {intervalConflicted ? (
                <Button
                  prominence="secondary"
                  disabled={disabled || busy || !canSchedule}
                  pending={activeAction === "reload-interval"}
                  onClick={() => runInterval("reload-interval", reloadInterval)}
                >
                  {ui("Reload saved interval")}
                </Button>
              ) : null}
            </div>
          </form>
        ) : null}
        {intervalError ? (
          <p role="alert" className="text-status-danger-content">
            {ui(intervalError)}
          </p>
        ) : null}
      </dd>
    </div>
  );
}
