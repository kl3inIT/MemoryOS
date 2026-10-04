import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import { MeetingPage } from "@/features/meetings/meeting-page";
import { MEETING_PANES } from "@/features/meetings/meeting-panes";

export const Route = createFileRoute("/_authenticated/meetings_/$meetingId")({
  /**
   * `tabAudio=missing` says an online meeting started without the tab's audio; `tab` names the open tab, so a
   * reload or a shared link opens the same one.
   */
  validateSearch: z.object({
    tabAudio: z.literal("missing").optional().catch(undefined),
    tab: z.enum(MEETING_PANES).optional().catch(undefined),
  }),
  component: function MeetingRoute() {
    const { meetingId } = Route.useParams();
    const { tabAudio, tab } = Route.useSearch();
    const navigate = Route.useNavigate();
    return (
      <MeetingPage
        key={meetingId}
        meetingId={meetingId}
        tabAudioMissing={tabAudio === "missing"}
        pane={tab}
        // Naming the tab in the address must not move the page: a jump to a line names the tab on its way there.
        onPane={(next) =>
          void navigate({
            search: (current) => ({ ...current, tab: next }),
            replace: true,
            resetScroll: false,
          })
        }
      />
    );
  },
});
