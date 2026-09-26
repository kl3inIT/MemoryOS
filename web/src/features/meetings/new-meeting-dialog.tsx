import { useId, useMemo, useState } from "react";
import { useStore } from "@tanstack/react-form";
import { useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { ChevronDown, Copy, MonitorSpeaker, Mic } from "lucide-react";
import { useAppForm, setServerErrors, useProblemErrors } from "@/components/form/app-form";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
  FieldTitle,
} from "@/components/ui/field";
import { NativeSelect } from "@/components/ui/native-select";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { captureSupport, openMeetingSources, ShareCancelledError } from "./meeting-capture";
import { startRecording } from "./meeting-session";
import { MeetingShareField } from "./meeting-share-field";
import { splitNames, type MeetingAudience, type MeetingKind } from "./meetings-api";
import { useCreateMeeting } from "./use-create-meeting";

type MeetingLanguage = "vi" | "en" | "auto";

/** The language is chosen before every recording and locked once it starts, so the last choice is kept (Fireflies). */
const LANGUAGE_KEY = "memoryos.meeting.language";

function rememberedLanguage(): MeetingLanguage {
  try {
    const stored = localStorage.getItem(LANGUAGE_KEY);
    if (stored === "vi" || stored === "en" || stored === "auto") return stored;
  } catch {
    // Storage can be unavailable; Vietnamese is the default.
  }
  return "vi";
}

function rememberLanguage(language: MeetingLanguage) {
  try {
    localStorage.setItem(LANGUAGE_KEY, language);
  } catch {
    // A private window keeps the choice for this meeting only.
  }
}

export function NewMeetingDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* Mounted per opening, so each recording starts from an empty form. */}
      {open && <NewMeetingForm onClose={() => onOpenChange(false)} />}
    </Dialog>
  );
}

