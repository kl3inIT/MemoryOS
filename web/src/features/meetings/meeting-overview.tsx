import type { ReactNode } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { MeetingDetail } from "./meetings-api";
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
          {ui("Không nhận dạng được bản ghi. File đã được xóa.")}
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
