import { Mic, Square, Trash2, WifiOff } from "lucide-react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { BrandLoader } from "@/components/brand-loader";
import { DangerZone } from "@/components/composites/danger-zone";
import { EmptyState } from "@/components/composites/empty-state";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { captureSupport } from "./meeting-capture";
import { MeetingDetailsDialog } from "./meeting-details-dialog";
import { MeetingFacts, MeetingNotices, MeetingStats } from "./meeting-overview";
import { MeetingSharing } from "./meeting-sharing";
import { MeetingTabs } from "./meeting-tabs";
import type { MeetingDetail } from "./meetings-api";
import { RecordingBar } from "./recording-bar";
import { useMeetingPage, type MeetingPageState } from "./use-meeting-page";

export function MeetingPage({
  meetingId,
  tabAudioMissing,
}: {
  meetingId: string;
  tabAudioMissing?: boolean;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const page = useMeetingPage(meetingId, !!tabAudioMissing);
  const { meeting } = page;

  if (meeting.isPending || meeting.isError)
    return (
      <>
        <AppShellHeader title={ui("Cuộc họp")} />
        <SettingsLayout wide className="gap-6 md:pt-8">
          <PageHeader title={ui("Cuộc họp")} icon={<Mic />} />
          {meeting.isPending ? (
            <div
              role="status"
              className="flex justify-center rounded-xl border border-border-subtle px-6 py-20"
            >
              <BrandLoader label={ui("Đang tải cuộc họp")} />
            </div>
          ) : (
            <EmptyState
              role="alert"
              icon={<WifiOff />}
              title={ui("Chưa xem được cuộc họp này")}
              detail={problemMessage(presentProblem(meeting.error, "initialLoad").message)}
              action={
                <Button size="sm" prominence="secondary" onClick={() => void meeting.refetch()}>
                  {ui("Thử lại")}
                </Button>
              }
            />
          )}
        </SettingsLayout>
      </>
    );
  return <MeetingView meeting={meeting.data} page={page} />;
}

function MeetingView({ meeting, page }: { meeting: MeetingDetail; page: MeetingPageState }) {
  const ui = useAppTranslation();
  const { live } = page;
  // Everything that changes the meeting belongs to whoever recorded it; a reader reads.
  const owned = meeting.owned;
  return (
    <>
      <AppShellHeader title={meeting.title} />
      {live.recording && live.recorder && (
        <RecordingBar
          recorder={live.recorder}
          kind={meeting.kind}
          onStop={page.end}
          onBookmark={page.bookmark}
        />
      )}
      <SettingsLayout wide className="gap-5 md:pt-8">
        <PageHeader
          icon={<Mic />}
          title={meeting.title}
          description={<MeetingFacts meeting={meeting} />}
          actions={
            !owned ? null : meeting.status === "RECORDING" && !live.recording ? (
              <>
                <Button
                  pending={page.resuming}
                  disabled={live.busy || captureSupport() === "unsupported"}
                  onClick={() => page.resume(meeting)}
                >
                  <Mic data-icon="inline-start" aria-hidden="true" />
                  {ui("Tiếp tục ghi")}
                </Button>
                <ConfirmDialog
                  trigger={
                    <Button prominence="secondary">
                      <Square data-icon="inline-start" aria-hidden="true" />
                      {ui("Kết thúc cuộc họp")}
                    </Button>
                  }
                  title={ui("Kết thúc cuộc họp?")}
                  description={ui("Sau khi kết thúc, cuộc họp không ghi tiếp được.")}
                  confirmLabel={ui("Kết thúc")}
                  pendingLabel={ui("Đang kết thúc…")}
                  onConfirm={page.end}
                />
                <MeetingDetailsDialog meeting={meeting} />
              </>
            ) : (
              <MeetingDetailsDialog meeting={meeting} />
            )
          }
        />
        <MeetingStats meeting={meeting} recorder={live.recording ? live.recorder : undefined} />
        <MeetingNotices meeting={meeting} page={page} />
        <MeetingTabs meeting={meeting} page={page} />
        {owned && <MeetingSharing meeting={meeting} />}
        {owned && meeting.status === "ENDED" && (
          <DangerZone
            icon={<Trash2 />}
            title={ui("Xoá cuộc họp này")}
            description={ui("Transcript, tên người nói và ghi chú sẽ mất vĩnh viễn.")}
            action={
              <ConfirmDialog
                trigger={
                  <Button tone="danger" prominence="secondary">
                    {ui("Xoá cuộc họp")}
                  </Button>
                }
                title={ui("Xoá {{v1}}?", { v1: meeting.title })}
                description={ui(
                  "Transcript, tên người nói và ghi chú của cuộc họp này sẽ bị xoá vĩnh viễn.",
                )}
                confirmLabel={ui("Xoá")}
                pendingLabel={ui("Đang xoá…")}
                confirmTone="danger"
                errorMessage={(error) => presentProblem(error, "mutation").message}
                onConfirm={async () => {
                  await page.remove.mutateAsync({ path: { meetingId: meeting.id } });
                }}
              />
            }
          />
        )}
      </SettingsLayout>
    </>
  );
}
