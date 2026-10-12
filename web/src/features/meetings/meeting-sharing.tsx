import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Share2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
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

/**
 * Who else reads this meeting. Only its owner sees, or changes, this list. It opens from the header, so it is as
 * close at the end of a long transcript as at its start; each change is saved as it is made.
 */
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
    <Dialog>
      <DialogTrigger asChild>
        <Button prominence="secondary">
          <Share2 data-icon="inline-start" aria-hidden="true" />
          {ui("Chia sẻ")}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{ui("Chia sẻ cuộc họp")}</DialogTitle>
          <DialogDescription>
            {ui(
              "Người được chia sẻ đọc transcript và biên bản, kể cả khi cuộc họp đang ghi. Ghi chú của bạn vẫn riêng tư.",
            )}
          </DialogDescription>
        </DialogHeader>
        <MeetingShareField
          label={ui("Người và nhóm được đọc")}
          value={audienceOf(meeting)}
          disabled={share.isPending}
          onChange={save}
        />
        {share.isError && <FieldError>{failureText(share.error)}</FieldError>}
        <p role="status" className="text-xs text-content-muted">
          {share.isPending ? ui("Đang lưu…") : share.isSuccess ? ui("Đã lưu") : null}
        </p>
      </DialogContent>
    </Dialog>
  );
}
