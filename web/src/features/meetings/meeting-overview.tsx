import type { ReactNode } from "react";
import {
  Clock,
  FileAudio,
  Lock,
  MessageSquareText,
  Mic,
  MonitorSpeaker,
  Users,
} from "lucide-react";
import { StatStrip, StatTile } from "@/components/composites/stat-strip";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { i18n } from "@/i18n";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { MeetingRecorder } from "./meeting-recorder";
import { formatClock, formatWhen, type MeetingDetail } from "./meetings-api";
import { RecordingClock } from "./recording-bar";
import type { MeetingPageState } from "./use-meeting-page";

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

/** When the meeting was, how it was captured, who was in it and who else reads it. */
export function MeetingFacts({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  const uploaded = meeting.audio.status !== "NONE";
  return (
    <span className="flex flex-wrap gap-x-4 gap-y-1">
      <span>{formatWhen(meeting.createdAt, i18n.language)}</span>
      <span className="inline-flex items-center gap-1">
        {uploaded ? (
          <FileAudio className="size-3.5" aria-hidden="true" />
        ) : meeting.kind === "ONLINE" ? (
          <MonitorSpeaker className="size-3.5" aria-hidden="true" />
        ) : (
          <Mic className="size-3.5" aria-hidden="true" />
        )}
        {uploaded
          ? ui("Bản ghi tải lên")
          : meeting.kind === "ONLINE"
            ? ui("Họp online")
            : ui("Họp trực tiếp")}
      </span>
      {meeting.participants.length > 0 && (
        <span className="inline-flex items-center gap-1">
          <Users className="size-3.5" aria-hidden="true" />
          {meeting.participants.join(", ")}
        </span>
      )}
      <span className="inline-flex items-center gap-1">
        {meeting.owned && meeting.readers.length === 0 ? (
          <>
            <Lock className="size-3.5" aria-hidden="true" />
            {ui("Chỉ mình bạn")}
          </>
        ) : (
          <>
            <Users className="size-3.5" aria-hidden="true" />
            {meeting.owned
              ? ui("Chia sẻ với {{count}} người và nhóm", { count: meeting.readers.length })
              : ui("Được chia sẻ với bạn")}
          </>
        )}
      </span>
    </span>
  );
}

/** How long, how many lines and voices, and who transcribed it. */
export function MeetingStats({
  meeting,
  recorder,
}: {
  meeting: MeetingDetail;
  /** The recorder while this meeting is being recorded here; its clock replaces the stored length. */
  recorder: MeetingRecorder | undefined;
}) {
  const ui = useAppTranslation();
  const named = meeting.speakers.filter((speaker) => speaker.name).length;
  return (
    <StatStrip columns={4}>
      <StatTile
        label={ui("Thời lượng")}
        icon={<Clock />}
        iconClass="text-chart-1"
        value={
          recorder ? (
            <RecordingClock recorder={recorder} />
          ) : (
            formatClock(
              meeting.utterances.reduce(
                (longest, utterance) => Math.max(longest, utterance.endMs),
                0,
              ),
            )
          )
        }
        hint={recorder ? ui("Đang ghi") : undefined}
      />
      <StatTile
        label={ui("Số câu")}
        icon={<MessageSquareText />}
        iconClass="text-chart-3"
        value={meeting.utterances.length}
      />
      <StatTile
        label={ui("Người nói")}
        icon={<Users />}
        iconClass="text-chart-6"
        value={meeting.speakers.length}
        hint={named > 0 ? ui("{{count}} đã đặt tên", { count: named }) : ui("Chưa đặt tên")}
      />
      <StatTile
        label={ui("Nhận dạng")}
        icon={<Mic />}
        iconClass={meeting.provider ? "text-chart-2" : "text-content-muted"}
        value={meeting.provider ?? "—"}
        hint={
          meeting.provider
            ? meeting.diarized
              ? ui("Có tách người nói")
              : ui("Không tách người nói")
            : undefined
        }
      />
    </StatStrip>
  );
}

/** What the page says about the recording and the last action: its state, a missing tab, a failure. */
export function MeetingNotices({
  meeting,
  page,
}: {
  meeting: MeetingDetail;
  page: MeetingPageState;
}) {
  const ui = useAppTranslation();
  const { live, tabMissing, resuming, actionError } = page;
  const { recording } = live;
  return (
    <>
      {meeting.status === "TRANSCRIBING" && (
        <Notice variant="info">
          {ui("Đang nhận dạng bản ghi {{filename}}…", { filename: meeting.audio.filename ?? "" })}
        </Notice>
      )}
      {meeting.audio.status === "FAILED" && (
        <Notice variant="destructive" alert>
          {ui("Không nhận dạng được bản ghi. File đã được xoá.")}
        </Notice>
      )}
      {meeting.owned && meeting.status === "RECORDING" && !recording && !resuming && (
        <Notice variant="default">{ui("Cuộc họp chưa kết thúc nhưng không còn ghi.")}</Notice>
      )}
      {recording && meeting.kind === "ONLINE" && (tabMissing || live.tabEnded) && (
        <Notice
          variant="warning"
          action={
            <Button size="sm" prominence="secondary" onClick={page.shareTab}>
              {ui("Chọn tab họp")}
            </Button>
          }
        >
          {live.tabEnded
            ? ui("Tab cuộc họp đã dừng chia sẻ, nên chỉ còn ghi giọng của bạn.")
            : ui("Chưa bật “Chia sẻ cả âm thanh của thẻ”, nên chỉ ghi giọng của bạn.")}
        </Notice>
      )}
      {recording && live.hasTab && live.tabQuiet && (
        <Notice
          variant="warning"
          action={
            <Button size="sm" prominence="secondary" onClick={page.shareTab}>
              {ui("Chọn lại tab")}
            </Button>
          }
        >
          {ui("Không nghe thấy tab cuộc họp hơn 20 giây. Có thể âm thanh tab chưa được chia sẻ.")}
        </Notice>
      )}
      {live.reconnecting && <Notice variant="info">{ui("Đang kết nối lại…")}</Notice>}
      {live.stoppedBy && !live.recorder && (
        <Notice variant="destructive" alert>
          {socketMessage(live.stoppedBy, ui)}
        </Notice>
      )}
      {actionError && (
        <Notice variant="destructive" alert>
          {actionError}
        </Notice>
      )}
    </>
  );
}

function Notice({
  variant,
  alert = false,
  action,
  children,
}: {
  variant: "default" | "info" | "warning" | "destructive";
  /** A failure is announced at once; a state is announced politely. */
  alert?: boolean;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <Alert variant={variant} role={alert ? "alert" : "status"}>
      <AlertTitle>{children}</AlertTitle>
      {action && <AlertDescription className="mt-1.5">{action}</AlertDescription>}
    </Alert>
  );
}
