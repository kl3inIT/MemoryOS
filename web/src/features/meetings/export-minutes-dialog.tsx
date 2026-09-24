import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, FileDown } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { PdfView } from "@/features/preview/pdf-view";
import { PreviewCanvas, PreviewSkeleton } from "@/features/preview/preview-surface";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { cn } from "@/lib/utils";
import {
  exportMinutes,
  loadMinutesHeading,
  meetingHeadingKey,
  saveMinutesHeading,
  vietnameseMoment,
  type MeetingDetail,
  type MeetingHeadingRequest,
} from "./meetings-api";
import { slug } from "./meeting-file-name";

/**
 * Nghị định 30 asks for Times New Roman and a company follows it by convention, so it leads. Word only names the face;
 * the reader's own machine supplies it, which is why these are faces every office machine has.
 */
const TYPEFACES = ["Times New Roman", "Arial", "Calibri", "Tahoma"];

/** How long typing must pause before the page is drawn again, and before the heading is kept. */
const SETTLE_MS = 700;

/** The participants are offered as the owner types; Chrome's own dropdown arrow is hidden, as on every other field. */
const SUGGESTS = "[&::-webkit-calendar-picker-indicator]:hidden!";

/** Saves the returned document through a temporary object URL, as the file preview does. */
function save(document: Blob, name: string) {
  const url = URL.createObjectURL(document);
  const link = Object.assign(window.document.createElement("a"), { href: url, download: name });
  window.document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/** Splits "Anh Thanh, Chị Lan" into names, as the start form does. */
function names(value: string) {
  return value
    .split(/[,;\n]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

/** The value after it has stopped changing for `delay` milliseconds. */
function useSettled<T>(value: T, delay: number) {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setSettled(value), delay);
    return () => window.clearTimeout(timer);
  }, [value, delay]);
  return settled;
}

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
  const saved = useQuery({
    queryKey: meetingHeadingKey(meeting.id),
    queryFn: ({ signal }) => loadMinutesHeading(meeting.id, signal),
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        aria-describedby={undefined}
        className="flex h-[calc(100dvh-2rem)] flex-col gap-0 overflow-clip p-0 sm:h-[calc(100dvh-3rem)] sm:w-[calc(100vw-3rem)] sm:max-w-[88rem]"
      >
        <DialogHeader className="border-border-subtle border-b px-6 py-4">
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

function initialHeading(
  meeting: MeetingDetail,
  saved: MeetingHeadingRequest & { saved: boolean },
): MeetingHeadingRequest {
  if (saved.saved) {
    const { saved: _saved, ...heading } = saved;
    return { ...heading, font: heading.font || TYPEFACES[0] };
  }
  // A meeting without a heading of its own starts from the organization and typeface of the last biên bản.
  return {
    organization: saved.organization,
    parentOrganization: saved.parentOrganization,
    number: "",
    about: meeting.title,
    place: "",
    opened: vietnameseMoment(meeting.createdAt),
    closed: meeting.endedAt ? vietnameseMoment(meeting.endedAt) : "",
    chair: "",
    chairRole: "",
    secretary: "",
    secretaryRole: "",
    attendees: meeting.participants,
    font: saved.font || TYPEFACES[0],
  };
}

function MinutesEditor({
  meeting,
  initial,
  onClose,
}: {
  meeting: MeetingDetail;
  initial: MeetingHeadingRequest;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const id = useId();
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const [heading, setHeading] = useState(initial);
  const [attendees, setAttendees] = useState((initial.attendees ?? []).join(", "));
  const [pending, setPending] = useState<"DOCX" | "PDF">();
  const [error, setError] = useState<string>();
  const request: MeetingHeadingRequest = { ...heading, attendees: names(attendees) };
  const settled = useSettled(JSON.stringify(request), SETTLE_MS);
  const lastSaved = useRef(JSON.stringify(initial));

  // The page is drawn again once the owner stops typing, and whenever the minutes themselves change; the last page
  // stays up while the next one is drawn.
  const preview = useQuery({
    queryKey: [
      ...meetingHeadingKey(meeting.id),
      "preview",
      settled,
      JSON.stringify(meeting.minutes),
    ],
    queryFn: () => exportMinutes(meeting.id, JSON.parse(settled) as MeetingHeadingRequest, "PDF"),
    placeholderData: keepPreviousData,
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });
  // The page on screen stays until the next one is drawn beneath it, so a redraw never flashes an empty sheet.
  const [shown, setShown] = useState<{ document: Blob; at: number }>();
  const drawing =
    preview.data && preview.dataUpdatedAt !== shown?.at
      ? { document: preview.data, at: preview.dataUpdatedAt }
      : undefined;

  // Only the owner keeps a heading; a reader's edits live as long as the dialog.
  useEffect(() => {
    if (!meeting.owned || settled === lastSaved.current) return;
    lastSaved.current = settled;
    void saveMinutesHeading(meeting.id, JSON.parse(settled) as MeetingHeadingRequest)
      .then((stored) => cache.setQueryData(meetingHeadingKey(meeting.id), stored))
      .catch(() => {
        lastSaved.current = "";
      });
  }, [cache, meeting.id, meeting.owned, settled]);

  function field(key: Exclude<keyof MeetingHeadingRequest, "attendees">) {
    return {
      id: `${id}-${key}`,
      value: String(heading[key] ?? ""),
      onChange: (event: { target: { value: string } }) =>
        setHeading((value) => ({ ...value, [key]: event.target.value })),
    };
  }

  async function download(format: "DOCX" | "PDF") {
    if (pending) return;
    setPending(format);
    setError(undefined);
    try {
      save(
        await exportMinutes(meeting.id, request, format),
        `bien-ban-${slug(meeting.title)}.${format === "PDF" ? "pdf" : "docx"}`,
      );
      onClose();
    } catch (failed) {
      setError(problemMessage(presentProblem(failed, "mutation").message));
    } finally {
      setPending(undefined);
    }
  }

  const people = `${id}-people`;

  return (
    <>
      <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)] grid-rows-[minmax(0,1fr)_minmax(0,1fr)] md:grid-cols-[minmax(22rem,28rem)_1fr] md:grid-rows-1">
        <div className="min-h-0 overflow-y-auto border-border-subtle border-b px-6 py-5 md:border-r md:border-b-0">
          <datalist id={people}>
            {meeting.participants.map((person) => (
              <option key={person} value={person} />
            ))}
          </datalist>
          <div className="grid gap-7">
            <Section title={ui("Buổi họp")}>
              <Field label={ui("Về việc")} htmlFor={`${id}-about`}>
                <Input maxLength={500} {...field("about")} />
              </Field>
              <Field label={ui("Địa điểm")} htmlFor={`${id}-place`}>
                <Input
                  maxLength={200}
                  placeholder={ui("Phòng họp A, Hà Nội")}
                  {...field("place")}
                />
              </Field>
              <Field label={ui("Bắt đầu")} htmlFor={`${id}-opened`}>
                <Input maxLength={200} {...field("opened")} />
              </Field>
              <Field label={ui("Kết thúc")} htmlFor={`${id}-closed`}>
                <Input maxLength={200} {...field("closed")} />
              </Field>
            </Section>
            <Section title={ui("Thành phần")}>
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label={ui("Chủ trì")} htmlFor={`${id}-chair`}>
                  <Input maxLength={200} list={people} className={SUGGESTS} {...field("chair")} />
                </Field>
                <Field label={ui("Chức vụ")} htmlFor={`${id}-chairRole`}>
                  <Input maxLength={200} {...field("chairRole")} />
                </Field>
                <Field label={ui("Thư ký")} htmlFor={`${id}-secretary`}>
                  <Input
                    maxLength={200}
                    list={people}
                    className={SUGGESTS}
                    {...field("secretary")}
                  />
                </Field>
                <Field label={ui("Chức vụ")} htmlFor={`${id}-secretaryRole`}>
                  <Input maxLength={200} {...field("secretaryRole")} />
                </Field>
              </div>
              <Field label={ui("Người dự")} htmlFor={`${id}-attendees`}>
                <Input
                  id={`${id}-attendees`}
                  value={attendees}
                  placeholder={ui("Tên người dự, cách nhau bằng dấu phẩy")}
                  onChange={(event) => setAttendees(event.target.value)}
                />
              </Field>
            </Section>
            <Collapsible defaultOpen={!initial.organization} className="group grid gap-4">
              <CollapsibleTrigger className="flex min-w-0 cursor-pointer items-center gap-2 text-left focus-visible:outline-2 focus-visible:outline-focus-ring">
                <ChevronDown
                  aria-hidden="true"
                  className="size-4 shrink-0 transition-transform group-has-[[data-state=open]]:rotate-180"
                />
                <span className="font-main-ui-action text-content-primary">
                  {ui("Đơn vị ban hành")}
                </span>
                <span className="truncate font-secondary-body text-content-muted">
                  {[
                    heading.organization,
                    heading.number && ui("Số {{number}}", { number: heading.number }),
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
              </CollapsibleTrigger>
              <CollapsibleContent className="grid gap-4">
                <Field label={ui("Cơ quan, tổ chức")} htmlFor={`${id}-organization`}>
                  <Input
                    maxLength={200}
                    placeholder={ui("CÔNG TY CỔ PHẦN TASCO")}
                    {...field("organization")}
                  />
                </Field>
                <Field label={ui("Cơ quan cấp trên")} htmlFor={`${id}-parentOrganization`}>
                  <Input maxLength={200} {...field("parentOrganization")} />
                </Field>
                <Field label={ui("Số biên bản")} htmlFor={`${id}-number`}>
                  <Input maxLength={100} placeholder="12" {...field("number")} />
                </Field>
              </CollapsibleContent>
            </Collapsible>
            <Section title={ui("Trình bày")}>
              <Field label={ui("Phông chữ")} htmlFor={`${id}-font`}>
                <Select {...field("font")}>
                  {TYPEFACES.map((font) => (
                    <option key={font} value={font}>
                      {font}
                    </option>
                  ))}
                </Select>
              </Field>
            </Section>
          </div>
        </div>
        <div className="relative flex min-h-0 flex-col overflow-hidden bg-surface-base">
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
                  <PdfView
                    url={layer.document}
                    pages={[1]}
                    boxes={[]}
                    onReady={layer === drawing ? () => setShown(layer) : undefined}
                  />
                </div>
              ),
          )}
          {shown || drawing ? null : preview.isError ? (
            <PreviewCanvas>
              <p
                role="alert"
                className="rounded-xl bg-status-danger-surface p-4 text-status-danger-content"
              >
                {ui("Chưa dựng được bản xem trước. Bạn vẫn tải được biên bản.")}
              </p>
            </PreviewCanvas>
          ) : (
            <PreviewCanvas>
              <PreviewSkeleton width={560} />
            </PreviewCanvas>
          )}
          <p
            role="status"
            className={cn(
              "pointer-events-none absolute top-3 right-4 rounded-full bg-surface-overlay px-2.5 py-1 font-secondary-body text-content-muted shadow-sm transition-opacity",
              (preview.isFetching || drawing) && shown ? "opacity-100" : "opacity-0",
            )}
          >
            {ui("Đang cập nhật…")}
          </p>
        </div>
      </div>
      <DialogFooter className="m-0 items-center">
        {error && (
          <p role="alert" className="mr-auto text-sm text-status-danger-content">
            {error}
          </p>
        )}
        <Button type="button" prominence="tertiary" onClick={onClose}>
          {ui("Đóng")}
        </Button>
        <Button
          type="button"
          prominence="secondary"
          pending={pending === "PDF"}
          disabled={!!pending}
          onClick={() => void download("PDF")}
        >
          {ui("Tải PDF")}
        </Button>
        <Button
          type="button"
          pending={pending === "DOCX"}
          disabled={!!pending}
          onClick={() => void download("DOCX")}
        >
          <FileDown aria-hidden="true" />
          {ui("Tải Word")}
        </Button>
      </DialogFooter>
    </>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="grid gap-4">
      <h3 className="font-main-ui-action text-content-primary">{title}</h3>
      {children}
    </section>
  );
}

function Field({
  label,
  htmlFor,
  children,
}: {
  label: string;
  htmlFor: string;
  children: ReactNode;
}) {
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
    </div>
  );
}
