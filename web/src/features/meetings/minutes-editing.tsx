import { useState, type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, Pencil, Plus, Trash2, X } from "lucide-react";
import { useAppForm, setServerErrors, useProblemErrors } from "@/components/form/app-form";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  addMeetingMinutesItemMutation,
  editMeetingMinutesItemMutation,
  editMeetingMinutesSummaryMutation,
  removeMeetingMinutesItemMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { cn } from "@/lib/utils";
import { InputField, TextareaField } from "./meeting-form-fields";
import {
  patchMeeting,
  withAddedMinutesItem,
  withMinutesItem,
  withoutMinutesItem,
  type MeetingDetail,
  type MeetingMinutesItem,
} from "./meetings-api";
import { useFailureText } from "./use-failure-text";

type ItemValues = { text: string; owner: string; due: string };

/**
 * The pencil sits beside the line it edits and shows on hover or focus, as Fireflies and Otter do; a device without
 * hover always shows it.
 */
function EditButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <span className={cn("shrink-0", hoverReveal)}>
      <IconButton size="sm" aria-label={label} onClick={onClick}>
        <Pencil />
      </IconButton>
    </span>
  );
}

/**
 * The minutes as the owner may correct them. A model that misheard one conclusion should cost one edit, not a rerun
 * of the whole meeting; what it first wrote stays on the record either way.
 */
