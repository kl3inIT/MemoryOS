import { useEffect, useId, useRef, useState } from "react";
import { useStore } from "@tanstack/react-form";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { FileAudio, Upload, Users } from "lucide-react";
import { useAppForm, setServerErrors, useProblemErrors } from "@/components/form/app-form";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { NativeSelect } from "@/components/ui/native-select";
import { Progress } from "@/components/ui/progress";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listMeetingTranscribersOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { MeetingShareField } from "./meeting-share-field";
import {
  splitNames,
  uploadRecording,
  type MeetingAudience,
  type MeetingTranscriber,
} from "./meetings-api";
import { useCreateMeeting } from "./use-create-meeting";

/** Containers every supported provider reads; the server checks the type again against the bytes. */
const ACCEPT = ".mp3,.m4a,.wav,.webm,.ogg,.flac,.mp4,audio/*";

function megabytes(bytes: number) {
  return Math.round(bytes / (1024 * 1024));
}

/** A recording under a megabyte still has to read as something; the limits are always in whole megabytes. */
function size(bytes: number) {
  return bytes >= 1024 * 1024
    ? `${(bytes / (1024 * 1024)).toFixed(1)} MB`
    : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

/**
 * A meeting made from a recording taken somewhere else. The file goes straight to storage and a job transcribes it;
 * the audio is deleted once the transcript exists.
 */
export function UploadRecordingDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* Mounted per opening; closing it stops an upload still running. */}
      {open && <UploadRecordingForm onClose={() => onOpenChange(false)} />}
    </Dialog>
  );
}

