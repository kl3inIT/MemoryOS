import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { useStore } from "@tanstack/react-form";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, FileDown } from "lucide-react";
import { useAppForm, withForm } from "@/components/form/app-form";
import { useFieldValidity } from "@/components/form/form-context";
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
import { Field, FieldError, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";
import { NativeSelect } from "@/components/ui/native-select";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { LazyPdfView } from "@/features/preview/lazy-pdf-view";
import { PreviewCanvas, PreviewSkeleton } from "@/features/preview/preview-surface";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  exportMeetingMinutesMutation,
  getMeetingMinutesHeadingOptions,
  saveMeetingMinutesHeadingMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { exportMeetingMinutes } from "@/lib/hey-api/sdk.gen";
import type { MeetingHeading } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import {
  headingQueryKey,
  saveDocument,
  splitNames,
  vietnameseMoment,
  type MeetingDetail,
  type MeetingHeadingRequest,
} from "./meetings-api";
import { slug } from "./meeting-file-name";
import { useFailureText } from "./use-failure-text";

/**
 * Nghị định 30 asks for Times New Roman and a company follows it by convention, so it leads. Word only names the face;
 * the reader's own machine supplies it, which is why these are faces every office machine has.
 */
const TYPEFACES = ["Times New Roman", "Arial", "Calibri", "Tahoma"];

/** How long typing must pause before the page is drawn again, and before the heading is kept. */
const SETTLE_MS = 700;

/** The participants are offered as the owner types; Chrome's own dropdown arrow is hidden, as on every other field. */
const SUGGESTS = "[&::-webkit-calendar-picker-indicator]:hidden!";

/** Every heading field as the form edits it: plain text, with the attendees as one comma-separated line. */
type HeadingValues = Required<Omit<MeetingHeadingRequest, "attendees">> & { attendees: string };

/**
 * The biên bản as it will print, beside what the owner fills in: the heading the transcript cannot know, and the
 * summary, decisions and work the model wrote. The page on the right is the PDF the server renders, so what is seen is
 * what is downloaded (Mercury and Acctual invoices, Craft's export). Nghị định 30/2020 binds state bodies, so every
 * heading field stays the owner's to fill and an empty one prints as an ellipsis to write on.
 */
export function ExportMinutesDialog({
  meeting,
  open,
  onOpenChange,
}: {
  meeting: MeetingDetail;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const ui = useAppTranslation();
  const saved = useQuery(getMeetingMinutesHeadingOptions({ path: { meetingId: meeting.id } }));

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        aria-describedby={undefined}
        layout="flush"
        size="full"
        className="h-[calc(100dvh-2rem)]"
      >
        <DialogHeader>
          <DialogTitle>{ui("Biên bản cuộc họp")}</DialogTitle>
        </DialogHeader>
        {saved.data ? (
          <MinutesEditor
            meeting={meeting}
            initial={initialHeading(meeting, saved.data)}
            onClose={() => onOpenChange(false)}
          />
        ) : (
          <div className="flex min-h-0 flex-1">
            <PreviewCanvas>
              <PreviewSkeleton width={560} />
            </PreviewCanvas>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

function initialHeading(meeting: MeetingDetail, saved: MeetingHeading): HeadingValues {
  const text = (value: string | undefined) => value ?? "";
  if (saved.saved)
    return {
      organization: text(saved.organization),
      parentOrganization: text(saved.parentOrganization),
      number: text(saved.number),
      about: text(saved.about),
      place: text(saved.place),
      opened: text(saved.opened),
      closed: text(saved.closed),
      chair: text(saved.chair),
      chairRole: text(saved.chairRole),
      secretary: text(saved.secretary),
      secretaryRole: text(saved.secretaryRole),
      attendees: (saved.attendees ?? []).join(", "),
      font: saved.font || TYPEFACES[0]!,
    };
  // A meeting without a heading of its own starts from the organization and typeface of the last biên bản.
  return {
    organization: text(saved.organization),
    parentOrganization: text(saved.parentOrganization),
    number: "",
    about: meeting.title,
    place: "",
    opened: vietnameseMoment(meeting.createdAt),
    closed: meeting.endedAt ? vietnameseMoment(meeting.endedAt) : "",
    chair: "",
    chairRole: "",
    secretary: "",
    secretaryRole: "",
    attendees: meeting.participants.join(", "),
    font: saved.font || TYPEFACES[0]!,
  };
}

/** The heading as the API takes it. */
function requestOf({ attendees, ...heading }: HeadingValues): MeetingHeadingRequest {
  return { ...heading, attendees: splitNames(attendees) };
}

/**
 * The heading, saved and drawn again once the owner stops typing. Only the owner keeps a heading; a reader's edits
 * live as long as the dialog.
 */
function useHeadingPreview(meeting: MeetingDetail, values: HeadingValues, initial: HeadingValues) {
  const cache = useQueryClient();
  const settled = useDebouncedValue(JSON.stringify(requestOf(values)), SETTLE_MS);
  const lastSaved = useRef(JSON.stringify(requestOf(initial)));
  const keep = useMutation({
    ...saveMeetingMinutesHeadingMutation(),
    onSuccess: (stored) => cache.setQueryData(headingQueryKey(meeting.id), stored),
    onError: () => {
      lastSaved.current = "";
    },
  });
  const { mutate } = keep;
  useEffect(() => {
    if (!meeting.owned || settled === lastSaved.current) return;
    lastSaved.current = settled;
    mutate({
      path: { meetingId: meeting.id },
      body: JSON.parse(settled) as MeetingHeadingRequest,
    });
  }, [mutate, meeting.id, meeting.owned, settled]);

  // The page is drawn again once the owner stops typing, and whenever the minutes themselves change; the last page
  // stays up while the next one is drawn.
  return useQuery({
    queryKey: [...headingQueryKey(meeting.id), "preview", settled, JSON.stringify(meeting.minutes)],
    queryFn: async ({ signal }) =>
      (
        await exportMeetingMinutes({
          path: { meetingId: meeting.id },
          query: { format: "PDF" },
          body: JSON.parse(settled) as MeetingHeadingRequest,
          signal,
        })
      ).data,
    placeholderData: keepPreviousData,
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });
}

function MinutesEditor({
  meeting,
  initial,
  onClose,
}: {
  meeting: MeetingDetail;
  initial: HeadingValues;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const failureText = useFailureText();
  const form = useAppForm({ defaultValues: initial });
  const values = useStore(form.store, (state) => state.values);
  const preview = useHeadingPreview(meeting, values, initial);
  const download = useMutation({
    ...exportMeetingMinutesMutation(),
    onSuccess: (document, { query }) => {
      saveDocument(
        document,
        `bien-ban-${slug(meeting.title)}.${query?.format === "PDF" ? "pdf" : "docx"}`,
      );
      onClose();
    },
  });
  const take = (format: "DOCX" | "PDF") =>
    download.mutate({
      path: { meetingId: meeting.id },
      query: { format },
      body: requestOf(values),
    });

  return (
    <>
      <div className="flex min-h-0 flex-1 flex-col md:flex-row">
        <div className="min-h-0 flex-1 overflow-y-auto border-b border-border-subtle px-6 py-5 md:w-md md:flex-none md:border-r md:border-b-0">
          <HeadingFields meeting={meeting} form={form} organization={initial.organization} />
        </div>
        <PreviewPane preview={preview} />
      </div>
      <DialogFooter className="items-center">
        {download.isError && (
          <p role="alert" className="mr-auto text-sm text-status-danger-content">
            {failureText(download.error)}
          </p>
        )}
        <Button prominence="tertiary" onClick={onClose}>
          {ui("Đóng")}
        </Button>
        <Button
          prominence="secondary"
          pending={download.isPending && download.variables.query?.format === "PDF"}
          disabled={download.isPending}
          onClick={() => take("PDF")}
        >
          {ui("Tải PDF")}
        </Button>
        <Button
          pending={download.isPending && download.variables.query?.format === "DOCX"}
          disabled={download.isPending}
          onClick={() => take("DOCX")}
        >
          <FileDown data-icon="inline-start" aria-hidden="true" />
          {ui("Tải Word")}
        </Button>
      </DialogFooter>
    </>
  );
}

/** What the owner fills in: the meeting, the people, the issuing body and the typeface. */
const HeadingFields = withForm({
  defaultValues: {} as HeadingValues,
  props: {} as {
    meeting: MeetingDetail;
    /** The issuing body the heading opened with; without one its fields start open. */
    organization: string;
  },
  render: function HeadingFieldsRender({ form, meeting, organization }) {
    const ui = useAppTranslation();
    const people = `${useId()}-people`;
    const organizationNow = useStore(form.store, (state) => state.values.organization);
    const number = useStore(form.store, (state) => state.values.number);
    return (
      <div className="grid gap-7">
        <datalist id={people}>
          {meeting.participants.map((person) => (
            <option key={person} value={person}>
              {person}
            </option>
          ))}
        </datalist>
        <FieldSet>
          <FieldLegend variant="label">{ui("Buổi họp")}</FieldLegend>
          <form.AppField name="about">
            {(field) => <field.TextField label={ui("Về việc")} maxLength={500} />}
          </form.AppField>
          <form.AppField name="place">
            {(field) => (
              <field.TextField
                label={ui("Địa điểm")}
                maxLength={200}
                placeholder={ui("Phòng họp A, Hà Nội")}
              />
            )}
          </form.AppField>
          <form.AppField name="opened">
            {(field) => <field.TextField label={ui("Bắt đầu")} maxLength={200} />}
          </form.AppField>
          <form.AppField name="closed">
            {(field) => <field.TextField label={ui("Kết thúc")} maxLength={200} />}
          </form.AppField>
        </FieldSet>
        <FieldSet>
          <FieldLegend variant="label">{ui("Thành phần")}</FieldLegend>
          <div className="grid gap-4 sm:grid-cols-2">
            <form.AppField name="chair">
              {(field) => (
                <field.TextField
                  label={ui("Chủ trì")}
                  maxLength={200}
                  list={people}
                  className={SUGGESTS}
                />
              )}
            </form.AppField>
            <form.AppField name="chairRole">
              {(field) => <field.TextField label={ui("Chức vụ")} maxLength={200} />}
            </form.AppField>
            <form.AppField name="secretary">
              {(field) => (
                <field.TextField
                  label={ui("Thư ký")}
                  maxLength={200}
                  list={people}
                  className={SUGGESTS}
                />
              )}
            </form.AppField>
            <form.AppField name="secretaryRole">
              {(field) => <field.TextField label={ui("Chức vụ")} maxLength={200} />}
            </form.AppField>
          </div>
          <form.AppField name="attendees">
            {(field) => (
              <field.TextField
                label={ui("Người dự")}
                placeholder={ui("Tên người dự, cách nhau bằng dấu phẩy")}
              />
            )}
          </form.AppField>
        </FieldSet>
        <Collapsible defaultOpen={!organization}>
          <CollapsibleTrigger asChild>
            <Button prominence="tertiary" size="sm" className="-ml-2 max-w-full justify-start">
              <ChevronDown
                data-icon="inline-start"
                aria-hidden="true"
                className="transition-transform in-data-[state=open]:rotate-180"
              />
              <span className="shrink-0">{ui("Đơn vị ban hành")}</span>
              <span className="truncate font-secondary-body text-content-muted">
                {[organizationNow, number && ui("Số {{number}}", { number })]
                  .filter(Boolean)
                  .join(" · ")}
              </span>
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <div className="grid gap-4 pt-4">
              <form.AppField name="organization">
                {(field) => (
                  <field.TextField
                    label={ui("Cơ quan, tổ chức")}
                    maxLength={200}
                    placeholder={ui("CÔNG TY CỔ PHẦN TASCO")}
                  />
                )}
              </form.AppField>
              <form.AppField name="parentOrganization">
                {(field) => <field.TextField label={ui("Cơ quan cấp trên")} maxLength={200} />}
              </form.AppField>
              <form.AppField name="number">
                {(field) => (
                  <field.TextField label={ui("Số biên bản")} maxLength={100} placeholder="12" />
                )}
              </form.AppField>
            </div>
          </CollapsibleContent>
        </Collapsible>
        <FieldSet>
          <FieldLegend variant="label">{ui("Trình bày")}</FieldLegend>
          <form.AppField name="font">
            {() => <TypefaceField label={ui("Phông chữ")} />}
          </form.AppField>
        </FieldSet>
      </div>
    );
  },
});

/** The typeface the biên bản prints in. */
function TypefaceField({ label }: { label: string }) {
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel htmlFor={field.name}>{label}</FieldLabel>
      <NativeSelect
        id={field.name}
        name={field.name}
        value={field.state.value}
        onBlur={field.handleBlur}
        onChange={(event) => field.handleChange(event.target.value)}
      >
        {TYPEFACES.map((font) => (
          <option key={font} value={font}>
            {font}
          </option>
        ))}
      </NativeSelect>
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}

/** The page as the server renders it; the page on screen stays until the next one is drawn beneath it. */
function PreviewPane({ preview }: { preview: ReturnType<typeof useHeadingPreview> }) {
  const ui = useAppTranslation();
  const [shown, setShown] = useState<{ document: Blob; at: number }>();
  const drawing =
    preview.data && preview.dataUpdatedAt !== shown?.at
      ? { document: preview.data, at: preview.dataUpdatedAt }
      : undefined;
  return (
    <div className="relative flex min-h-0 flex-1 flex-col overflow-hidden bg-surface-base">
      {[shown, drawing].map(
        (layer) =>
          layer && (
            <div
              key={layer.at}
              aria-hidden={layer === drawing && shown ? true : undefined}
              className={cn(
                "flex min-h-0 flex-1 flex-col",
                layer === drawing && shown && "invisible absolute inset-0",
              )}
            >
              <LazyPdfView
                skeletonWidth={560}
                url={layer.document}
                pages={[1]}
                boxes={[]}
                onReady={layer === drawing ? () => setShown(layer) : undefined}
              />
            </div>
          ),
      )}
      {shown || drawing ? null : (
        <PreviewCanvas>
          {preview.isError ? (
            <Alert variant="destructive">
              <AlertTitle>
                {ui("Chưa dựng được bản xem trước. Bạn vẫn tải được biên bản.")}
              </AlertTitle>
            </Alert>
          ) : (
            <PreviewSkeleton width={560} />
          )}
        </PreviewCanvas>
      )}
      <Updating visible={(preview.isFetching || !!drawing) && !!shown}>
        {ui("Đang cập nhật…")}
      </Updating>
    </div>
  );
}

function Updating({ visible, children }: { visible: boolean; children: ReactNode }) {
  return (
    <p
      role="status"
      className={cn(
        "pointer-events-none absolute top-3 right-4 rounded-full bg-surface-overlay px-2.5 py-1 font-secondary-body text-content-muted shadow-sm transition-opacity",
        visible ? "opacity-100" : "opacity-0",
      )}
    >
      {children}
    </p>
  );
}
