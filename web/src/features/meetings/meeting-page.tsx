import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import {
  Clock,
  FileAudio,
  Lock,
  MessageSquareText,
  Mic,
  MonitorSpeaker,
  Square,
  Trash2,
  Users,
  WifiOff,
} from "lucide-react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { BrandLoader } from "@/components/brand-loader";
import { DangerZone } from "@/components/composites/danger-zone";
import { EmptyState } from "@/components/composites/empty-state";
import { StatStrip, StatTile } from "@/components/composites/stat-strip";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { i18n } from "@/i18n";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import {
  captureSupport,
  openMeetingSources,
  pickMeetingTab,
  ShareCancelledError,
} from "./meeting-capture";
import { MeetingDetailsDialog } from "./meeting-details-dialog";
import { MinutesActions, MinutesItems, MinutesSummary } from "./meeting-minutes";
import { MeetingNotes } from "./meeting-notes";
import {
  endMeeting,
  startRecording,
  useActiveMeeting,
  useRecordingFailure,
} from "./meeting-session";
import { MeetingSharing } from "./meeting-sharing";
import { Transcript, type TranscriptTarget } from "./meeting-transcript";
import { RecordingBar, RecordingClock } from "./recording-bar";
import { useRecorderValue } from "./recorder-state";
import { SpeakerSuggestions } from "./speaker-suggestions";
import { TranscriptCorrections } from "./transcript-corrections";
import {
  addBookmark,
  formatClock,
  formatWhen,
  loadMeeting,
  meetingKey,
  invalidateMeetingList,
  patchMeeting,
  removeMeeting,
  setUtteranceStar,
  trackOffsets,
} from "./meetings-api";

type Translate = ReturnType<typeof useAppTranslation>;

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
  // The page follows only what changes its layout; the clock, the meters and the live sentences subscribe
  // where they are shown, so a level reading does not re-render the transcript and the minutes.
  const phase = useRecorderValue(recorder, (snapshot) => snapshot.phase);
  const hasTab = useRecorderValue(recorder, (snapshot) =>
    snapshot.tracks.some((track) => track.track === "TAB"),
  );
  const tabEnded = useRecorderValue(recorder, (snapshot) =>
    snapshot.tracks.some((track) => track.track === "TAB" && track.ended),
  );
  const tabQuiet = useRecorderValue(recorder, (snapshot) =>
    snapshot.tracks.some((track) => track.track === "TAB" && track.quiet && !track.ended),
  );
  const reconnecting = useRecorderValue(recorder, (snapshot) =>
    snapshot.tracks.some((track) => track.reconnecting),
  );
  const failure = useRecordingFailure();
  const stoppedBy = failure?.meetingId === meetingId ? failure.code : undefined;
  const [tabMissing, setTabMissing] = useState(!!tabAudioMissing);
  const [pane, setPane] = useState<string>();
  const [actionError, setActionError] = useState<string>();
  const [pending, setPending] = useState(false);
  const [target, setTarget] = useState<TranscriptTarget>();

  if (meeting.isPending)
    return (
      <>
        <AppShellHeader title={ui("Cuộc họp")} />
        <SettingsLayout wide className="gap-6 md:pt-8">
          <PageHeader title={ui("Cuộc họp")} icon={<Mic />} />
          <div
            role="status"
            className="flex justify-center rounded-xl border border-border-subtle px-6 py-20"
          >
            <BrandLoader label={ui("Đang tải cuộc họp")} />
          </div>
        </SettingsLayout>
      </>
    );
  if (meeting.isError)
    return (
      <>
        <AppShellHeader title={ui("Cuộc họp")} />
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
      </>
    );
  const data = meeting.data;
  const recording =
    !!recorder && (phase === "recording" || phase === "paused" || phase === "stopping");
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

  /** A mark is the reader's own, so it is applied straight away and answers with the reader's marks. */
  function star(utteranceId: string, starred: boolean) {
    void (async () => {
      setActionError(undefined);
      try {
        const marked = await setUtteranceStar(meetingId, utteranceId, starred);
        patchMeeting(cache, meetingId, (current) => ({ ...current, starred: marked }));
      } catch (failed) {
        setActionError(problemMessage(presentProblem(failed, "mutation").message));
      }
    })();
  }

  function bookmark(atMs: number) {
    void (async () => {
      setActionError(undefined);
      try {
        const bookmarks = await addBookmark(meetingId, atMs);
        patchMeeting(cache, meetingId, (current) => ({ ...current, bookmarks }));
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

  /** Opens the transcript on one line, from wherever the page quotes it. */
  function reveal(utteranceId: string) {
    setPane("transcript");
    setTarget((current) => ({ utteranceId, seq: (current?.seq ?? 0) + 1 }));
  }

  const named = data.speakers.filter((speaker) => speaker.name).length;

  return (
    <>
      <AppShellHeader title={data.title} />
      {recording && (
        <RecordingBar recorder={recorder} kind={data.kind} onStop={end} onBookmark={bookmark} />
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
            value={
              recording ? (
                <RecordingClock recorder={recorder} />
              ) : (
                formatClock(
                  data.utterances.reduce(
                    (longest, utterance) => Math.max(longest, utterance.endMs),
                    0,
                  ),
                )
              )
            }
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
        {recording && data.kind === "ONLINE" && (tabMissing || tabEnded) && (
          <Warning
            action={
              <Button size="sm" prominence="secondary" onClick={() => void shareTab()}>
                {ui("Chọn tab họp")}
              </Button>
            }
          >
            {tabEnded
              ? ui("Tab cuộc họp đã dừng chia sẻ, nên chỉ còn ghi giọng của bạn.")
              : ui("Chưa bật “Chia sẻ cả âm thanh của thẻ”, nên chỉ ghi giọng của bạn.")}
          </Warning>
        )}
        {recording && hasTab && tabQuiet && (
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
                  <MinutesActions meeting={data} />
                )}
              </div>
              {data.minutes.status !== "NONE" && (
                <TabsContent value="summary" className="pt-4">
                  <MinutesSummary meeting={data} />
                </TabsContent>
              )}
              <TabsContent value="actions" className="pt-4">
                <MinutesItems
                  meeting={data}
                  items={data.minutes.actions}
                  kind="ACTION"
                  onReveal={reveal}
                />
              </TabsContent>
              <TabsContent value="decisions" className="pt-4">
                <MinutesItems
                  meeting={data}
                  items={data.minutes.decisions}
                  kind="DECISION"
                  onReveal={reveal}
                />
              </TabsContent>
              <TabsContent value="transcript" className="grid gap-4 pt-4">
                <SpeakerSuggestions meeting={data} onReveal={reveal} />
                {corrections.panel}
                <Transcript
                  meeting={data}
                  recorder={recording ? recorder : undefined}
                  target={target}
                  onStar={star}
                />
              </TabsContent>
              {owned && (
                <TabsContent value="notes" className="pt-4">
                  <MeetingNotes meeting={data} />
                </TabsContent>
              )}
            </Tabs>
          )}
        </TranscriptCorrections>

        {owned && <MeetingSharing meeting={data} />}

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
