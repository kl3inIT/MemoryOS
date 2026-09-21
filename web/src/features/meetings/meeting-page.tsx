import { useEffect, useId, useRef, useState, useSyncExternalStore } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import {
  ArrowLeft,
  Lock,
  Mic,
  MonitorSpeaker,
  Pause,
  Play,
  Square,
  Trash2,
  Users,
} from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { DotMatrix } from "@/components/ui/dot-matrix";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { SettingsLayout } from "@/components/ui/settings-layout";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { i18n } from "@/i18n";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ApiError } from "@/lib/api";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { cn } from "@/lib/utils";
import {
  captureSupport,
  openMeetingSources,
  pickMeetingTab,
  ShareCancelledError,
} from "./meeting-capture";
import type { MeetingRecorder, RecorderSnapshot } from "./meeting-recorder";
import { startRecording, stopRecording, useActiveMeeting } from "./meeting-session";
import type { MeetingTrack } from "./meeting-socket";
import {
  finishMeeting,
  formatClock,
  formatWhen,
  loadMeeting,
  meetingKey,
  meetingsKey,
  nameSpeaker,
  removeMeeting,
  saveMeetingNotes,
  trackOffsets,
  type MeetingDetail,
} from "./meetings-api";

type Translate = ReturnType<typeof useAppTranslation>;

const idle: RecorderSnapshot = { phase: "stopped", elapsedMs: 0, tracks: [], previews: {} };
const noop = () => () => undefined;

function useRecorderSnapshot(recorder: MeetingRecorder | undefined) {
  return useSyncExternalStore(recorder?.subscribe ?? noop, recorder?.getSnapshot ?? (() => idle));
}

/** Online, the microphone is the owner; every other voice is a numbered speaker until someone names it. */
function speakerName(meeting: MeetingDetail, track: MeetingTrack, label: string, ui: Translate) {
  const named = meeting.speakers.find(
    (speaker) => speaker.track === track && speaker.label === label,
  )?.name;
  if (named) return named;
  if (meeting.kind === "ONLINE" && track === "MIC") return ui("Bạn");
  return ui("Người nói {{label}}", { label });
}

const SPEAKER_COLORS = [
  "bg-chart-1",
  "bg-chart-2",
  "bg-chart-3",
  "bg-chart-4",
  "bg-chart-5",
  "bg-chart-6",
  "bg-chart-7",
  "bg-chart-8",
];

function speakerColor(meeting: MeetingDetail, track: MeetingTrack, label: string) {
  const index = meeting.speakers.findIndex(
    (speaker) => speaker.track === track && speaker.label === label,
  );
  return SPEAKER_COLORS[Math.max(0, index) % SPEAKER_COLORS.length];
}

function socketMessage(code: string, ui: Translate) {
  switch (code) {
    case "MEETING_ENDED":
      return ui("Cuộc họp đã kết thúc nên không ghi tiếp được.");
    case "MEETING_TOO_LONG":
      return ui("Mỗi luồng âm thanh ghi tối đa 5 giờ.");
    case "MEETING_BUSY":
      return ui("Máy chủ đang bận. Hãy thử lại sau ít phút.");
    case "MEETING_PROVIDER_FAILED":
    case "MEETING_UNAVAILABLE":
      return ui("Dịch vụ nhận dạng giọng nói không phản hồi. Những gì đã ghi vẫn được lưu.");
    default:
      return ui("Mất kết nối và không nối lại được. Những gì đã ghi vẫn được lưu.");
  }
}

