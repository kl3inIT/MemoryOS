import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import { MeetingPage } from "@/features/meetings/meeting-page";

export const Route = createFileRoute("/_authenticated/meetings_/$meetingId")({
  /** `tabAudio=missing` says an online meeting started without the tab's audio. */
  validateSearch: z.object({ tabAudio: z.literal("missing").optional().catch(undefined) }),
  component: function MeetingRoute() {
    const { meetingId } = Route.useParams();
    const { tabAudio } = Route.useSearch();
    return (
      <MeetingPage key={meetingId} meetingId={meetingId} tabAudioMissing={tabAudio === "missing"} />
    );
  },
});
