import type { ReactNode } from "react";
import { CalendarDays, Clock, FileAudio, Lock, Mic, MonitorSpeaker, Users } from "lucide-react";
import { Sheet, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { i18n } from "@/i18n";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { MeetingRecorder } from "./meeting-recorder";
import { formatClock, formatWhen, type MeetingDetail, type TimelineEntry } from "./meetings-api";
import { RecordingClock } from "./recording-bar";
import type { TranscriptTarget } from "./meeting-transcript";
import { SpeakerChip } from "./speaker-chip";
import { SpeakerSuggestions } from "./speaker-suggestions";
import { TranscriptTimeline } from "./transcript-timeline";

/**
 * What the reader steers a meeting by, beside the page (Lightfield's meeting details): when it was, how long it
 * ran, how it was captured, who was in it and who reads it, then its timeline and its voices. Beside a wide page
 * it stays in view while the page scrolls, so a meeting hours long is never further than one press from any of
 * its subjects; a page too narrow for it opens it from the shell header.
 */
export function MeetingPanel({
  meeting,
  recorder,
  timeline,
  reading,
  wide,
  open,
  onOpenChange,
  onReach,
}: {
  meeting: MeetingDetail;
  /** The recorder while this meeting is being recorded here; its clock replaces the stored length. */
  recorder: MeetingRecorder | undefined;
  timeline: TimelineEntry[];
  /** The place in the timeline the transcript is being read at. */
  reading: string | undefined;
  wide: boolean;
  /** Whether the panel is open over a narrow page; beside a wide one it always stands. */
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onReach: (utteranceId: string, block: TranscriptTarget["block"]) => void;
}) {
  const ui = useAppTranslation();
  const body = (
    <div className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto px-4 pb-6">
      <MeetingFacts meeting={meeting} recorder={recorder} />
      <TranscriptTimeline
        entries={timeline}
        current={reading}
        marking={!!recorder}
        onReach={(utteranceId) => onReach(utteranceId, "start")}
      />
      <MeetingVoices meeting={meeting} onReveal={(utteranceId) => onReach(utteranceId, "center")} />
    </div>
  );
  if (!wide)
    return (
      <Sheet open={open} onOpenChange={onOpenChange}>
        <SheetContent aria-describedby={undefined} className="w-full sm:max-w-sm">
          <SheetHeader>
            <SheetTitle>{ui("Chi tiết cuộc họp")}</SheetTitle>
          </SheetHeader>
          {body}
        </SheetContent>
      </Sheet>
    );
  return (
    // As tall as the window under the shell header, whose height is 3.5rem.
    <aside
      aria-label={ui("Chi tiết cuộc họp")}
      className="sticky top-0 flex h-[calc(100dvh-3.5rem)] w-80 shrink-0 flex-col border-l border-border-subtle"
    >
      <h2 className="px-4 py-3 font-main-ui-action text-content-primary">
        {ui("Chi tiết cuộc họp")}
      </h2>
      {body}
    </aside>
  );
}

/** When the meeting was, how long it ran, how it was captured, who else reads it and who was in it. */
function MeetingFacts({
  meeting,
  recorder,
}: {
  meeting: MeetingDetail;
  recorder: MeetingRecorder | undefined;
}) {
  const ui = useAppTranslation();
  const uploaded = meeting.audio.status !== "NONE";
  const lengthMs = meeting.utterances.reduce(
    (longest, utterance) => Math.max(longest, utterance.endMs),
    0,
  );
  return (
    <dl className="grid gap-3 text-sm">
      <Fact icon={<CalendarDays />} label={ui("Ngày họp")}>
        {formatWhen(meeting.createdAt, i18n.language)}
      </Fact>
      {/* A recording still being read has no length to show yet. */}
      {(recorder || lengthMs > 0) && (
        <Fact icon={<Clock />} label={ui("Thời lượng")}>
          <span className="tabular-nums">
            {recorder ? <RecordingClock recorder={recorder} /> : formatClock(lengthMs)}
          </span>
        </Fact>
      )}
      <Fact
        icon={uploaded ? <FileAudio /> : meeting.kind === "ONLINE" ? <MonitorSpeaker /> : <Mic />}
        label={ui("Hình thức")}
      >
        {uploaded
          ? ui("Bản ghi tải lên")
          : meeting.kind === "ONLINE"
            ? ui("Họp online")
            : ui("Họp trực tiếp")}
      </Fact>
      <Fact
        icon={meeting.owned && meeting.readers.length === 0 ? <Lock /> : <Users />}
        label={ui("Chia sẻ")}
      >
        {meeting.owned
          ? meeting.readers.length === 0
            ? ui("Chỉ mình bạn")
            : ui("Chia sẻ với {{count}} người và nhóm", { count: meeting.readers.length })
          : ui("Được chia sẻ với bạn")}
      </Fact>
      {meeting.participants.length > 0 && (
        <Fact icon={<Users />} label={ui("Thành phần")}>
          {meeting.participants.join(", ")}
        </Fact>
      )}
    </dl>
  );
}

/**
 * Every voice heard in the meeting; its owner names one from here without looking for a line it said, and is
 * offered the names the voices gave themselves.
 */
function MeetingVoices({
  meeting,
  onReveal,
}: {
  meeting: MeetingDetail;
  onReveal: (utteranceId: string) => void;
}) {
  const ui = useAppTranslation();
  if (meeting.speakers.length === 0) return null;
  return (
    <section className="flex flex-col gap-2">
      <h3 className="text-xs font-medium text-content-muted">{ui("Người nói")}</h3>
      <ul className="grid gap-2">
        {meeting.speakers.map((speaker) => (
          <li key={`${speaker.track}/${speaker.label}`} className="flex">
            <SpeakerChip meeting={meeting} track={speaker.track} label={speaker.label} />
          </li>
        ))}
      </ul>
      <SpeakerSuggestions meeting={meeting} onReveal={onReveal} />
    </section>
  );
}

function Fact({ icon, label, children }: { icon: ReactNode; label: string; children: ReactNode }) {
  return (
    <div className="flex gap-3">
      <dt className="flex w-28 shrink-0 items-center gap-2 self-start text-content-muted [&_svg]:size-4">
        <span aria-hidden="true">{icon}</span>
        {label}
      </dt>
      <dd className="min-w-0 flex-1 text-content-primary">{children}</dd>
    </div>
  );
}