export function MeetingPage({
  meetingId,
  tabAudioMissing,
}: {
  meetingId: string;
  tabAudioMissing?: boolean;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const problemMessage = useProblemMessage();
  const meeting = useQuery({
    queryKey: meetingKey(meetingId),
    queryFn: ({ signal }) => loadMeeting(meetingId, signal),
  });
  const live = useActiveMeeting();
  const recorder = live?.meetingId === meetingId ? live.recorder : undefined;
  const snapshot = useRecorderSnapshot(recorder);
  const [tabMissing, setTabMissing] = useState(!!tabAudioMissing);
  const [actionError, setActionError] = useState<string>();
  const [pending, setPending] = useState(false);

  if (meeting.isPending)
    return (
      <AppShell pageTitle={ui("Cuộc họp")}>
        <SettingsLayout wide>
          <Skeleton className="h-10 w-1/2" />
          <Skeleton className="h-64 rounded-xl" />
        </SettingsLayout>
      </AppShell>
    );
  if (meeting.isError)
    return (
      <AppShell pageTitle={ui("Cuộc họp")}>
        <SettingsLayout wide>
          <p role="alert" className="text-sm text-status-danger-content">
            {problemMessage(presentProblem(meeting.error, "initialLoad").message)}
          </p>
          <Link to="/meetings" className="text-sm underline">
            {ui("Về danh sách cuộc họp")}
          </Link>
        </SettingsLayout>
      </AppShell>
    );
  const data = meeting.data;
  const recording =
    !!recorder &&
    (snapshot.phase === "recording" ||
      snapshot.phase === "paused" ||
      snapshot.phase === "stopping");

  async function resume() {
    setPending(true);
    setActionError(undefined);
    try {
      const sources = await openMeetingSources(data.kind);
      const offsets = trackOffsets(data);
      await startRecording(
        meetingId,
        [
          { track: "MIC", stream: sources.microphone, offsetMs: offsets.MIC },
          ...(sources.tab
            ? [{ track: "TAB" as const, stream: sources.tab, offsetMs: offsets.TAB }]
            : []),
        ],
        cache,
      );
      setTabMissing(data.kind === "ONLINE" && !sources.tab);
    } catch (failed) {
      setActionError(
        failed instanceof ShareCancelledError
          ? ui("Bạn chưa chọn tab cuộc họp nên chưa bắt đầu ghi.")
          : failed instanceof DOMException
            ? ui("Trình duyệt không cho dùng micro. Hãy cho phép micro rồi thử lại.")
            : problemMessage(presentProblem(failed, "mutation").message),
      );
    } finally {
      setPending(false);
    }
  }

  async function end() {
    await stopRecording();
    const ended = await finishMeeting(meetingId);
    cache.setQueryData(meetingKey(meetingId), ended);
    void cache.invalidateQueries({ queryKey: meetingsKey, exact: true });
  }

  async function shareTab() {
    if (!recorder) return;
    try {
      const tab = await pickMeetingTab();
      if (!tab) {
        setTabMissing(true);
        return;
      }
      await recorder.replaceTab(tab);
      setTabMissing(false);
    } catch (failed) {
      if (!(failed instanceof ShareCancelledError))
        setActionError(ui("Không chia sẻ được tab. Hãy thử lại."));
    }
  }

  const tab = snapshot.tracks.find((track) => track.track === "TAB");
  const reconnecting = snapshot.tracks.some((track) => track.reconnecting);

  return (
    <AppShell pageTitle={data.title}>
      {recording && (
        <RecordingBar
          snapshot={snapshot}
          meeting={data}
          onPause={() => void recorder.pause()}
          onResume={() => void recorder.resume()}
          onStop={end}
          ui={ui}
        />
      )}
      <SettingsLayout wide className="gap-5">
        <div className="grid gap-2">
          <Link
            to="/meetings"
            className="inline-flex w-fit items-center gap-1 text-sm text-content-muted hover:text-content-primary"
          >
            <ArrowLeft className="size-4" aria-hidden="true" />
            {ui("Cuộc họp")}
          </Link>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <h1 className="min-w-0 break-words font-heading-h2 text-content-primary">
              {data.title}
            </h1>
            <div className="flex flex-wrap gap-2">
              {data.status === "RECORDING" && !recording && (
                <>
                  <Button
                    pending={pending}
                    disabled={!!live || captureSupport() === "unsupported"}
                    onClick={() => void resume()}
                  >
                    <Mic aria-hidden="true" />
                    {ui("Tiếp tục ghi")}
                  </Button>
                  <ConfirmDialog
                    trigger={
                      <Button prominence="secondary">
                        <Square aria-hidden="true" />
                        {ui("Kết thúc cuộc họp")}
                      </Button>
                    }
                    title={ui("Kết thúc cuộc họp?")}
                    description={ui("Sau khi kết thúc, cuộc họp này không ghi tiếp được nữa.")}
                    confirmLabel={ui("Kết thúc")}
                    pendingLabel={ui("Đang kết thúc…")}
                    onConfirm={end}
                  />
                </>
              )}
              {data.status === "ENDED" && (
                <ConfirmDialog
                  trigger={
                    <Button prominence="secondary" tone="danger">
                      <Trash2 aria-hidden="true" />
                      {ui("Xoá")}
                    </Button>
                  }
                  title={ui("Xoá cuộc họp?")}
                  description={ui(
                    "Transcript, tên người nói và ghi chú của cuộc họp này sẽ bị xoá vĩnh viễn.",
                  )}
                  confirmLabel={ui("Xoá")}
                  pendingLabel={ui("Đang xoá…")}
                  confirmTone="danger"
                  onConfirm={async () => {
                    await removeMeeting(meetingId);
                    cache.removeQueries({ queryKey: meetingKey(meetingId) });
                    void cache.invalidateQueries({ queryKey: meetingsKey, exact: true });
                    await navigate({ to: "/meetings" });
                  }}
                />
              )}
            </div>
          </div>
          <p className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-content-muted">
            <span>{formatWhen(data.createdAt, i18n.language)}</span>
            <span className="inline-flex items-center gap-1">
              {data.kind === "ONLINE" ? (
                <MonitorSpeaker className="size-3.5" aria-hidden="true" />
              ) : (
                <Mic className="size-3.5" aria-hidden="true" />
              )}
              {data.kind === "ONLINE" ? ui("Họp online") : ui("Họp trực tiếp")}
            </span>
            {data.participants.length > 0 && (
              <span className="inline-flex items-center gap-1">
                <Users className="size-3.5" aria-hidden="true" />
                {data.participants.join(", ")}
              </span>
            )}
            <span className="inline-flex items-center gap-1">
              <Lock className="size-3.5" aria-hidden="true" />
              {ui("Chỉ mình bạn")}
            </span>
          </p>
        </div>

        {data.status === "RECORDING" && !recording && !pending && (
          <p
            role="status"
            className="rounded-xl border border-border-default bg-surface-sunken px-4 py-3 text-sm text-content-secondary"
          >
            {ui(
              "Cuộc họp này chưa kết thúc nhưng không còn ghi, có thể do tab đã đóng. Bấm “Tiếp tục ghi” để ghi tiếp đúng mốc thời gian, hoặc kết thúc cuộc họp.",
            )}
          </p>
        )}
        {recording && data.kind === "ONLINE" && (tabMissing || tab?.ended) && (
          <Warning
            action={
              <Button size="sm" prominence="secondary" onClick={() => void shareTab()}>
                {ui("Chọn tab họp")}
              </Button>
            }
          >
            {tab?.ended
              ? ui("Tab cuộc họp đã dừng chia sẻ, nên chỉ còn ghi giọng của bạn.")
              : ui(
                  "Chưa có âm thanh của tab cuộc họp, có thể bạn chưa bật “Chia sẻ cả âm thanh của thẻ”. Hiện chỉ ghi giọng của bạn.",
                )}
          </Warning>
        )}
        {recording && tab?.quiet && !tab.ended && (
          <Warning
            action={
              <Button size="sm" prominence="secondary" onClick={() => void shareTab()}>
                {ui("Chọn lại tab")}
              </Button>
            }
          >
            {ui(
              "Không nghe thấy âm thanh từ tab cuộc họp hơn 20 giây. Nếu mọi người đang nói, có thể âm thanh tab chưa được chia sẻ.",
            )}
          </Warning>
        )}
        {reconnecting && (
          <p
            role="status"
            className="rounded-xl bg-status-info-surface px-4 py-3 text-sm text-status-info-content"
          >
            {ui("Đang kết nối lại… Âm thanh vẫn được giữ và sẽ gửi tiếp khi có kết nối.")}
          </p>
        )}
        {recorder && snapshot.phase === "failed" && snapshot.error && (
          <p
            role="alert"
            className="rounded-xl bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
          >
            {socketMessage(snapshot.error, ui)}
          </p>
        )}
        {actionError && (
          <p
            role="alert"
            className="rounded-xl bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
          >
            {actionError}
          </p>
        )}

        <Tabs defaultValue="transcript">
          <TabsList>
            <TabsTrigger value="transcript">{ui("Transcript")}</TabsTrigger>
            <TabsTrigger value="notes">{ui("Ghi chú của tôi")}</TabsTrigger>
          </TabsList>
          <TabsContent value="transcript" className="pt-4">
            <Transcript meeting={data} snapshot={recording ? snapshot : idle} ui={ui} />
          </TabsContent>
          <TabsContent value="notes" className="pt-4">
            <Notes meeting={data} ui={ui} />
          </TabsContent>
        </Tabs>
      </SettingsLayout>
    </AppShell>
  );
}

function Warning({ children, action }: { children: React.ReactNode; action: React.ReactNode }) {
  return (
    <div
      role="status"
      className="flex flex-wrap items-center gap-3 rounded-xl bg-status-warning-surface px-4 py-3 text-sm text-status-warning-content"
    >
      <p className="min-w-0 flex-1">{children}</p>
      {action}
    </div>
  );
}

function Meter({ level, label }: { level: number; label: string }) {
  const bars = 5;
  // RMS rarely exceeds 0.3 for speech; the meter is only a sign of life.
  const lit = Math.round(Math.min(1, level / 0.15) * bars);
  return (
    <span
      className="inline-flex items-center gap-1.5 text-xs text-content-secondary"
      aria-label={label}
    >
      {label}
      <span className="flex h-3.5 items-end gap-0.5" aria-hidden="true">
        {Array.from({ length: bars }, (_, index) => (
          <span
            key={index}
            className={cn(
              "w-1 rounded-sm",
              index < lit ? "bg-status-success-strong" : "bg-surface-strong",
            )}
            style={{ height: `${40 + index * 15}%` }}
          />
        ))}
      </span>
    </span>
  );
}

function RecordingBar({
  snapshot,
  meeting,
  onPause,
  onResume,
  onStop,
  ui,
}: {
  snapshot: RecorderSnapshot;
  meeting: MeetingDetail;
  onPause: () => void;
  onResume: () => void;
  onStop: () => Promise<void>;
  ui: Translate;
}) {
  const paused = snapshot.phase === "paused";
  const reconnecting = snapshot.tracks.some((track) => track.reconnecting);
  return (
    <div className="sticky top-0 z-10 flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-border-subtle bg-surface-raised px-(--page-gutter) py-2.5">
      <DotMatrix
        state={paused ? "paused" : reconnecting ? "connecting" : "recording"}
        label={paused ? ui("Tạm dừng") : reconnecting ? ui("Đang kết nối lại") : ui("Đang ghi")}
        className="size-5"
      />
      <span
        className="font-mono text-base tabular-nums"
        role="timer"
        aria-label={ui("Thời gian ghi")}
      >
        {formatClock(snapshot.elapsedMs)}
      </span>
      <span className="text-xs text-content-muted">
        {snapshot.phase === "stopping"
          ? ui("Đang lưu phần cuối…")
          : paused
            ? ui("Tạm dừng")
            : ui("Đang ghi")}
      </span>
      {snapshot.tracks.map((track) => (
        <Meter
          key={track.track}
          level={paused ? 0 : track.level}
          label={
            track.track === "TAB"
              ? ui("Tab họp")
              : meeting.kind === "ONLINE"
                ? ui("Bạn")
                : ui("Micro")
          }
        />
      ))}
      <span className="flex-1" />
      {paused ? (
        <Button size="sm" prominence="secondary" onClick={onResume}>
          <Play aria-hidden="true" />
          {ui("Tiếp tục")}
        </Button>
      ) : (
        <Button
          size="sm"
          prominence="secondary"
          disabled={snapshot.phase !== "recording"}
          onClick={onPause}
        >
          <Pause aria-hidden="true" />
          {ui("Tạm dừng")}
        </Button>
      )}
      <ConfirmDialog
        trigger={
          <Button size="sm" tone="danger" disabled={snapshot.phase === "stopping"}>
            <Square aria-hidden="true" />
            {ui("Dừng")}
          </Button>
        }
        title={ui("Dừng ghi và kết thúc cuộc họp?")}
        description={ui(
          "Những gì đã nói đến {{time}} đã được lưu. Sau khi dừng, cuộc họp này không ghi tiếp được.",
          {
            time: formatClock(snapshot.elapsedMs),
          },
        )}
        confirmLabel={ui("Dừng và kết thúc")}
        pendingLabel={ui("Đang lưu phần cuối…")}
        confirmTone="danger"
        onConfirm={onStop}
      />
    </div>
  );
}

function Transcript({
  meeting,
  snapshot,
  ui,
}: {
  meeting: MeetingDetail;
  snapshot: RecorderSnapshot;
  ui: Translate;
}) {
  const previews = (
    Object.entries(snapshot.previews) as [
      MeetingTrack,
      { speaker: string; text: string } | undefined,
    ][]
  ).filter((entry): entry is [MeetingTrack, { speaker: string; text: string }] => !!entry[1]?.text);
  if (meeting.utterances.length === 0 && previews.length === 0)
    return (
      <p className="rounded-xl border border-dashed border-border-default px-4 py-8 text-center text-sm text-content-muted">
        {snapshot.phase === "recording"
          ? ui("Đang nghe… Transcript sẽ hiện khi có người nói.")
          : ui("Cuộc họp này chưa có transcript.")}
      </p>
    );
  return (
    <ol className="grid gap-1" aria-live="polite" aria-relevant="additions">
      {meeting.utterances.map((utterance) => (
        <li
          key={utterance.id}
          className="grid grid-cols-[4.5rem_1fr] gap-x-3 rounded-lg px-2 py-2 hover:bg-surface-base"
        >
          <span className="pt-0.5 font-mono text-xs text-content-muted tabular-nums">
            {formatClock(utterance.startMs)}
          </span>
          <div className="min-w-0">
            <SpeakerChip
              meeting={meeting}
              track={utterance.track}
              label={utterance.speaker}
              ui={ui}
            />
            <p className="mt-0.5 text-content-secondary">{utterance.text}</p>
          </div>
        </li>
      ))}
      {previews.map(([track, preview]) => (
        <li key={`preview-${track}`} className="grid grid-cols-[4.5rem_1fr] gap-x-3 px-2 py-2">
          <span className="pt-0.5 text-xs text-content-muted">{ui("đang nói")}</span>
          <div className="min-w-0">
            <span className="inline-flex items-center gap-1.5 text-sm font-medium text-content-muted">
              <span
                className={cn(
                  "size-2.5 rounded-full opacity-60",
                  speakerColor(meeting, track, preview.speaker || "1"),
                )}
                aria-hidden="true"
              />
              {speakerName(meeting, track, preview.speaker || "1", ui)}
            </span>
            <p className="mt-0.5 italic text-content-muted">{preview.text}…</p>
          </div>
        </li>
      ))}
    </ol>
  );
}

function SpeakerChip({
  meeting,
  track,
  label,
  ui,
}: {
  meeting: MeetingDetail;
  track: MeetingTrack;
  label: string;
  ui: Translate;
}) {
  const cache = useQueryClient();
  const id = useId();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [error, setError] = useState<string>();
  const owner = meeting.kind === "ONLINE" && track === "MIC";
  const display = speakerName(meeting, track, label, ui);
  const dot = (
    <span
      className={cn("size-2.5 rounded-full", speakerColor(meeting, track, label))}
      aria-hidden="true"
    />
  );
  if (owner)
    return (
      <span className="inline-flex items-center gap-1.5 text-sm font-medium text-content-primary">
        {dot}
        {display}
      </span>
    );

  async function save(value: string | null) {
    setError(undefined);
    try {
      const saved = await nameSpeaker(meeting.id, track, label, value);
      cache.setQueryData(meetingKey(meeting.id), (current: MeetingDetail | undefined) =>
        current ? { ...current, speakers: saved.speakers } : saved,
      );
      setOpen(false);
      setName("");
    } catch (failed) {
      setError(
        failed instanceof ApiError
          ? ui("Không đổi được tên. Hãy thử lại.")
          : ui("Không đổi được tên. Hãy thử lại."),
      );
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="inline-flex items-center gap-1.5 rounded text-sm font-medium text-content-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
          aria-label={ui("Đặt tên cho {{name}}", { name: display })}
        >
          {dot}
          {display}
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="grid w-64 gap-2 p-2">
        <p className="px-1 text-xs text-content-muted">
          {ui("Áp dụng cho mọi câu của {{name}}", { name: display })}
        </p>
        {meeting.participants.map((participant) => (
          <button
            key={participant}
            type="button"
            className="rounded-md px-2 py-1.5 text-left text-sm hover:bg-surface-subtle"
            onClick={() => void save(participant)}
          >
            {participant}
          </button>
        ))}
        <form
          className="flex gap-1.5"
          onSubmit={(event) => {
            event.preventDefault();
            if (name.trim()) void save(name.trim());
          }}
        >
          <Input
            id={`${id}-name`}
            size="sm"
            value={name}
            maxLength={200}
            placeholder={ui("Tên khác")}
            aria-label={ui("Tên người nói")}
            onChange={(event) => setName(event.target.value)}
          />
          <Button size="sm" type="submit" disabled={!name.trim()}>
            {ui("Lưu")}
          </Button>
        </form>
        {meeting.speakers.some(
          (speaker) => speaker.track === track && speaker.label === label && speaker.name,
        ) && (
          <Button size="sm" prominence="tertiary" onClick={() => void save(null)}>
            {ui("Bỏ tên")}
          </Button>
        )}
        {error && (
          <p role="alert" className="px-1 text-xs text-status-danger-content">
            {error}
          </p>
        )}
      </PopoverContent>
    </Popover>
  );
}

function Notes({ meeting, ui }: { meeting: MeetingDetail; ui: Translate }) {
  const cache = useQueryClient();
  const id = useId();
  const [value, setValue] = useState(meeting.notes);
  const [state, setState] = useState<"saved" | "saving" | "dirty" | "conflict">("saved");
  const revision = useRef(meeting.revision);
  const timer = useRef<number>(undefined);

  useEffect(() => {
    revision.current = meeting.revision;
  }, [meeting.revision]);
  useEffect(() => () => window.clearTimeout(timer.current), []);

  function change(next: string) {
    setValue(next);
    setState("dirty");
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => void save(next), 1_200);
  }

  async function save(next: string) {
    setState("saving");
    try {
      const saved = await saveMeetingNotes(meeting.id, next, revision.current);
      revision.current = saved.revision;
      cache.setQueryData(meetingKey(meeting.id), (current: MeetingDetail | undefined) =>
        current ? { ...current, notes: saved.notes, revision: saved.revision } : saved,
      );
      setState("saved");
    } catch (failed) {
      setState(failed instanceof ApiError && failed.status === 409 ? "conflict" : "dirty");
    }
  }

  return (
    <div className="grid gap-2">
      <label htmlFor={`${id}-notes`} className="text-sm text-content-muted">
        {ui("Ghi chú riêng của bạn. Không ai khác xem được.")}
      </label>
      <Textarea
        id={`${id}-notes`}
        value={value}
        maxLength={50_000}
        rows={12}
        onChange={(event) => change(event.target.value)}
        onBlur={() => state === "dirty" && void save(value)}
      />
      <p className="text-xs text-content-muted" role="status">
        {state === "saving"
          ? ui("Đang lưu…")
          : state === "saved"
            ? ui("Đã lưu")
            : state === "conflict"
              ? ui("Ghi chú vừa được sửa ở nơi khác. Tải lại trang để xem bản mới.")
              : ui("Chưa lưu")}
      </p>
    </div>
  );
}
