import type { useAppTranslation } from "@/i18n/use-app-translation";
import type { MeetingTrack } from "./meeting-socket";
import type { MeetingDetail } from "./meetings-api";

type Translate = ReturnType<typeof useAppTranslation>;

/** Online, the microphone is the owner; every other voice is a numbered speaker until someone names it. */
export function speakerName(
  meeting: MeetingDetail,
  track: MeetingTrack,
  label: string,
  ui: Translate,
) {
  const named = meeting.speakers.find(
    (speaker) => speaker.track === track && speaker.label === label,
  )?.name;
  if (named) return named;
  if (meeting.kind === "ONLINE" && track === "MIC") return ui("Bạn");
  return ui("Người nói {{label}}", { label });
}

/**
 * What a voice's badge reads: the number of a voice nobody has named, or the first letter of the name it goes by,
 * which in a Vietnamese name is the last word.
 */
export function speakerInitial(
  meeting: MeetingDetail,
  track: MeetingTrack,
  label: string,
  ui: Translate,
) {
  const named = meeting.speakers.find(
    (speaker) => speaker.track === track && speaker.label === label,
  )?.name;
  if (!named && !(meeting.kind === "ONLINE" && track === "MIC")) return label;
  const given = speakerName(meeting, track, label, ui).trim().split(/\s+/).at(-1) ?? "";
  return [...given][0]?.toUpperCase() ?? label;
}

// Pastel fills with no orange or green among them: those are what the transcript marks unsure and corrected
// words with, and a badge of either beside those words would read as one of them.
const SPEAKER_COLORS = [
  "bg-speaker-1",
  "bg-speaker-2",
  "bg-speaker-3",
  "bg-speaker-4",
  "bg-speaker-5",
  "bg-speaker-6",
];

export function speakerColor(meeting: MeetingDetail, track: MeetingTrack, label: string) {
  const index = meeting.speakers.findIndex(
    (speaker) => speaker.track === track && speaker.label === label,
  );
  return SPEAKER_COLORS[Math.max(0, index) % SPEAKER_COLORS.length];
}
