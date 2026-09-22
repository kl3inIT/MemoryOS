import { useId, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/select";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import {
  exportMinutes,
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

/** Saves the returned document through a temporary object URL, as the file preview does. */
function save(document: Blob, name: string) {
  const url = URL.createObjectURL(document);
  const link = Object.assign(window.document.createElement("a"), { href: url, download: name });
  window.document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/**
 * The heading of a biên bản: what the transcript cannot know. Nghị định 30/2020 binds state bodies, so every field
 * stays the owner's to fill and an empty one is printed as an ellipsis to write on.
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
  const id = useId();
  const problemMessage = useProblemMessage();
  const [heading, setHeading] = useState<MeetingHeadingRequest>(() => ({
    organization: "",
    parentOrganization: "",
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
    font: TYPEFACES[0],
  }));
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();

  function field(key: keyof MeetingHeadingRequest) {
    return {
      id: `${id}-${key}`,
      value: String(heading[key] ?? ""),
      onChange: (event: { target: { value: string } }) =>
        setHeading((current) => ({ ...current, [key]: event.target.value })),
    };
  }

  async function download(event: FormEvent) {
    event.preventDefault();
    if (pending) return;
    setPending(true);
    setError(undefined);
    try {
      save(await exportMinutes(meeting.id, heading), `bien-ban-${slug(meeting.title)}.docx`);
      onOpenChange(false);
    } catch (failed) {
      setError(problemMessage(presentProblem(failed, "mutation").message));
    } finally {
      setPending(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !pending && onOpenChange(next)}>
      <DialogContent className="sm:max-w-xl" aria-describedby={undefined}>
        <form onSubmit={(event) => void download(event)} className="grid gap-5">
          <DialogHeader>
            <DialogTitle>{ui("Xuất biên bản")}</DialogTitle>
          </DialogHeader>
          <fieldset disabled={pending} className="grid gap-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-organization`}>{ui("Cơ quan, tổ chức")}</Label>
                <Input
                  maxLength={200}
                  placeholder={ui("CÔNG TY CỔ PHẦN TASCO")}
                  {...field("organization")}
                />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-number`}>{ui("Số biên bản")}</Label>
                <Input maxLength={100} placeholder="12" {...field("number")} />
              </div>
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-about`}>{ui("Về việc")}</Label>
              <Input maxLength={500} {...field("about")} />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-place`}>{ui("Địa điểm")}</Label>
              <Input maxLength={200} placeholder={ui("Phòng họp A, Hà Nội")} {...field("place")} />
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-opened`}>{ui("Bắt đầu")}</Label>
                <Input maxLength={200} {...field("opened")} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-closed`}>{ui("Kết thúc")}</Label>
                <Input maxLength={200} {...field("closed")} />
              </div>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-chair`}>{ui("Chủ trì")}</Label>
                <Input maxLength={200} {...field("chair")} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-chairRole`}>{ui("Chức vụ chủ trì")}</Label>
                <Input maxLength={200} {...field("chairRole")} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-secretary`}>{ui("Thư ký")}</Label>
                <Input maxLength={200} {...field("secretary")} />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-secretaryRole`}>{ui("Chức vụ thư ký")}</Label>
                <Input maxLength={200} {...field("secretaryRole")} />
              </div>
            </div>
          </fieldset>
          <div className="grid max-w-64 gap-1.5">
            <Label htmlFor={`${id}-font`}>{ui("Phông chữ")}</Label>
            <Select {...field("font")}>
              {TYPEFACES.map((font) => (
                <option key={font} value={font}>
                  {font}
                </option>
              ))}
            </Select>
          </div>
          {error && (
            <p role="alert" className="text-sm text-status-danger-content">
              {error}
            </p>
          )}
          <DialogFooter>
            <Button type="button" prominence="tertiary" onClick={() => onOpenChange(false)}>
              {ui("Huỷ")}
            </Button>
            <Button type="submit" pending={pending}>
              {ui("Tải về")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
