import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Card, CardContent } from "@/components/ui/card";
import { FieldError } from "@/components/ui/field";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { shareMeetingMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { MeetingShareField } from "./meeting-share-field";
import {
  audienceOf,
  patchMeeting,
  shareBody,
  type MeetingAudience,
  type MeetingDetail,
} from "./meetings-api";
import { useFailureText } from "./use-failure-text";

/** Who else reads this meeting. Only its owner sees, or changes, this list. */
export function MeetingSharing({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const failureText = useFailureText();
  const share = useMutation({
    ...shareMeetingMutation(),
    onSuccess: (readers) => patchMeeting(cache, meeting.id, (current) => ({ ...current, readers })),
  });

  function save(next: MeetingAudience) {
    share.mutate({ path: { meetingId: meeting.id }, body: shareBody(next) });
  }

  return (
    <Card size="sm">
      <CardContent>
        <MeetingShareField
          label={ui("Chia sẻ")}
          value={audienceOf(meeting)}
          disabled={share.isPending}
          onChange={save}
        />
        {share.isError && <FieldError>{failureText(share.error)}</FieldError>}
      </CardContent>
    </Card>
  );
}
