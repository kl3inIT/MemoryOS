import { useId, useMemo, useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { Copy, MonitorSpeaker, Mic } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Select } from "@/components/ui/select";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { captureSupport, openMeetingSources, ShareCancelledError } from "./meeting-capture";
import { startRecording } from "./meeting-session";
import { MeetingShareField, type MeetingAudience } from "./meeting-share-field";
import {
  meetingKey,
  meetingsKey,
  shareMeeting,
  startMeeting,
  type MeetingKind,
} from "./meetings-api";

/** Splits "Anh Thanh, Chị Lan" into names; the server trims and de-duplicates again. */
function list(value: string) {
  return value
    .split(/[,;\n]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

export function NewMeetingDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const ui = useAppTranslation();
  const id = useId();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const problemMessage = useProblemMessage();
  const support = useMemo(() => captureSupport(), []);
  const [title, setTitle] = useState("");
  const [kind, setKind] = useState<MeetingKind>(support === "sharedAudio" ? "ONLINE" : "IN_PERSON");
  const [language, setLanguage] = useState<"vi" | "en" | "auto">("vi");
  const [participants, setParticipants] = useState("");
  const [terms, setTerms] = useState("");
  const [audience, setAudience] = useState<MeetingAudience>({ people: [], groups: [] });
  const [consent, setConsent] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();
  const [copied, setCopied] = useState(false);
  const notice = ui(
    "Buổi họp hôm nay được ghi lại thành văn bản trên MemoryOS để làm biên bản. Nếu ai không đồng ý, xin báo ngay.",
  );

  async function start(event: FormEvent) {
    event.preventDefault();
    if (!consent || pending) return;
    setPending(true);
    setError(undefined);
    let sources: Awaited<ReturnType<typeof openMeetingSources>> | undefined;
    try {
      // Permissions first, while the click still counts as the person's action.
      sources = await openMeetingSources(kind);
      const meeting = await startMeeting({
        title: title.trim() || ui("Cuộc họp {{date}}", { date: new Date().toLocaleString() }),
        kind,
        language: language === "auto" ? undefined : language,
        participants: list(participants),
        terms: list(terms),
      });
      const shared =
        audience.people.length > 0 || audience.groups.length > 0
          ? await shareMeeting(
              meeting.id,
              audience.people.map((person) => person.actorId),
              audience.groups.map((group) => group.id),
            )
          : meeting;
      cache.setQueryData(meetingKey(shared.id), shared);
      void cache.invalidateQueries({ queryKey: meetingsKey, exact: true });
      await startRecording(
        meeting.id,
        [
          { track: "MIC", stream: sources.microphone, offsetMs: 0 },
          ...(sources.tab ? [{ track: "TAB" as const, stream: sources.tab, offsetMs: 0 }] : []),
        ],
        cache,
      );
      onOpenChange(false);
      await navigate({
        to: "/meetings/$meetingId",
        params: { meetingId: meeting.id },
        search: sources.tab || kind === "IN_PERSON" ? {} : { tabAudio: "missing" as const },
      });
    } catch (failed) {
      for (const stream of [sources?.microphone, sources?.tab])
        for (const track of stream?.getTracks() ?? []) track.stop();
      if (failed instanceof ShareCancelledError)
        setError(ui("Bạn chưa chọn tab cuộc họp nên chưa bắt đầu ghi."));
      else if (failed instanceof DOMException)
        setError(ui("Trình duyệt không cho dùng micro. Hãy cho phép micro rồi thử lại."));
      else setError(problemMessage(presentProblem(failed, "mutation").message));
    } finally {
      setPending(false);
    }
  }

  async function copyNotice() {
    try {
      await navigator.clipboard.writeText(notice);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !pending && onOpenChange(next)}>
      <DialogContent className="sm:max-w-xl" aria-describedby={undefined}>
        <form onSubmit={(event) => void start(event)} className="grid gap-5">
          <DialogHeader>
            <DialogTitle>{ui("Ghi cuộc họp mới")}</DialogTitle>
          </DialogHeader>
          {support === "unsupported" && (
            <p
              role="status"
              className="rounded-xl bg-status-warning-surface px-4 py-3 text-sm text-status-warning-content"
            >
              {ui(
                "Trình duyệt này chưa ghi được. Hãy dùng Chrome hoặc Edge trên máy tính, hoặc Chrome trên điện thoại.",
              )}
            </p>
          )}
          <fieldset disabled={pending || support === "unsupported"} className="grid gap-4">
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-title`}>{ui("Tên cuộc họp")}</Label>
              <Input
                id={`${id}-title`}
                value={title}
                maxLength={200}
                placeholder={ui("Ví dụ: Giao ban tuần")}
                onChange={(event) => setTitle(event.target.value)}
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-participants`}>{ui("Thành phần")}</Label>
              <Input
                id={`${id}-participants`}
                value={participants}
                placeholder={ui("Anh Thanh, Chị Lan, Anh Minh")}
                aria-describedby={`${id}-participants-hint`}
                onChange={(event) => setParticipants(event.target.value)}
              />
              <p id={`${id}-participants-hint`} className="text-xs text-content-muted">
                {ui("Cách nhau bằng dấu phẩy; dùng để gán tên người nói.")}
              </p>
            </div>
            <MeetingShareField
              label={ui("Chia sẻ với")}
              value={audience}
              disabled={pending}
              onChange={setAudience}
            />
            <div className="grid gap-2">
              <Label>{ui("Hình thức")}</Label>
              <RadioGroup
                value={kind}
                onValueChange={(value) => setKind(value as MeetingKind)}
                className="grid gap-2 sm:grid-cols-2"
              >
                <Label
                  htmlFor={`${id}-online`}
                  className="flex cursor-pointer items-start gap-3 rounded-xl border border-border-subtle p-3 font-normal has-[[data-state=checked]]:border-content-primary"
                >
                  <RadioGroupItem
                    id={`${id}-online`}
                    value="ONLINE"
                    disabled={support !== "sharedAudio"}
                    className="mt-0.5"
                  />
                  <span className="grid gap-0.5">
                    <span className="flex items-center gap-1.5 font-main-ui-action">
                      <MonitorSpeaker className="size-4" aria-hidden="true" />
                      {ui("Họp online")}
                    </span>
                  </span>
                </Label>
                <Label
                  htmlFor={`${id}-in-person`}
                  className="flex cursor-pointer items-start gap-3 rounded-xl border border-border-subtle p-3 font-normal has-[[data-state=checked]]:border-content-primary"
                >
                  <RadioGroupItem id={`${id}-in-person`} value="IN_PERSON" className="mt-0.5" />
                  <span className="grid gap-0.5">
                    <span className="flex items-center gap-1.5 font-main-ui-action">
                      <Mic className="size-4" aria-hidden="true" />
                      {ui("Họp trực tiếp")}
                    </span>
                  </span>
                </Label>
              </RadioGroup>
              {kind === "ONLINE" && (
                <p className="text-xs text-content-muted">
                  {ui("Nhớ bật “Chia sẻ cả âm thanh của thẻ”, và nên đeo tai nghe.")}
                </p>
              )}
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-language`}>{ui("Ngôn ngữ")}</Label>
              <Select
                id={`${id}-language`}
                value={language}
                aria-describedby={`${id}-language-hint`}
                onChange={(event) => setLanguage(event.target.value as "vi" | "en" | "auto")}
              >
                <option value="vi">{ui("Tiếng Việt")}</option>
                <option value="auto">{ui("Tiếng Việt xen tiếng Anh")}</option>
                <option value="en">{ui("Tiếng Anh")}</option>
              </Select>
              <p id={`${id}-language-hint`} className="text-xs text-content-muted">
                {ui("Không đổi được sau khi bắt đầu ghi.")}
              </p>
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-terms`}>{ui("Thuật ngữ riêng")}</Label>
              <Input
                id={`${id}-terms`}
                value={terms}
                placeholder={ui("Tasco, OKR, KPI")}
                aria-describedby={`${id}-terms-hint`}
                onChange={(event) => setTerms(event.target.value)}
              />
              <p id={`${id}-terms-hint`} className="text-xs text-content-muted">
                {ui("Giúp nhận dạng đúng tên riêng và từ chuyên ngành.")}
              </p>
            </div>
            <div className="flex items-start gap-3 rounded-xl border border-border-subtle bg-surface-base p-3">
              <Checkbox
                id={`${id}-consent`}
                checked={consent}
                onCheckedChange={(checked) => setConsent(checked === true)}
                className="mt-0.5"
              />
              <Label htmlFor={`${id}-consent`} className="flex-1 font-normal">
                {ui("Tôi đã thông báo cho mọi người rằng buổi họp được ghi lại.")}
              </Label>
              <Button
                type="button"
                size="sm"
                prominence="secondary"
                onClick={() => void copyNotice()}
              >
                <Copy aria-hidden="true" />
                {copied ? ui("Đã sao chép") : ui("Câu thông báo")}
              </Button>
            </div>
          </fieldset>
          {error && (
            <p
              role="alert"
              className="rounded-lg bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
            >
              {error}
            </p>
          )}
          <DialogFooter>
            <Button
              type="button"
              prominence="secondary"
              disabled={pending}
              onClick={() => onOpenChange(false)}
            >
              {ui("Huỷ")}
            </Button>
            <Button
              type="submit"
              pending={pending}
              disabled={!consent || support === "unsupported"}
            >
              <Mic aria-hidden="true" />
              {ui("Bắt đầu ghi")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