export function EditableSummary({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  const [editing, setEditing] = useState(false);
  if (!editing)
    return (
      <div className="group flex items-start gap-2">
        <p className="min-w-0 flex-1 whitespace-pre-wrap text-content-secondary">
          {meeting.minutes.summary}
        </p>
        {meeting.owned && <EditButton label={ui("Sửa tóm tắt")} onClick={() => setEditing(true)} />}
      </div>
    );
  return <SummaryForm meeting={meeting} onDone={() => setEditing(false)} />;
}

function SummaryForm({ meeting, onDone }: { meeting: MeetingDetail; onDone: () => void }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemErrors = useProblemErrors();
  const edit = useMutation(editMeetingMinutesSummaryMutation());
  const form = useAppForm({
    defaultValues: { summary: meeting.minutes.summary },
    onSubmit: async ({ value, formApi }) => {
      try {
        const saved = await edit.mutateAsync({
          path: { meetingId: meeting.id },
          body: { summary: value.summary },
        });
        patchMeeting(cache, meeting.id, (current) => ({
          ...current,
          minutes: { ...current.minutes, summary: saved.summary, edited: saved.edited },
        }));
        onDone();
      } catch (failed) {
        setServerErrors(formApi, problemErrors(failed));
      }
    },
  });
  return (
    <form
      className="grid gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <form.AppField name="summary">
        {() => <TextareaField label={ui("Tóm tắt")} hideLabel rows={6} maxLength={20000} />}
      </form.AppField>
      <form.AppForm>
        <form.FormError />
        <div className="flex gap-2">
          <form.SubmitButton size="sm">
            <Check data-icon="inline-start" aria-hidden="true" />
            {ui("Lưu")}
          </form.SubmitButton>
          <Button size="sm" prominence="tertiary" onClick={onDone}>
            <X data-icon="inline-start" aria-hidden="true" />
            {ui("Huỷ")}
          </Button>
        </div>
      </form.AppForm>
    </form>
  );
}

/** One decision or one piece of work, with what it says, who owns it and when it is due. */
export function EditableItem({
  meeting,
  item,
  kind,
  children,
}: {
  meeting: MeetingDetail;
  item: MeetingMinutesItem;
  kind: "ACTION" | "DECISION";
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const failureText = useFailureText();
  const [editing, setEditing] = useState(false);
  const edit = useMutation(editMeetingMinutesItemMutation());
  const remove = useMutation({
    ...removeMeetingMinutesItemMutation(),
    onSuccess: () =>
      patchMeeting(cache, meeting.id, (current) => withoutMinutesItem(current, item.id)),
  });

  if (!editing)
    return (
      <div className="group flex items-start gap-2">
        <div className="min-w-0 flex-1">
          {children}
          {item.edited && <p className="mt-0.5 text-xs text-content-muted">{ui("Bạn đã sửa")}</p>}
        </div>
        {meeting.owned && (
          <EditButton
            label={ui("Sửa")}
            onClick={() => {
              remove.reset();
              setEditing(true);
            }}
          />
        )}
      </div>
    );

  return (
    <ItemForm
      kind={kind}
      initial={{ text: item.text, owner: item.owner ?? "", due: item.due ?? "" }}
      submitLabel={ui("Lưu")}
      busy={remove.isPending}
      failure={remove.isError ? failureText(remove.error) : undefined}
      onSubmit={async (value) => {
        remove.reset();
        const saved = await edit.mutateAsync({
          path: { meetingId: meeting.id, itemId: item.id },
          body: { text: value.text, owner: value.owner || null, due: value.due || null },
        });
        patchMeeting(cache, meeting.id, (current) => withMinutesItem(current, saved));
        setEditing(false);
      }}
      onCancel={() => setEditing(false)}
      extra={
        <Button
          size="sm"
          prominence="tertiary"
          tone="danger"
          className="ml-auto"
          pending={remove.isPending}
          onClick={() => remove.mutate({ path: { meetingId: meeting.id, itemId: item.id } })}
        >
          <Trash2 data-icon="inline-start" aria-hidden="true" />
          {ui("Xoá")}
        </Button>
      }
    />
  );
}

/** A decision or a piece of work the model missed, written in by the owner. */
export function NewItem({
  meeting,
  kind,
  label,
}: {
  meeting: MeetingDetail;
  kind: "ACTION" | "DECISION";
  label: string;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [open, setOpen] = useState(false);
  const add = useMutation(addMeetingMinutesItemMutation());

  if (!open)
    return (
      <div>
        <Button size="sm" prominence="tertiary" className="-ml-2" onClick={() => setOpen(true)}>
          <Plus data-icon="inline-start" aria-hidden="true" />
          {label}
        </Button>
      </div>
    );

  return (
    <ItemForm
      kind={kind}
      initial={{ text: "", owner: "", due: "" }}
      submitLabel={ui("Thêm")}
      onSubmit={async (value) => {
        const added = await add.mutateAsync({
          path: { meetingId: meeting.id },
          body: { kind, text: value.text, owner: value.owner || null, due: value.due || null },
        });
        patchMeeting(cache, meeting.id, (current) => withAddedMinutesItem(current, kind, added));
        setOpen(false);
      }}
      onCancel={() => setOpen(false)}
    />
  );
}

/**
 * What an item says, and for a piece of work who takes it and by when. A decision belongs to the meeting rather than
 * to a person, so it carries neither an owner nor a deadline.
 */
function ItemForm({
  kind,
  initial,
  submitLabel,
  busy = false,
  failure,
  onSubmit,
  onCancel,
  extra,
}: {
  kind: "ACTION" | "DECISION";
  initial: ItemValues;
  submitLabel: string;
  /** Another request about the item runs, such as removing it. */
  busy?: boolean;
  failure?: string;
  onSubmit: (value: ItemValues) => Promise<void>;
  onCancel: () => void;
  extra?: ReactNode;
}) {
  const ui = useAppTranslation();
  const problemErrors = useProblemErrors();
  const assignable = kind === "ACTION";
  const form = useAppForm({
    defaultValues: initial,
    onSubmit: async ({ value, formApi }) => {
      try {
        await onSubmit({
          text: value.text,
          owner: assignable ? value.owner.trim() : "",
          due: assignable ? value.due.trim() : "",
        });
      } catch (failed) {
        setServerErrors(formApi, problemErrors(failed));
      }
    },
  });
  return (
    <form
      className="grid gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <div className="grid gap-2">
        <form.AppField name="text">
          {() => <TextareaField label={ui("Nội dung")} hideLabel rows={2} maxLength={2000} />}
        </form.AppField>
        {assignable && (
          <div className="grid gap-2 sm:grid-cols-2">
            <form.AppField name="owner">
              {() => <InputField label={ui("Người nhận")} maxLength={200} />}
            </form.AppField>
            <form.AppField name="due">
              {() => <InputField label={ui("Hạn")} maxLength={100} />}
            </form.AppField>
          </div>
        )}
      </div>
      <form.AppForm>
        <form.FormError />
        {failure && (
          <p role="alert" className="text-sm text-status-danger-content">
            {failure}
          </p>
        )}
        <div className="flex gap-2">
          <form.Subscribe selector={(state) => state.values.text.trim() === ""}>
            {(blank) => (
              <form.SubmitButton size="sm" disabled={blank || busy}>
                <Check data-icon="inline-start" aria-hidden="true" />
                {submitLabel}
              </form.SubmitButton>
            )}
          </form.Subscribe>
          <Button size="sm" prominence="tertiary" onClick={onCancel}>
            <X data-icon="inline-start" aria-hidden="true" />
            {ui("Huỷ")}
          </Button>
          {extra}
        </div>
      </form.AppForm>
    </form>
  );
}
