import { useId, useRef, useState, type FormEvent } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { FileAudio, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";
import { Select } from "@/components/ui/select";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import {
  loadTranscribers,
  meetingKey,
  meetingsKey,
  startMeeting,
  transcribersKey,
  uploadRecording,
  type MeetingTranscriber,
} from "./meetings-api";

/** Containers every supported provider reads; the server checks the type again against the bytes. */
const ACCEPT = ".mp3,.m4a,.wav,.webm,.ogg,.flac,.mp4,audio/*";

/** Splits "Anh Thanh, Chị Lan" into names, as the recording dialog does. */
function list(value: string) {
  return value
    .split(/[,;\n]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

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
  const ui = useAppTranslation();
  const id = useId();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const problemMessage = useProblemMessage();
  const aborter = useRef<AbortController>(undefined);
  const [file, setFile] = useState<File>();
  const [title, setTitle] = useState("");
  const [participants, setParticipants] = useState("");
  const [provider, setProvider] = useState<MeetingTranscriber["provider"]>();
  const [consent, setConsent] = useState(false);
  const [percent, setPercent] = useState<number>();
  const [error, setError] = useState<string>();

  const transcribers = useQuery({
    queryKey: transcribersKey,
    queryFn: ({ signal }) => loadTranscribers(signal),
    enabled: open,
  });
  const chosen =
    transcribers.data?.find((item) => item.provider === provider) ??
    transcribers.data?.find((item) => item.selected);
  const pending = percent !== undefined;
  const tooLarge = !!file && !!chosen && file.size > chosen.maxBytes;
  const nobody = transcribers.isSuccess && transcribers.data.length === 0;

  async function upload(event: FormEvent) {
    event.preventDefault();
    if (!file || pending || tooLarge || nobody || !consent) return;
    setPercent(0);
    setError(undefined);
    const controller = new AbortController();
    aborter.current = controller;
    try {
      const meeting = await startMeeting({
        title: title.trim() || file.name.replace(/\.[^.]+$/, ""),
        kind: "IN_PERSON",
        language: "vi",
        participants: list(participants),
        terms: [],
      });
      const uploaded = await uploadRecording(
        meeting.id,
        file,
        chosen?.provider,
        controller.signal,
        setPercent,
      );
      cache.setQueryData(meetingKey(uploaded.id), uploaded);
      void cache.invalidateQueries({ queryKey: meetingsKey, exact: true });
      onOpenChange(false);
      await navigate({ to: "/meetings/$meetingId", params: { meetingId: uploaded.id } });
    } catch (failed) {
      if (!controller.signal.aborted)
        setError(problemMessage(presentProblem(failed, "mutation").message));
    } finally {
      aborter.current = undefined;
      setPercent(undefined);
    }
  }

  function close(next: boolean) {
    if (!next) aborter.current?.abort();
    onOpenChange(next);
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="sm:max-w-xl" aria-describedby={undefined}>
        <form onSubmit={(event) => void upload(event)} className="grid gap-5">
          <DialogHeader>
            <DialogTitle>{ui("Tải file ghi âm")}</DialogTitle>
          </DialogHeader>
          {nobody && (
            <p
              role="status"
              className="rounded-xl bg-status-warning-surface px-4 py-3 text-sm text-status-warning-content"
            >
              {ui(
                "Chưa có kết nối nhận dạng giọng nói nào đọc được file. Hãy nhờ quản trị viên cấu hình.",
              )}
            </p>
          )}
          <fieldset disabled={pending || nobody} className="grid gap-4">
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-file`}>{ui("File ghi âm")}</Label>
              <Input
                id={`${id}-file`}
                type="file"
                accept={ACCEPT}
                onChange={(event) => setFile(event.target.files?.[0])}
              />
              {file && <p className="text-xs text-content-muted">{size(file.size)}</p>}
            </div>
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
                onChange={(event) => setParticipants(event.target.value)}
              />
            </div>
            {transcribers.data && transcribers.data.length > 1 && (
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-provider`}>{ui("Nhận dạng bằng")}</Label>
                <Select
                  id={`${id}-provider`}
                  value={chosen?.provider ?? ""}
                  onChange={(event) =>
                    setProvider(event.target.value as MeetingTranscriber["provider"])
                  }
                >
                  {transcribers.data.map((item) => (
                    <option key={item.provider} value={item.provider}>
                      {item.provider} · {item.model}
                    </option>
                  ))}
                </Select>
              </div>
            )}
            {chosen && (
              <p className="flex flex-wrap items-center gap-3 text-xs text-content-muted">
                <span className="inline-flex items-center gap-1">
                  <Users className="size-3" aria-hidden="true" />
                  {chosen.diarizes ? ui("Tách được người nói") : ui("Không tách người nói")}
                </span>
                <span>{ui("Tối đa {{size}} MB", { size: megabytes(chosen.maxBytes) })}</span>
              </p>
            )}
            <Label
              htmlFor={`${id}-consent`}
              className="flex items-start gap-3 font-normal text-content-secondary"
            >
              <Checkbox
                id={`${id}-consent`}
                checked={consent}
                className="mt-0.5"
                onCheckedChange={(checked) => setConsent(checked === true)}
              />
              {ui("Những người trong bản ghi đã biết buổi họp được ghi lại.")}
            </Label>
          </fieldset>
          {tooLarge && chosen && (
            <p role="alert" className="text-sm text-status-danger-content">
              {ui("File {{size}} MB vượt giới hạn {{limit}} MB của {{provider}}.", {
                size: megabytes(file.size),
                limit: megabytes(chosen.maxBytes),
                provider: chosen.provider,
              })}
            </p>
          )}
          {pending && (
            <div className="grid gap-1.5">
              <Progress value={percent} />
              <p role="status" className="text-xs text-content-muted">
                {ui("Đang tải lên… {{percent}}%", { percent: Math.round(percent) })}
              </p>
            </div>
          )}
          {error && (
            <p role="alert" className="text-sm text-status-danger-content">
              {error}
            </p>
          )}
          <DialogFooter>
            <Button type="button" prominence="tertiary" onClick={() => close(false)}>
              {ui("Huỷ")}
            </Button>
            <Button
              type="submit"
              pending={pending}
              disabled={!file || tooLarge || nobody || !consent}
            >
              <FileAudio aria-hidden="true" />
              {ui("Tải lên và nhận dạng")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
