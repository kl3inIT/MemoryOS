import { useAppTranslation } from "@/i18n/use-app-translation";
import { useId } from "react";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { HelpPopover } from "@/components/ui/help-popover";
import { Input } from "@/components/ui/input";
import {
  MAX_SHAREPOINT_PRUNE_INTERVAL_HOURS,
  MAX_SHAREPOINT_SYNC_INTERVAL_MINUTES,
  type SharePointScopeDraft,
} from "./sharepoint-scope";

type Schedule = Pick<SharePointScopeDraft, "syncIntervalMinutes" | "pruneIntervalHours">;

/** The two intervals a SharePoint Source runs on: an incremental refresh and a full prune. */
export function SharePointScheduleFields({
  draft,
  disabled,
  onChange,
}: {
  draft: Schedule;
  disabled: boolean;
  onChange: (draft: Schedule) => void;
}) {
  const ui = useAppTranslation();

  const id = useId();
  const prune = Number(draft.pruneIntervalHours);

  return (
    <div className="grid gap-5 sm:grid-cols-2">
      <Field>
        <FieldLabel htmlFor={`${id}-sync`}>{ui("Synchronization interval in minutes")}</FieldLabel>
        <Input
          id={`${id}-sync`}
          type="number"
          inputMode="numeric"
          min={1}
          max={MAX_SHAREPOINT_SYNC_INTERVAL_MINUTES}
          step={1}
          required
          value={draft.syncIntervalMinutes}
          disabled={disabled}
          onChange={(event) => onChange({ ...draft, syncIntervalMinutes: event.target.value })}
        />
        <FieldDescription>
          {ui(
            "Each run reads the change log from where the previous one stopped, with a thirty-minute overlap.",
          )}
        </FieldDescription>
      </Field>
      <Field>
        <div className="flex items-center gap-2">
          <FieldLabel htmlFor={`${id}-prune`}>{ui("Prune interval in hours")}</FieldLabel>
          <HelpPopover label={ui("Prune interval in hours")}>
            <p>
              {ui(
                "A prune lists the whole scope and removes what it no longer finds. It only removes after recording that the listing finished, so a site that cannot answer leaves its documents in place.",
              )}
            </p>
            <p>
              {ui(
                "A deleted file is normally removed by the next synchronization, because the change log reports it. Pruning covers what the change log cannot: an item the application loses permission to read, one moved out of a selected folder, and anything missed when a stale change token forces a full rescan.",
              )}
            </p>
            <p>{ui("0 disables pruning. The first prune runs one interval after creation.")}</p>
          </HelpPopover>
        </div>
        <Input
          id={`${id}-prune`}
          type="number"
          inputMode="numeric"
          min={0}
          max={MAX_SHAREPOINT_PRUNE_INTERVAL_HOURS}
          step={1}
          required
          value={draft.pruneIntervalHours}
          disabled={disabled}
          onChange={(event) => onChange({ ...draft, pruneIntervalHours: event.target.value })}
        />
        <FieldDescription>
          {prune === 0
            ? ui("Pruning is disabled. Only what the change log reports is removed.")
            : ui("Documents deleted outside the change log are found by the next prune.")}
        </FieldDescription>
      </Field>
    </div>
  );
}