function UploadRecordingForm({ onClose }: { onClose: () => void }) {
  const ui = useAppTranslation();
  const id = useId();
  const navigate = useNavigate();
  const problemErrors = useProblemErrors();
  const { create, publish } = useCreateMeeting();
  const picker = useRef<HTMLInputElement>(null);
  const aborter = useRef<AbortController>(undefined);
  // Leaving the dialog, by closing it or navigating away, stops the upload.
  useEffect(() => () => aborter.current?.abort(), []);
  const [percent, setPercent] = useState<number>();
  const transcribers = useQuery(listMeetingTranscribersOptions());
  const audience: MeetingAudience = { people: [], groups: [] };
  const form = useAppForm({
    defaultValues: {
      file: undefined as File | undefined,
      title: "",
      participants: "",
      provider: undefined as MeetingTranscriber["provider"] | undefined,
      audience,
      consent: false,
    },
    onSubmit: async ({ value, formApi }) => {
      const { file } = value;
      if (!file || !value.consent) return;
      const controller = new AbortController();
      aborter.current = controller;
      setPercent(0);
      try {
        const meeting = await create({
          title: value.title.trim() || file.name.replace(/\.[^.]+$/, ""),
          kind: "IN_PERSON",
          language: "vi",
          participants: splitNames(value.participants),
          terms: [],
        });
        const uploaded = await uploadRecording({
          meetingId: meeting.id,
          file,
          provider: chosenOf(transcribers.data, value.provider)?.provider,
          signal: controller.signal,
          onProgress: setPercent,
        });
        await publish(uploaded, value.audience);
        onClose();
        await navigate({ to: "/meetings/$meetingId", params: { meetingId: uploaded.id } });
      } catch (failed) {
        if (!controller.signal.aborted) setServerErrors(formApi, problemErrors(failed));
      } finally {
        aborter.current = undefined;
        setPercent(undefined);
      }
    },
  });
  const file = useStore(form.store, (state) => state.values.file);
  const provider = useStore(form.store, (state) => state.values.provider);
  const consent = useStore(form.store, (state) => state.values.consent);
  const chosen = chosenOf(transcribers.data, provider);
  const pending = percent !== undefined;
  const tooLarge = !!file && !!chosen && file.size > chosen.maxBytes;
  const nobody = transcribers.isSuccess && transcribers.data.length === 0;

  return (
    <DialogContent className="sm:max-w-xl" aria-describedby={undefined}>
      <form
        className="grid gap-5"
        onSubmit={(event) => {
          event.preventDefault();
          if (!tooLarge && !nobody) void form.handleSubmit();
        }}
      >
        <DialogHeader>
          <DialogTitle>{ui("Tải file ghi âm")}</DialogTitle>
        </DialogHeader>
        {nobody && (
          <Alert variant="warning" role="status">
            <AlertTitle>
              {ui(
                "Chưa có kết nối nhận dạng giọng nói nào đọc được file. Hãy nhờ quản trị viên cấu hình.",
              )}
            </AlertTitle>
          </Alert>
        )}
        <fieldset disabled={pending || nobody} className="min-w-0">
          <FieldGroup>
            <form.AppField name="file">
              {(field) => (
                <Field>
                  <FieldLabel htmlFor={`${id}-file`}>{ui("File ghi âm")}</FieldLabel>
                  {/* A button over a hidden input, as the library upload does: the native control cannot be styled
                      and shows the browser's own English label. */}
                  <div className="flex items-center gap-3">
                    <Button
                      size="sm"
                      prominence="secondary"
                      onClick={() => picker.current?.click()}
                    >
                      <Upload data-icon="inline-start" aria-hidden="true" />
                      {ui("Chọn file")}
                    </Button>
                    <span className="min-w-0 truncate text-sm text-content-secondary">
                      {field.state.value
                        ? ui("{{name}} · {{size}}", {
                            name: field.state.value.name,
                            size: size(field.state.value.size),
                          })
                        : ui("Chưa chọn file nào")}
                    </span>
                  </div>
                  <input
                    ref={picker}
                    id={`${id}-file`}
                    type="file"
                    accept={ACCEPT}
                    className="sr-only"
                    onChange={(event) => field.handleChange(event.target.files?.[0])}
                  />
                </Field>
              )}
            </form.AppField>
            <form.AppField name="title">
              {(field) => (
                <field.TextField
                  label={ui("Tên cuộc họp")}
                  maxLength={200}
                  placeholder={ui("Giao ban tuần")}
                />
              )}
            </form.AppField>
            <form.AppField name="participants">
              {(field) => (
                <field.TextField
                  label={ui("Thành phần")}
                  placeholder={ui("Tên người dự, cách nhau bằng dấu phẩy")}
                />
              )}
            </form.AppField>
            <form.AppField name="audience">
              {(field) => (
                <MeetingShareField
                  label={ui("Chia sẻ với")}
                  value={field.state.value}
                  disabled={pending}
                  onChange={field.handleChange}
                />
              )}
            </form.AppField>
            <form.AppField name="provider">
              {(field) => (
                <Field>
                  {transcribers.data && transcribers.data.length > 1 && (
                    <>
                      <FieldLabel htmlFor={`${id}-provider`}>{ui("Nhận dạng bằng")}</FieldLabel>
                      <NativeSelect
                        id={`${id}-provider`}
                        value={chosen?.provider ?? ""}
                        onChange={(event) =>
                          field.handleChange(event.target.value as MeetingTranscriber["provider"])
                        }
                      >
                        {transcribers.data.map((item) => (
                          <option key={item.provider} value={item.provider}>
                            {item.provider} · {item.model}
                          </option>
                        ))}
                      </NativeSelect>
                    </>
                  )}
                  {chosen && (
                    <FieldDescription>
                      <span className="flex flex-wrap items-center gap-3">
                        <span className="inline-flex items-center gap-1">
                          <Users className="size-3" aria-hidden="true" />
                          {chosen.diarizes ? ui("Tách được người nói") : ui("Không tách người nói")}
                        </span>
                        <span>
                          {ui("Tối đa {{size}} MB", { size: megabytes(chosen.maxBytes) })}
                        </span>
                      </span>
                    </FieldDescription>
                  )}
                </Field>
              )}
            </form.AppField>
            <form.AppField name="consent">
              {(field) => (
                <field.CheckboxField
                  label={ui("Những người trong bản ghi đã biết buổi họp được ghi lại.")}
                />
              )}
            </form.AppField>
          </FieldGroup>
        </fieldset>
        {tooLarge && chosen && (
          <FieldError>
            {ui("File {{size}} MB vượt giới hạn {{limit}} MB của {{provider}}.", {
              size: megabytes(file.size),
              limit: megabytes(chosen.maxBytes),
              provider: chosen.provider,
            })}
          </FieldError>
        )}
        {pending && (
          <div className="grid gap-1.5">
            <Progress value={percent} />
            <p role="status" className="text-xs text-content-muted">
              {ui("Đang tải lên… {{percent}}%", { percent: Math.round(percent) })}
            </p>
          </div>
        )}
        <form.AppForm>
          <form.FormError />
          <DialogFooter>
            <Button prominence="tertiary" onClick={onClose}>
              {ui("Huỷ")}
            </Button>
            <form.SubmitButton disabled={!file || tooLarge || nobody || !consent}>
              <FileAudio data-icon="inline-start" aria-hidden="true" />
              {ui("Tải lên và nhận dạng")}
            </form.SubmitButton>
          </DialogFooter>
        </form.AppForm>
      </form>
    </DialogContent>
  );
}

/** The transcriber the person picked, or the Tenant's selected one. */
function chosenOf(
  transcribers: MeetingTranscriber[] | undefined,
  provider: MeetingTranscriber["provider"] | undefined,
) {
  return (
    transcribers?.find((item) => item.provider === provider) ??
    transcribers?.find((item) => item.selected)
  );
}
