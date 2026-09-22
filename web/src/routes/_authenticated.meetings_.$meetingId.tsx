import { createFileRoute } from "@tanstack/react-router";
import { MeetingPage } from "@/features/meetings/meeting-page";

export const Route = createFileRoute("/_authenticated/meetings_/$meetingId")({
  /** `tabAudio=missing` says an online meeting started without the tab's audio. */
  validateSearch: (search: Record<string, unknown>): { tabAudio?: "missing" } =>
    search.tabAudio === "missing" ? { tabAudio: "missing" } : {},
  component: function MeetingRoute() {
    const { meetingId } = Route.useParams();
    const { tabAudio } = Route.useSearch();
    return (
      <MeetingPage key={meetingId} meetingId={meetingId} tabAudioMissing={tabAudio === "missing"} />
    );
  },
});
