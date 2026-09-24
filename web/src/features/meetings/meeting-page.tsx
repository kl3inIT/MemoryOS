import { useEffect, useId, useRef, useState, useSyncExternalStore } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import {
  Bookmark,
  CheckSquare,
  ChevronDown,
  ChevronUp,
  Clock,
  FileAudio,
  FileDown,
  Lock,
  MessageSquareText,
  Mic,
  Gavel,
  MonitorSpeaker,
  Pause,
  RefreshCw,
  Play,
  Square,
  Star,
  Trash2,
  Users,
  WifiOff,
} from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { BrandLoader } from "@/components/brand-loader";
import { DangerZone } from "@/components/composites/danger-zone";
import { EmptyState } from "@/components/composites/empty-state";
import { StatStrip, StatTile } from "@/components/composites/stat-strip";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { DotMatrix } from "@/components/ui/dot-matrix";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
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
import { ExportMinutesDialog } from "./export-minutes-dialog";
import { MeetingDetailsDialog } from "./meeting-details-dialog";
import { MeetingShareField, type MeetingAudience } from "./meeting-share-field";
import {
  endMeeting,
  startRecording,
  useActiveMeeting,
  useRecordingFailure,
} from "./meeting-session";
import type { MeetingTrack } from "./meeting-socket";
import { slug } from "./meeting-file-name";
import { EditableItem, EditableSummary, NewItem } from "./minutes-editing";
import { SpeakerSuggestions } from "./speaker-suggestions";
import { TranscriptCorrections } from "./transcript-corrections";
import { WordCorrection } from "./word-correction";
import { matches } from "./transcript-search";
import { Said } from "./transcript-text";
import {
  addBookmark,
  exportTranscript,
  formatClock,
  formatWhen,
  loadMeeting,
  markMinutesItem,
  meetingKey,
  invalidateMeetingList,
  nameSpeaker,
  publishMinutes,
  removeMeeting,
  rerunMinutes,
  shareMeeting,
  saveMeetingNotes,
  setUtteranceStar,
  trackOffsets,
  type MeetingDetail,
  type MeetingMinutesItem,
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
      return ui("Dịch vụ nhận dạng giọng nói không phản hồi. Phần đã ghi vẫn được lưu.");
    default:
      return ui("Mất kết nối và không nối lại được. Phần đã ghi vẫn được lưu.");
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
    // A recording being transcribed, minutes being written and a correction pass have no socket; the page asks
    // again until they land.
    refetchInterval: (query) => {
      const current = query.state.data;
      if (!current) return false;
      const waiting =
        current.status === "TRANSCRIBING" ||
        current.minutes.status === "PENDING" ||
        current.minutes.status === "RUNNING" ||
        current.correcting;
      return waiting ? 3000 : false;
    },
  });
  const live = useActiveMeeting();
  const recorder = live?.meetingId === meetingId ? live.recorder : undefined;
  const snapshot = useRecorderSnapshot(recorder);
  const failure = useRecordingFailure();
  const stoppedBy = failure?.meetingId === meetingId ? failure.code : undefined;
  const [tabMissing, setTabMissing] = useState(!!tabAudioMissing);
  const [pane, setPane] = useState<string>();
  const [actionError, setActionError] = useState<string>();
  const [pending, setPending] = useState(false);

  if (meeting.isPending)
    return (
      <AppShell pageTitle={ui("Cuộc họp")}>
        <SettingsLayout wide className="gap-6 md:pt-8">
          <PageHeader title={ui("Cuộc họp")} icon={<Mic />} />
          <div
            role="status"
            className="flex justify-center rounded-xl border border-border-subtle px-6 py-20"
          >
            <BrandLoader label={ui("Đang tải cuộc họp")} />
          </div>
        </SettingsLayout>
      </AppShell>
    );
  if (meeting.isError)
    return (
      <AppShell pageTitle={ui("Cuộc họp")}>
        <SettingsLayout wide className="gap-6 md:pt-8">
          <PageHeader title={ui("Cuộc họp")} icon={<Mic />} />
          <EmptyState
            role="alert"
            icon={<WifiOff />}
            title={ui("Chưa xem được cuộc họp này")}
            detail={problemMessage(presentProblem(meeting.error, "initialLoad").message)}
            action={
              <Button size="sm" prominence="secondary" onClick={() => void meeting.refetch()}>
                {ui("Thử lại")}
              </Button>
            }
          />
        </SettingsLayout>
      </AppShell>
    );
  const data = meeting.data;
  const recording =
    !!recorder &&
    (snapshot.phase === "recording" ||
      snapshot.phase === "paused" ||
      snapshot.phase === "stopping");
  const transcribing = data.status === "TRANSCRIBING";
  // Everything that changes the meeting belongs to whoever recorded it; a reader reads.
  const owned = data.owned;
  const shown = pane ?? (data.minutes.status === "READY" ? "summary" : "transcript");

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

  /** A mark is the reader's own, so it is applied straight away and the whole meeting comes back with it. */
  function star(utteranceId: string, starred: boolean) {
    void (async () => {
      setActionError(undefined);
      try {
        cache.setQueryData(
          meetingKey(meetingId),
          await setUtteranceStar(meetingId, utteranceId, starred),
        );
      } catch (failed) {
        setActionError(problemMessage(presentProblem(failed, "mutation").message));
      }
    })();
  }

  function bookmark(atMs: number) {
    void (async () => {
      setActionError(undefined);
      try {
        cache.setQueryData(meetingKey(meetingId), await addBookmark(meetingId, atMs));
      } catch (failed) {
        setActionError(problemMessage(presentProblem(failed, "mutation").message));
      }
    })();
  }

  /** Closes the confirmation at once; the recording bar shows the last words being stored. */
  async function end() {
    void endMeeting(meetingId, cache).catch(() =>
      setActionError(ui("Chưa kết thúc được cuộc họp. Hãy thử lại.")),
    );
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

  const duration = recording
    ? snapshot.elapsedMs
    : data.utterances.reduce((longest, utterance) => Math.max(longest, utterance.endMs), 0);
  const named = data.speakers.filter((speaker) => speaker.name).length;
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
          onBookmark={bookmark}
          ui={ui}
        />
      )}
      <SettingsLayout wide className="gap-5 md:pt-8">
        <PageHeader
          icon={<Mic />}
          title={data.title}
          description={
            <span className="flex flex-wrap gap-x-4 gap-y-1">
              <span>{formatWhen(data.createdAt, i18n.language)}</span>
              <span className="inline-flex items-center gap-1">
                {data.audio.status !== "NONE" ? (
                  <FileAudio className="size-3.5" aria-hidden="true" />
                ) : data.kind === "ONLINE" ? (
                  <MonitorSpeaker className="size-3.5" aria-hidden="true" />
                ) : (
                  <Mic className="size-3.5" aria-hidden="true" />
                )}
                {data.audio.status !== "NONE"
                  ? ui("Bản ghi tải lên")
                  : data.kind === "ONLINE"
                    ? ui("Họp online")
                    : ui("Họp trực tiếp")}
              </span>
              {data.participants.length > 0 && (
                <span className="inline-flex items-center gap-1">
                  <Users className="size-3.5" aria-hidden="true" />
                  {data.participants.join(", ")}
                </span>
              )}
              <span className="inline-flex items-center gap-1">
                {data.owned && data.readers.length === 0 ? (
                  <>
                    <Lock className="size-3.5" aria-hidden="true" />
                    {ui("Chỉ mình bạn")}
                  </>
                ) : (
                  <>
                    <Users className="size-3.5" aria-hidden="true" />
                    {data.owned
                      ? ui("Chia sẻ với {{count}} người và nhóm", { count: data.readers.length })
                      : ui("Được chia sẻ với bạn")}
                  </>
                )}
              </span>
            </span>
          }
          actions={
            !owned ? null : data.status === "RECORDING" && !recording ? (
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
                  description={ui("Sau khi kết thúc, cuộc họp không ghi tiếp được.")}
                  confirmLabel={ui("Kết thúc")}
                  pendingLabel={ui("Đang kết thúc…")}
                  onConfirm={end}
                />
                <MeetingDetailsDialog meeting={data} />
              </>
            ) : (
              <MeetingDetailsDialog meeting={data} />
            )
          }
        />

        <StatStrip columns={4}>
          <StatTile
            label={ui("Thời lượng")}
            icon={<Clock />}
            iconClass="text-chart-1"
            value={formatClock(duration)}
            hint={recording ? ui("Đang ghi") : undefined}
          />
          <StatTile
            label={ui("Số câu")}
            icon={<MessageSquareText />}
            iconClass="text-chart-3"
            value={data.utterances.length}
          />
          <StatTile
            label={ui("Người nói")}
            icon={<Users />}
            iconClass="text-chart-6"
            value={data.speakers.length}
            hint={named > 0 ? ui("{{count}} đã đặt tên", { count: named }) : ui("Chưa đặt tên")}
          />
          <StatTile
            label={ui("Nhận dạng")}
            icon={<Mic />}
            iconClass={data.provider ? "text-chart-2" : "text-content-muted"}
            value={data.provider ?? "—"}
            hint={
              data.provider
                ? data.diarized
                  ? ui("Có tách người nói")
                  : ui("Không tách người nói")
                : undefined
            }
          />
        </StatStrip>

        {transcribing && (
          <p
            role="status"
            className="rounded-xl bg-status-info-surface px-4 py-3 text-sm text-status-info-content"
          >
            {ui("Đang nhận dạng bản ghi {{filename}}…", { filename: data.audio.filename ?? "" })}
          </p>
        )}
        {data.audio.status === "FAILED" && (
          <p
            role="alert"
            className="rounded-xl bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
          >
            {ui("Không nhận dạng được bản ghi. File đã được xoá.")}
          </p>
        )}
        {owned && data.status === "RECORDING" && !recording && !pending && (
          <p
            role="status"
            className="rounded-xl border border-border-default bg-surface-sunken px-4 py-3 text-sm text-content-secondary"
          >
            {ui("Cuộc họp chưa kết thúc nhưng không còn ghi.")}
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
              : ui("Chưa bật “Chia sẻ cả âm thanh của thẻ”, nên chỉ ghi giọng của bạn.")}
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
            {ui("Không nghe thấy tab cuộc họp hơn 20 giây. Có thể âm thanh tab chưa được chia sẻ.")}
          </Warning>
        )}
        {reconnecting && (
          <p
            role="status"
            className="rounded-xl bg-status-info-surface px-4 py-3 text-sm text-status-info-content"
          >
            {ui("Đang kết nối lại…")}
          </p>
        )}
        {stoppedBy && !recorder && (
          <p
            role="alert"
            className="rounded-xl bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
          >
            {socketMessage(stoppedBy, ui)}
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

        {/* The minutes take the reader's place as soon as they land, as ghiam-pro's conclusion tab does. */}
        <TranscriptCorrections
          meeting={data}
          enabled={data.owned && data.status === "ENDED" && data.utterances.length > 0}
        >
          {(corrections) => (
            <Tabs value={shown} onValueChange={setPane}>
              {/* What acts on the open tab sits at the right of the tab row; on a phone it drops below the tabs. */}
              <div className="flex flex-wrap items-center justify-between gap-2">
                {/* Five tabs are wider than a phone: they scroll inside their own row, or choosing one
                    scrolls the whole page sideways to reveal it. */}
                <TabsList className="max-w-full min-w-0 justify-start overflow-x-auto">
                  {data.minutes.status !== "NONE" && (
                    <TabsTrigger value="summary">{ui("Tóm tắt")}</TabsTrigger>
                  )}
                  {data.minutes.actions.length > 0 && (
                    <TabsTrigger value="actions">
                      {ui("Việc cần làm")}
                      <span className="ml-1 text-xs text-content-muted tabular-nums">
                        {data.minutes.actions.length}
                      </span>
                    </TabsTrigger>
                  )}
                  {data.minutes.decisions.length > 0 && (
                    <TabsTrigger value="decisions">
                      {ui("Quyết định")}
                      <span className="ml-1 text-xs text-content-muted tabular-nums">
                        {data.minutes.decisions.length}
                      </span>
                    </TabsTrigger>
                  )}
                  <TabsTrigger value="transcript">{ui("Transcript")}</TabsTrigger>
                  {owned && <TabsTrigger value="notes">{ui("Ghi chú của tôi")}</TabsTrigger>}
                </TabsList>
                {shown === "transcript" && corrections.trigger}
                {shown !== "transcript" && shown !== "notes" && data.minutes.status === "READY" && (
                  <MinutesActions meeting={data} ui={ui} />
                )}
              </div>
              {data.minutes.status !== "NONE" && (
                <TabsContent value="summary" className="pt-4">
                  <MinutesSummary meeting={data} ui={ui} />
                </TabsContent>
              )}
              <TabsContent value="actions" className="pt-4">
                <MinutesItems meeting={data} items={data.minutes.actions} kind="ACTION" ui={ui} />
              </TabsContent>
              <TabsContent value="decisions" className="pt-4">
                <MinutesItems
                  meeting={data}
                  items={data.minutes.decisions}
                  kind="DECISION"
                  ui={ui}
                />
              </TabsContent>
              <TabsContent value="transcript" className="grid gap-4 pt-4">
                <SpeakerSuggestions meeting={data} />
                {corrections.panel}
                <Transcript
                  meeting={data}
                  snapshot={recording ? snapshot : idle}
                  ui={ui}
                  onStar={star}
                />
              </TabsContent>
              {owned && (
                <TabsContent value="notes" className="pt-4">
                  <Notes meeting={data} ui={ui} />
                </TabsContent>
              )}
            </Tabs>
          )}
        </TranscriptCorrections>

        {owned && <Sharing meeting={data} ui={ui} />}

        {owned && data.status === "ENDED" && (
          <DangerZone
            icon={<Trash2 />}
            title={ui("Xoá cuộc họp này")}
            description={ui("Transcript, tên người nói và ghi chú sẽ mất vĩnh viễn.")}
            action={
              <ConfirmDialog
                trigger={
                  <Button tone="danger" prominence="secondary">
                    {ui("Xoá cuộc họp")}
                  </Button>
                }
                title={ui("Xoá {{v1}}?", { v1: data.title })}
                description={ui(
                  "Transcript, tên người nói và ghi chú của cuộc họp này sẽ bị xoá vĩnh viễn.",
                )}
                confirmLabel={ui("Xoá")}
                pendingLabel={ui("Đang xoá…")}
                confirmTone="danger"
                onConfirm={async () => {
                  await removeMeeting(meetingId);
                  cache.removeQueries({ queryKey: meetingKey(meetingId) });
                  void invalidateMeetingList(cache);
                  await navigate({ to: "/meetings" });
                }}
              />
            }
          />
        )}
      </SettingsLayout>
    </AppShell>
  );
}

