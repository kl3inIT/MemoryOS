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

export function speakerColor(meeting: MeetingDetail, track: MeetingTrack, label: string) {
  const index = meeting.speakers.findIndex(
    (speaker) => speaker.track === track && speaker.label === label,
  );
  return SPEAKER_COLORS[Math.max(0, index) % SPEAKER_COLORS.length];
}
