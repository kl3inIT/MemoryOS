import { appText } from "@/i18n/app-text";
import type { AppCopy } from "@/i18n/app-text";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation } from "@tanstack/react-query";
import { Pencil, TriangleAlert } from "lucide-react";
import { useLayoutEffect, useRef, useState, type KeyboardEvent, type RefObject } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
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
      <dd className="mt-1 flex min-w-0 flex-col gap-3 text-content-primary">
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
          <SyncIntervalForm
            id={`sync-interval-${sourceId}`}
            draft={intervalDraft}
            inputRef={intervalInput}
            validation={intervalValidation}
            conflicted={intervalConflicted}
            controlsDisabled={controlsDisabled}
            busy={busy}
            reloadDisabled={disabled || busy || !canSchedule}
            activeAction={activeAction}
            onChange={(change) => {
              setIntervalDraft({ ...intervalDraft, ...change });
              setIntervalError(null);
            }}
            onSave={() => {
              if (!controlsDisabled && !intervalConflicted && !intervalValidation)
                runInterval("save-interval", saveInterval);
            }}
            onCancel={cancelInterval}
            onReload={() => runInterval("reload-interval", reloadInterval)}
          />
        ) : null}
        {intervalError ? <FieldError role="alert">{ui(intervalError)}</FieldError> : null}
      </dd>
    </div>
  );
}

/** The interval being edited: a whole number of minutes, hours or days. */
function SyncIntervalForm({
  id,
  draft,
  inputRef,
  validation,
  conflicted,
  controlsDisabled,
  busy,
  reloadDisabled,
  activeAction,
  onChange,
  onSave,
  onCancel,
  onReload,
}: {
  id: string;
  draft: IntervalDraft;
  inputRef: RefObject<HTMLInputElement | null>;
  validation: AppCopy | null;
  /** The saved schedule changed under the draft. */
  conflicted: boolean;
  controlsDisabled: boolean;
  busy: boolean;
  reloadDisabled: boolean;
  activeAction: string | null;
  onChange: (change: Partial<Pick<IntervalDraft, "value" | "unit">>) => void;
  onSave: () => void;
  onCancel: () => void;
  onReload: () => void;
}) {
  const ui = useAppTranslation();
  // Escape in the interval leaves the edit, as Cancel does.
  const escape = (event: KeyboardEvent) => {
    if (event.key === "Escape" && !busy) {
      event.preventDefault();
      onCancel();
    }
  };
  return (
    <form
      className="flex flex-col gap-3"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        onSave();
      }}
    >
      <Field data-invalid={validation ? true : undefined}>
        <FieldLabel htmlFor={`${id}-value`}>{ui("Sync every")}</FieldLabel>
        <div className="flex items-center gap-2">
          <Input
            ref={inputRef}
            id={`${id}-value`}
            type="number"
            inputMode="numeric"
            min={1}
            max={maxSyncIntervalValue(draft.unit)}
            step={1}
            required
            value={draft.value}
            disabled={controlsDisabled}
            aria-invalid={Boolean(validation)}
            aria-describedby={validation ? `${id}-error` : undefined}
            className="w-24"
            onChange={(event) => onChange({ value: event.target.value })}
            onKeyDown={escape}
          />
          <Select
            value={draft.unit}
            disabled={controlsDisabled}
            onValueChange={(next) => {
              const unit = syncIntervalUnits.find((entry) => entry === next);
              if (unit) onChange({ unit });
            }}
          >
            <SelectTrigger aria-label={ui("Interval unit")} className="min-w-28" onKeyDown={escape}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent position="popper">
              <SelectGroup>
                {syncIntervalUnits.map((unit) => (
                  <SelectItem key={unit} value={unit}>
                    {syncIntervalUnitName(unit, Number(draft.value) || 0)}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </div>
        {validation ? (
          <FieldError id={`${id}-error`} role="alert">
            {ui(validation)}
          </FieldError>
        ) : null}
      </Field>
      {conflicted ? (
        <Alert variant="warning">
          <TriangleAlert aria-hidden="true" />
          <AlertDescription>
            {ui(
              "The automatic interval changed while you were editing. Your interval draft has not been saved. Reload the saved interval before continuing.",
            )}
          </AlertDescription>
        </Alert>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <Button
          type="submit"
          disabled={controlsDisabled || conflicted || Boolean(validation)}
          pending={activeAction === "save-interval"}
        >
          {ui("Save interval")}
        </Button>
        <Button prominence="tertiary" disabled={busy} onClick={onCancel}>
          {ui("Cancel")}
        </Button>
        {conflicted ? (
          <Button
            prominence="secondary"
            disabled={reloadDisabled}
            pending={activeAction === "reload-interval"}
            onClick={onReload}
          >
            {ui("Reload saved interval")}
          </Button>
        ) : null}
      </div>
    </form>
  );
}