/** Who else reads this meeting. Only its owner sees, or changes, this list. */
function Sharing({ meeting, ui }: { meeting: MeetingDetail; ui: Translate }) {
  const cache = useQueryClient();
  const [pending, setPending] = useState(false);
  const audience = {
    people: meeting.readers
      .filter((reader) => reader.kind === "MEMBER")
      .map((reader) => ({ actorId: reader.id, name: reader.name, email: null })),
    groups: meeting.readers
      .filter((reader) => reader.kind === "GROUP")
      .map((reader) => ({ id: reader.id, name: reader.name })),
  };

  async function save(next: MeetingAudience) {
    setPending(true);
    try {
      cache.setQueryData(
        meetingKey(meeting.id),
        await shareMeeting(
          meeting.id,
          next.people.map((person) => person.actorId),
          next.groups.map((group) => group.id),
        ),
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <section className="grid gap-2 rounded-xl border border-border-subtle px-4 py-4">
      <MeetingShareField
        label={ui("Chia sẻ")}
        value={audience}
        disabled={pending}
        onChange={(next) => void save(next)}
      />
    </section>
  );
}

/** What acts on the whole minutes, beside the tabs that show them. */
function MinutesActions({ meeting, ui }: { meeting: MeetingDetail; ui: Translate }) {
  const cache = useQueryClient();
  const [pending, setPending] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [opening, setOpening] = useState(false);
  const navigate = useNavigate();

  /** Publishes the minutes into the library, then opens a new conversation with them in the composer. */
  async function openInChat() {
    setOpening(true);
    try {
      const file = await publishMinutes(meeting.id);
      await navigate({ to: "/", search: { attach: file.fileId } });
    } finally {
      setOpening(false);
    }
  }

  async function rerun(discardEdits = false) {
    setPending(true);
    try {
      cache.setQueryData(meetingKey(meeting.id), await rerunMinutes(meeting.id, discardEdits));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-1">
      {meeting.owned &&
        (meeting.minutes.edited ? (
          <ConfirmDialog
            trigger={
              <Button size="sm" prominence="tertiary" pending={pending}>
                <RefreshCw aria-hidden="true" />
                {ui("Viết lại")}
              </Button>
            }
            title={ui("Viết lại tóm tắt?")}
            description={ui("Những chỗ bạn đã sửa sẽ bị thay bằng bản mới.")}
            confirmLabel={ui("Viết lại")}
            pendingLabel={ui("Đang viết lại…")}
            confirmTone="danger"
            onConfirm={() => rerun(true)}
          />
        ) : (
          <Button size="sm" prominence="tertiary" pending={pending} onClick={() => void rerun()}>
            <RefreshCw aria-hidden="true" />
            {ui("Viết lại")}
          </Button>
        ))}
      <Button size="sm" prominence="tertiary" pending={opening} onClick={() => void openInChat()}>
        <MessageSquareText aria-hidden="true" />
        {ui("Mở trong Chat")}
      </Button>
      <Button size="sm" prominence="secondary" onClick={() => setExporting(true)}>
        <FileDown aria-hidden="true" />
        {ui("Xuất biên bản")}
      </Button>
      {exporting && (
        <ExportMinutesDialog meeting={meeting} open onOpenChange={(next) => setExporting(next)} />
      )}
    </div>
  );
}

/** The model's account of the meeting, with what it is still doing or why it could not. */
function MinutesSummary({ meeting, ui }: { meeting: MeetingDetail; ui: Translate }) {
  const cache = useQueryClient();
  const [pending, setPending] = useState(false);
  const { status, generatedAt } = meeting.minutes;

  async function rerun() {
    setPending(true);
    try {
      cache.setQueryData(meetingKey(meeting.id), await rerunMinutes(meeting.id, false));
    } finally {
      setPending(false);
    }
  }

  if (status === "PENDING" || status === "RUNNING")
    return (
      <p
        role="status"
        className="rounded-xl bg-status-info-surface px-4 py-3 text-sm text-status-info-content"
      >
        {ui("Đang viết tóm tắt, quyết định và việc cần làm…")}
      </p>
    );
  if (status === "FAILED")
    return (
      <EmptyState
        role="alert"
        icon={<WifiOff />}
        title={ui("Chưa viết được tóm tắt")}
        detail={ui("Transcript vẫn còn nguyên. Thử lại khi mô hình sẵn sàng.")}
        action={
          <Button size="sm" prominence="secondary" pending={pending} onClick={() => void rerun()}>
            <RefreshCw aria-hidden="true" />
            {ui("Viết lại")}
          </Button>
        }
      />
    );
  return (
    <div className="grid gap-3">
      {generatedAt && (
        <p className="text-xs text-content-muted">
          {ui("Viết lúc {{when}}", { when: formatWhen(generatedAt, i18n.language) })}
        </p>
      )}
      <EditableSummary meeting={meeting} />
    </div>
  );
}

/** Decisions and action items, each with the sentence it rests on. */
function MinutesItems({
  meeting,
  items,
  kind,
  ui,
}: {
  meeting: MeetingDetail;
  items: MeetingMinutesItem[];
  kind: "ACTION" | "DECISION";
  ui: Translate;
}) {
  const cache = useQueryClient();
  const [failed, setFailed] = useState(false);
  // What the model missed is written in beside what it found, by the owner only.
  const add = meeting.owned && meeting.minutes.status === "READY" && (
    <NewItem
      meeting={meeting}
      kind={kind}
      label={kind === "ACTION" ? ui("Thêm việc") : ui("Thêm quyết định")}
    />
  );
  if (items.length === 0)
    return (
      <div className="grid gap-3">
        <EmptyState
          icon={kind === "ACTION" ? <CheckSquare /> : <Gavel />}
          title={
            kind === "ACTION" ? ui("Không có việc nào được giao") : ui("Không có quyết định nào")
          }
        />
        {add}
      </div>
    );

  async function toggle(item: MeetingMinutesItem, done: boolean) {
    setFailed(false);
    try {
      cache.setQueryData(meetingKey(meeting.id), await markMinutesItem(meeting.id, item.id, done));
    } catch {
      setFailed(true);
    }
  }

  return (
    <>
      {failed && (
        <p role="alert" className="mb-2 text-sm text-status-danger-content">
          {ui("Chưa lưu được thay đổi. Hãy thử lại.")}
        </p>
      )}
      <ul className="grid gap-1">
        {items.map((item) => (
          <li
            key={item.id}
            className="grid grid-cols-[auto_1fr] gap-x-3 rounded-lg px-2 py-2 hover:bg-surface-base"
          >
            {kind === "ACTION" ? (
              <Checkbox
                className="mt-1"
                checked={item.done}
                aria-label={ui("Đánh dấu xong: {{text}}", { text: item.text })}
                disabled={!meeting.owned}
                onCheckedChange={(checked) => void toggle(item, checked === true)}
              />
            ) : (
              <Gavel className="mt-1 size-4 text-content-muted" aria-hidden="true" />
            )}
            <div className="min-w-0">
              <EditableItem meeting={meeting} item={item} kind={kind}>
                <p
                  className={cn(
                    "text-content-primary",
                    item.done && "text-content-muted line-through",
                  )}
                >
                  {item.text}
                </p>
                {(item.owner || item.due) && (
                  <p className="mt-0.5 flex flex-wrap gap-3 text-xs text-content-secondary">
                    {item.owner && (
                      <span className="inline-flex items-center gap-1">
                        <Users className="size-3" aria-hidden="true" />
                        {item.owner}
                      </span>
                    )}
                    {item.due && (
                      <span className="inline-flex items-center gap-1">
                        <Clock className="size-3" aria-hidden="true" />
                        {item.due}
                      </span>
                    )}
                  </p>
                )}
                {item.quote && (
                  <p className="mt-1 text-xs text-content-muted">
                    {item.sourceUtteranceId && (
                      <button
                        type="button"
                        className="mr-1.5 font-mono text-action-selection hover:underline"
                        onClick={() => {
                          const line = meeting.utterances.find(
                            (utterance) => utterance.id === item.sourceUtteranceId,
                          );
                          if (line)
                            document.getElementById(line.id)?.scrollIntoView({ block: "center" });
                        }}
                      >
                        {formatClock(
                          meeting.utterances.find(
                            (utterance) => utterance.id === item.sourceUtteranceId,
                          )?.startMs ?? 0,
                        )}
                      </button>
                    )}
                    “{item.quote}”
                  </p>
                )}
              </EditableItem>
            </div>
          </li>
        ))}
      </ul>
      {add && <div className="mt-2 px-2">{add}</div>}
    </>
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
  onBookmark,
  ui,
}: {
  snapshot: RecorderSnapshot;
  meeting: MeetingDetail;
  onPause: () => void;
  onResume: () => void;
  onStop: () => Promise<void>;
  onBookmark: (atMs: number) => void;
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
      <Button
        prominence="tertiary"
        size="sm"
        disabled={snapshot.phase === "stopping"}
        onClick={() => onBookmark(snapshot.elapsedMs)}
      >
        <Bookmark aria-hidden="true" />
        {ui("Đánh dấu")}
      </Button>
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
        description={ui("Đã lưu đến {{time}}. Sau khi dừng, cuộc họp không ghi tiếp được.", {
          time: formatClock(snapshot.elapsedMs),
        })}
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
  onStar,
}: {
  meeting: MeetingDetail;
  snapshot: RecorderSnapshot;
  ui: Translate;
  onStar: (utteranceId: string, starred: boolean) => void;
}) {
  const [query, setQuery] = useState("");
  const [starredOnly, setStarredOnly] = useState(false);
  const [at, setAt] = useState(0);
  const starred = new Set(meeting.starred);
  const shown = starredOnly
    ? meeting.utterances.filter((utterance) => starred.has(utterance.id))
    : meeting.utterances;
  // Each hit is numbered across the whole transcript, so the arrows can walk them in reading order.
  let counted = 0;
  const firstMatch = new Map<string, number>();
  for (const utterance of shown) {
    firstMatch.set(utterance.id, counted);
    counted += matches(utterance.text, query).length;
  }
  const total = counted;
  const current = total === 0 ? -1 : ((at % total) + total) % total;

  /** The file is named after the meeting, so a folder of them reads as a folder of meetings. */
  async function take(format: "DOCX" | "PDF") {
    const file = await exportTranscript(meeting.id, format);
    const url = URL.createObjectURL(file);
    const link = Object.assign(window.document.createElement("a"), {
      href: url,
      download: `transcript-${slug(meeting.title)}.${format === "PDF" ? "pdf" : "docx"}`,
    });
    window.document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  function jump(step: number) {
    if (total === 0) return;
    const next = (((at + step) % total) + total) % total;
    setAt(next);
    document
      .getElementById(`meeting-match-${next}`)
      ?.scrollIntoView({ block: "center", behavior: "smooth" });
  }

  const previews = (
    Object.entries(snapshot.previews) as [
      MeetingTrack,
      { speaker: string; text: string } | undefined,
    ][]
  ).filter((entry): entry is [MeetingTrack, { speaker: string; text: string }] => !!entry[1]?.text);
  const tools = meeting.utterances.length > 0 && (
    <div className="flex flex-wrap items-center gap-2">
      <div className="flex items-center gap-1">
        <Input
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setAt(0);
          }}
          placeholder={ui("Tìm trong transcript")}
          aria-label={ui("Tìm trong transcript")}
          className="h-8 w-56"
        />
        {query.trim() !== "" && (
          <>
            <span className="text-xs text-content-muted tabular-nums">
              {total === 0 ? ui("Không thấy") : ui("{{at}}/{{total}}", { at: current + 1, total })}
            </span>
            <Button
              prominence="tertiary"
              size="sm"
              disabled={total === 0}
              aria-label={ui("Kết quả trước")}
              onClick={() => jump(-1)}
            >
              <ChevronUp aria-hidden="true" />
            </Button>
            <Button
              prominence="tertiary"
              size="sm"
              disabled={total === 0}
              aria-label={ui("Kết quả tiếp theo")}
              onClick={() => jump(1)}
            >
              <ChevronDown aria-hidden="true" />
            </Button>
          </>
        )}
      </div>
      {(starred.size > 0 || starredOnly) && (
        <Button
          prominence={starredOnly ? "secondary" : "tertiary"}
          size="sm"
          aria-pressed={starredOnly}
          onClick={() => setStarredOnly((only) => !only)}
        >
          <Star aria-hidden="true" className={starredOnly ? "fill-current" : undefined} />
          {ui("Câu đã đánh dấu ({{count}})", { count: starred.size })}
        </Button>
      )}
      <div className="ml-auto flex items-center gap-1">
        <Button prominence="tertiary" size="sm" onClick={() => void take("DOCX")}>
          <FileDown aria-hidden="true" />
          {ui("Tải Word")}
        </Button>
        <Button prominence="tertiary" size="sm" onClick={() => void take("PDF")}>
          <FileDown aria-hidden="true" />
          {ui("Tải PDF")}
        </Button>
      </div>
    </div>
  );

  if (meeting.utterances.length === 0 && previews.length === 0)
    return (
      <p className="rounded-xl border border-dashed border-border-default px-4 py-8 text-center text-sm text-content-muted">
        {snapshot.phase === "recording"
          ? ui("Đang nghe…")
          : meeting.status === "TRANSCRIBING"
            ? ui("Transcript sẽ hiện khi nhận dạng xong.")
            : ui("Cuộc họp này chưa có transcript.")}
      </p>
    );
  const topics = meeting.minutes.topics
    .map((topic) => ({
      topic,
      line: meeting.utterances.find((utterance) => utterance.id === topic.sourceUtteranceId),
    }))
    .filter((entry) => entry.line !== undefined);

  return (
    <div className="grid gap-3">
      {topics.length > 0 && !starredOnly && (
        <nav
          aria-label={ui("Dòng thời gian")}
          className="rounded-xl border border-border-default p-2"
        >
          <h3 className="px-2 pt-1 pb-1.5 text-xs font-medium text-content-muted">
            {ui("Dòng thời gian")}
          </h3>
          <ol className="grid gap-0.5">
            {topics.map(({ topic, line }) => (
              <li key={topic.id}>
                <button
                  type="button"
                  className="grid w-full grid-cols-[4.5rem_1fr] gap-x-3 rounded-lg px-2 py-1.5 text-left text-sm hover:bg-surface-base"
                  onClick={() =>
                    document
                      .getElementById(line!.id)
                      ?.scrollIntoView({ block: "start", behavior: "smooth" })
                  }
                >
                  <span className="font-mono text-xs text-content-muted tabular-nums">
                    {formatClock(line!.startMs)}
                  </span>
                  <span className="text-content-primary">{topic.text}</span>
                </button>
              </li>
            ))}
          </ol>
        </nav>
      )}
      {tools}
      {shown.length === 0 && (
        <p className="text-sm text-content-muted">{ui("Chưa đánh dấu câu nào.")}</p>
      )}
      <ol className="grid gap-1" aria-live="polite" aria-relevant="additions">
        {shown.map((utterance) => (
          <li
            key={utterance.id}
            id={utterance.id}
            className="grid scroll-mt-24 grid-cols-[4.5rem_1fr_auto] gap-x-3 rounded-lg px-2 py-2 hover:bg-surface-base"
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
              <p className="mt-0.5 text-content-secondary">
                <Said
                  text={utterance.text}
                  spans={utterance.spans}
                  query={query}
                  firstMatch={firstMatch.get(utterance.id) ?? 0}
                  currentMatch={current}
                  unsure={
                    meeting.owned && meeting.status === "ENDED"
                      ? (span, mark) => (
                          <WordCorrection
                            meeting={meeting}
                            utteranceId={utterance.id}
                            text={utterance.text}
                            span={span}
                          >
                            {mark}
                          </WordCorrection>
                        )
                      : undefined
                  }
                />
              </p>
            </div>
            <Button
              prominence="tertiary"
              size="sm"
              className="self-start"
              aria-pressed={starred.has(utterance.id)}
              aria-label={ui("Đánh dấu câu này")}
              onClick={() => onStar(utterance.id, !starred.has(utterance.id))}
            >
              <Star
                aria-hidden="true"
                className={starred.has(utterance.id) ? "fill-current" : "opacity-40"}
              />
            </Button>
          </li>
        ))}
        {previews.map(([track, preview]) => (
          <li
            key={`preview-${track}`}
            className="grid grid-cols-[4.5rem_1fr_auto] gap-x-3 px-2 py-2"
          >
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
    </div>
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
      <PopoverTrigger asChild disabled={!meeting.owned}>
        <button
          type="button"
          className="inline-flex items-center gap-1.5 rounded text-sm font-medium text-content-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring enabled:hover:underline"
          disabled={!meeting.owned}
          aria-label={meeting.owned ? ui("Đặt tên cho {{name}}", { name: display }) : display}
        >
          {dot}
          {display}
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="grid w-64 gap-2 p-2">
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
  const latest = useRef(meeting.notes);
  const stored = useRef(meeting.notes);
  // Saves run one after another, so the second never reuses the revision the first is about to replace.
  const queue = useRef<Promise<void>>(Promise.resolve());

  useEffect(() => {
    revision.current = meeting.revision;
  }, [meeting.revision]);
  useEffect(() => () => window.clearTimeout(timer.current), []);

  function change(next: string) {
    latest.current = next;
    setValue(next);
    setState("dirty");
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(flush, 1_200);
  }

  function flush() {
    window.clearTimeout(timer.current);
    queue.current = queue.current.then(save);
  }

  async function save() {
    const next = latest.current;
    if (next === stored.current) {
      setState((current) => (current === "conflict" ? current : "saved"));
      return;
    }
    setState("saving");
    try {
      const saved = await saveMeetingNotes(meeting.id, next, revision.current);
      revision.current = saved.revision;
      stored.current = next;
      cache.setQueryData(meetingKey(meeting.id), (current: MeetingDetail | undefined) =>
        current ? { ...current, notes: saved.notes, revision: saved.revision } : saved,
      );
      setState(latest.current === next ? "saved" : "dirty");
    } catch (failed) {
      setState(failed instanceof ApiError && failed.status === 409 ? "conflict" : "dirty");
    }
  }

  return (
    <div className="grid gap-2">
      <Textarea
        id={`${id}-notes`}
        value={value}
        aria-label={ui("Ghi chú của tôi")}
        maxLength={50_000}
        rows={12}
        onChange={(event) => change(event.target.value)}
        onBlur={() => latest.current !== stored.current && flush()}
      />
      <p className="text-xs text-content-muted" role="status">
        {state === "saving"
          ? ui("Đang lưu…")
          : state === "saved"
            ? ui("Đã lưu")
            : state === "conflict"
              ? ui("Ghi chú vừa đổi ở nơi khác. Tải lại trang.")
              : ui("Chưa lưu")}
      </p>
    </div>
  );
}