function NewMeetingForm({ onClose }: { onClose: () => void }) {
  const ui = useAppTranslation();
  const id = useId();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const problemErrors = useProblemErrors();
  const { create, publish } = useCreateMeeting();
  const support = useMemo(() => captureSupport(), []);
  const [copied, setCopied] = useState(false);
  const notice = ui(
    "Buổi họp hôm nay được ghi lại thành văn bản trên MemoryOS để làm biên bản. Nếu ai không đồng ý, xin báo ngay.",
  );
  const audience: MeetingAudience = { people: [], groups: [] };
  const form = useAppForm({
    defaultValues: {
      kind: (support === "sharedAudio" ? "ONLINE" : "IN_PERSON") as MeetingKind,
      language: rememberedLanguage(),
      title: "",
      participants: "",
      terms: "",
      audience,
      consent: false,
    },
    onSubmit: async ({ value, formApi }) => {
      if (!value.consent) return;
      let sources: Awaited<ReturnType<typeof openMeetingSources>> | undefined;
      try {
        // Permissions first, while the click still counts as the person's action.
        sources = await openMeetingSources(value.kind);
        const meeting = await create({
          title:
            value.title.trim() || ui("Cuộc họp {{date}}", { date: new Date().toLocaleString() }),
          kind: value.kind,
          language: value.language === "auto" ? undefined : value.language,
          participants: splitNames(value.participants),
          terms: splitNames(value.terms),
        });
        await publish(meeting, value.audience);
        await startRecording(
          meeting.id,
          [
            { track: "MIC", stream: sources.microphone, offsetMs: 0 },
            ...(sources.tab ? [{ track: "TAB" as const, stream: sources.tab, offsetMs: 0 }] : []),
          ],
          cache,
        );
        onClose();
        await navigate({
          to: "/meetings/$meetingId",
          params: { meetingId: meeting.id },
          search: sources.tab || value.kind === "IN_PERSON" ? {} : { tabAudio: "missing" as const },
        });
      } catch (failed) {
        for (const stream of [sources?.microphone, sources?.tab])
          for (const track of stream?.getTracks() ?? []) track.stop();
        setServerErrors(
          formApi,
          failed instanceof ShareCancelledError
            ? { form: ui("Bạn chưa chọn tab cuộc họp nên chưa bắt đầu ghi."), fields: {} }
            : failed instanceof DOMException
              ? {
                  form: ui("Trình duyệt không cho dùng micro. Hãy cho phép micro rồi thử lại."),
                  fields: {},
                }
              : problemErrors(failed),
        );
      }
    },
  });
  const submitting = useStore(form.store, (state) => state.isSubmitting);

  async function copyNotice() {
    try {
      await navigator.clipboard.writeText(notice);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <DialogContent
      className="sm:max-w-xl"
      aria-describedby={undefined}
      showCloseButton={false}
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
          <DialogTitle>{ui("Ghi cuộc họp mới")}</DialogTitle>
        </DialogHeader>
        {support === "unsupported" && (
          <Alert variant="warning" role="status">
            <AlertTitle>
              {ui(
                "Trình duyệt này chưa ghi được. Hãy dùng Chrome hoặc Edge trên máy tính, hoặc Chrome trên điện thoại.",
              )}
            </AlertTitle>
          </Alert>
        )}
        <fieldset disabled={submitting || support === "unsupported"} className="min-w-0">
          <FieldGroup>
            <form.AppField name="kind">
              {(field) => (
                <KindField
                  value={field.state.value}
                  onChange={field.handleChange}
                  online={support === "sharedAudio"}
                />
              )}
            </form.AppField>
            <form.AppField name="language">
              {(field) => (
                <Field>
                  <FieldLabel htmlFor={`${id}-language`}>{ui("Ngôn ngữ")}</FieldLabel>
                  <NativeSelect
                    id={`${id}-language`}
                    value={field.state.value}
                    aria-describedby={`${id}-language-hint`}
                    onChange={(event) => {
                      const language = event.target.value as MeetingLanguage;
                      field.handleChange(language);
                      rememberLanguage(language);
                    }}
                  >
                    <option value="vi">{ui("Tiếng Việt")}</option>
                    <option value="auto">{ui("Tiếng Việt xen tiếng Anh")}</option>
                    <option value="en">{ui("Tiếng Anh")}</option>
                  </NativeSelect>
                  <FieldDescription id={`${id}-language-hint`}>
                    {ui("Không đổi được sau khi bắt đầu ghi.")}
                  </FieldDescription>
                </Field>
              )}
            </form.AppField>
            <Collapsible>
              <CollapsibleTrigger asChild>
                <Button prominence="tertiary" size="sm" className="-ml-2">
                  <ChevronDown
                    data-icon="inline-start"
                    aria-hidden="true"
                    className="transition-transform in-data-[state=open]:rotate-180"
                  />
                  {ui("Thêm chi tiết")}
                </Button>
              </CollapsibleTrigger>
              <CollapsibleContent>
                <div className="grid gap-4 pt-3">
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
                  <form.AppField name="terms">
                    {(field) => (
                      <field.TextField
                        label={ui("Thuật ngữ riêng")}
                        placeholder={ui("Tên riêng, từ viết tắt, thuật ngữ")}
                      />
                    )}
                  </form.AppField>
                  <form.AppField name="audience">
                    {(field) => (
                      <MeetingShareField
                        label={ui("Chia sẻ với")}
                        value={field.state.value}
                        disabled={submitting}
                        onChange={field.handleChange}
                      />
                    )}
                  </form.AppField>
                </div>
              </CollapsibleContent>
            </Collapsible>
            <form.AppField name="consent">
              {(field) => (
                <field.CheckboxField
                  label={ui("Tôi đã thông báo cho mọi người rằng buổi họp được ghi lại.")}
                >
                  <Button size="sm" prominence="secondary" onClick={() => void copyNotice()}>
                    <Copy data-icon="inline-start" aria-hidden="true" />
                    {copied ? ui("Đã sao chép") : ui("Câu thông báo")}
                  </Button>
                </field.CheckboxField>
              )}
            </form.AppField>
          </FieldGroup>
        </fieldset>
        <form.AppForm>
          <form.FormError />
          <DialogFooter>
            <Button prominence="secondary" disabled={submitting} onClick={onClose}>
              {ui("Huỷ")}
            </Button>
            <form.Subscribe selector={(state) => state.values.consent}>
              {(consent) => (
                <form.SubmitButton disabled={!consent || support === "unsupported"}>
                  <Mic data-icon="inline-start" aria-hidden="true" />
                  {ui("Bắt đầu ghi")}
                </form.SubmitButton>
              )}
            </form.Subscribe>
          </DialogFooter>
        </form.AppForm>
      </form>
    </DialogContent>
  );
}

/** Online records the meeting tab beside the microphone, which only a browser that shares tab audio can do. */
function KindField({
  value,
  onChange,
  online,
}: {
  value: MeetingKind;
  onChange: (kind: MeetingKind) => void;
  online: boolean;
}) {
  const ui = useAppTranslation();
  const id = useId();
  return (
    <FieldSet>
      <FieldLegend variant="label">{ui("Hình thức")}</FieldLegend>
      <RadioGroup
        value={value}
        onValueChange={(next) => onChange(next as MeetingKind)}
        className="sm:grid-cols-2"
      >
        <FieldLabel htmlFor={`${id}-online`}>
          <Field orientation="horizontal">
            <RadioGroupItem id={`${id}-online`} value="ONLINE" disabled={!online} />
            <FieldContent>
              <FieldTitle>
                <MonitorSpeaker className="size-4" aria-hidden="true" />
                {ui("Họp online")}
              </FieldTitle>
            </FieldContent>
          </Field>
        </FieldLabel>
        <FieldLabel htmlFor={`${id}-in-person`}>
          <Field orientation="horizontal">
            <RadioGroupItem id={`${id}-in-person`} value="IN_PERSON" />
            <FieldContent>
              <FieldTitle>
                <Mic className="size-4" aria-hidden="true" />
                {ui("Họp trực tiếp")}
              </FieldTitle>
            </FieldContent>
          </Field>
        </FieldLabel>
      </RadioGroup>
      {value === "ONLINE" && (
        <FieldDescription>
          {ui("Nhớ bật “Chia sẻ cả âm thanh của thẻ”, và nên đeo tai nghe.")}
        </FieldDescription>
      )}
    </FieldSet>
  );
}
