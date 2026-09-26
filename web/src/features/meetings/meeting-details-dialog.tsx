import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useStore } from "@tanstack/react-form";
import { Pencil } from "lucide-react";
import { useAppForm, setServerErrors, useProblemErrors } from "@/components/form/app-form";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { FieldGroup } from "@/components/ui/field";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { updateMeetingMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import {
  invalidateMeetingList,
  patchMeeting,
  splitNames,
  type MeetingDetail,
} from "./meetings-api";

/**
 * The name and the people, filled in once the meeting is running: the start form asks only what the recording needs,
 * and Fireflies names a recording on the recording page for the same reason.
 */
export function MeetingDetailsDialog({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button prominence="secondary">
          <Pencil data-icon="inline-start" aria-hidden="true" />
          {ui("Sửa thông tin")}
        </Button>
      </DialogTrigger>
      {/* Mounted per opening, so the form starts from the meeting as it is now. */}
      {open && <DetailsForm meeting={meeting} onClose={() => setOpen(false)} />}
    </Dialog>
  );
}

function DetailsForm({ meeting, onClose }: { meeting: MeetingDetail; onClose: () => void }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemErrors = useProblemErrors();
  const update = useMutation(updateMeetingMutation());
  const form = useAppForm({
    defaultValues: { title: meeting.title, participants: meeting.participants.join(", ") },
    onSubmit: async ({ value, formApi }) => {
      try {
        const saved = await update.mutateAsync({
          path: { meetingId: meeting.id },
          body: { title: value.title, participants: splitNames(value.participants) },
        });
        patchMeeting(cache, meeting.id, (current) => ({
          ...current,
          title: saved.title,
          participants: saved.participants,
          revision: saved.revision,
        }));
        void invalidateMeetingList(cache);
        onClose();
      } catch (failed) {
        setServerErrors(formApi, problemErrors(failed));
      }
    },
  });
  const submitting = useStore(form.store, (state) => state.isSubmitting);
  return (
    <DialogContent
      aria-describedby={undefined}
      onEscapeKeyDown={(event) => submitting && event.preventDefault()}
      onInteractOutside={(event) => submitting && event.preventDefault()}
    >
      <form
        className="grid gap-5"
        onSubmit={(event) => {
          event.preventDefault();
          void form.handleSubmit();
        }}
      >
        <DialogHeader>
          <DialogTitle>{ui("Thông tin cuộc họp")}</DialogTitle>
        </DialogHeader>
        <fieldset disabled={submitting} className="min-w-0">
          <FieldGroup>
            <form.AppField name="title">
              {(field) => <field.TextField label={ui("Tên cuộc họp")} required maxLength={200} />}
            </form.AppField>
            <form.AppField name="participants">
              {(field) => (
                <field.TextField
                  label={ui("Thành phần")}
                  placeholder={ui("Tên người dự, cách nhau bằng dấu phẩy")}
                />
              )}
            </form.AppField>
          </FieldGroup>
        </fieldset>
        <form.AppForm>
          <form.FormError />
          <DialogFooter>
            <Button prominence="tertiary" disabled={submitting} onClick={onClose}>
              {ui("Huỷ")}
            </Button>
            <form.SubmitButton>{ui("Lưu")}</form.SubmitButton>
          </DialogFooter>
        </form.AppForm>
      </form>
    </DialogContent>
  );
}
