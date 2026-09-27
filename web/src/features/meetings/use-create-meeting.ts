import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  createMeetingMutation,
  shareMeetingMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { MeetingCreateRequest } from "@/lib/hey-api/types.gen";
import {
  invalidateMeetingList,
  meetingQueryKey,
  shareBody,
  type MeetingAudience,
  type MeetingDetail,
} from "./meetings-api";

/**
 * Creates a meeting and, once its content is on its way, shares it and caches it for the page it opens on. The
 * recording and the upload dialogs differ only in what happens between the two.
 */
export function useCreateMeeting() {
  const cache = useQueryClient();
  const create = useMutation(createMeetingMutation());
  const share = useMutation(shareMeetingMutation());
  return {
    create: (body: MeetingCreateRequest) => create.mutateAsync({ body }),
    /** Shares the meeting with the chosen audience, if any, and caches it as the meeting page will read it. */
    publish: async (meeting: MeetingDetail, audience: MeetingAudience) => {
      const readers =
        audience.people.length > 0 || audience.groups.length > 0
          ? await share.mutateAsync({ path: { meetingId: meeting.id }, body: shareBody(audience) })
          : meeting.readers;
      cache.setQueryData(meetingQueryKey(meeting.id), { ...meeting, readers });
      void invalidateMeetingList(cache);
    },
  };
}
