import { useAppTranslation } from "@/i18n/use-app-translation";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useState } from "react";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import { useFieldValidity } from "@/components/form/form-context";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardHeader } from "@/components/ui/card";
import { FieldError } from "@/components/ui/field";
import type { SharePointConfigurationResponse } from "@/lib/hey-api/types.gen";
import { SharePointScheduleFields } from "./sharepoint-schedule-fields";
import { sharePointScheduleError } from "./sharepoint-scope";
import type { SharePointSchedule } from "./use-sharepoint-panel";

/** The refresh and prune intervals of a SharePoint Source, edited against the revision they were read at. */
export function SharePointScheduleCard({
  configuration,
  canSchedule,
  editing,
  controlsDisabled,
  busy,
  saving,
  onEditingChange,
  onSave,
}: {
  configuration: SharePointConfigurationResponse;
  canSchedule: boolean;
  editing: boolean;
  controlsDisabled: boolean;
  busy: boolean;
  saving: boolean;
  onEditingChange: (editing: boolean) => void;
  onSave: (schedule: SharePointSchedule, scheduleRevision: number) => void;
}) {
  const ui = useAppTranslation();
  return (
    <Card>
      <CardHeader>
        <h2 className="font-heading-h3 text-content-primary">{ui("Schedule")}</h2>
        {canSchedule && !editing ? (
          <CardAction>
            <Button
              prominence="secondary"
              disabled={controlsDisabled}
              onClick={() => onEditingChange(true)}
            >
              {ui("Edit intervals")}
            </Button>
          </CardAction>
        ) : null}
      </CardHeader>
      <CardContent>
        {editing ? (
          <ScheduleForm
            configuration={configuration}
            busy={busy}
            saving={saving}
            onCancel={() => onEditingChange(false)}
            onSave={onSave}
          />
        ) : null}
      </CardContent>
    </Card>
  );
}

function ScheduleForm({
  configuration,
  busy,
  saving,
  onCancel,
  onSave,
}: {
  configuration: SharePointConfigurationResponse;
  busy: boolean;
  saving: boolean;
  onCancel: () => void;
  onSave: (schedule: SharePointSchedule, scheduleRevision: number) => void;
}) {
  const ui = useAppTranslation();
  // The draft replaces the schedule it was opened from, not whatever is saved when it is sent.
  const [scheduleRevision] = useState(configuration.scheduleRevision);
  const form = useAppForm({
    defaultValues: {
      schedule: {
        syncIntervalMinutes: String(configuration.syncIntervalMinutes),
        pruneIntervalHours: String(configuration.pruneIntervalHours),
      },
    },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z.object({
        schedule: z.custom<SharePointSchedule>(
          (schedule) => !sharePointScheduleError(schedule as SharePointSchedule),
        ),
      }),
    },
    onSubmit: ({ value }) => onSave(value.schedule, scheduleRevision),
  });
  // An interval out of range keeps Save disabled and says why while it is typed.
  const scheduleError = useStore(form.store, (state) =>
    sharePointScheduleError(state.values.schedule),
  );

  return (
    <form
      className="flex flex-col gap-4"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <form.AppField name="schedule">{() => <ScheduleField disabled={busy} />}</form.AppField>
      {scheduleError ? <FieldError role="alert">{ui(scheduleError)}</FieldError> : null}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" pending={saving} disabled={busy || Boolean(scheduleError)}>
          {ui("Save intervals")}
        </Button>
        <Button prominence="tertiary" disabled={busy} onClick={onCancel}>
          {ui("Cancel")}
        </Button>
      </div>
    </form>
  );
}

function ScheduleField({ disabled }: { disabled: boolean }) {
  const { field } = useFieldValidity<SharePointSchedule>();
  return (
    <SharePointScheduleFields
      draft={field.state.value}
      disabled={disabled}
      onChange={field.handleChange}
    />
  );
}
