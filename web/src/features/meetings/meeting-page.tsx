import { useCallback, useMemo, useState, type CSSProperties } from "react";
import { Mic, PanelRightOpen, Square, WifiOff } from "lucide-react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { BrandLoader } from "@/components/brand-loader";
import { DetailBreadcrumb } from "@/components/composites/detail-header";
import { EmptyState } from "@/components/composites/empty-state";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { captureSupport } from "./meeting-capture";
import { MeetingActions } from "./meeting-actions";
import { MeetingNotices } from "./meeting-overview";
import { MeetingPanel } from "./meeting-panel";
import { MeetingSharing } from "./meeting-sharing";
import { MeetingTabs } from "./meeting-tabs";
import type { TranscriptTarget } from "./meeting-transcript";
import { timelineOf, type MeetingDetail } from "./meetings-api";
import { RecordingBar } from "./recording-bar";
import type { MeetingPane } from "./meeting-panes";
import { useMeetingPage, type MeetingPageState } from "./use-meeting-page";
import { useWidePage } from "./use-wide-page";

export function MeetingPage({
  meetingId,
  tabAudioMissing,
  pane,
  onPane,
}: {
  meetingId: string;
  tabAudioMissing?: boolean;
  /** The tab the address names, so a reload or a shared link opens the same one. */
  pane: MeetingPane | undefined;
  onPane: (pane: MeetingPane) => void;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  const page = useMeetingPage(meetingId, !!tabAudioMissing, pane, onPane);
  const { meeting } = page;

  if (meeting.isPending || meeting.isError)
    return (
      <>
        <AppShellHeader
          title={ui("Cuộc họp")}
          breadcrumb={<DetailBreadcrumb parent={{ label: ui("Cuộc họp"), to: "/meetings" }} />}
        />
        <SettingsLayout wide className="gap-6">
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

/**
 * One column to read in and, beside it, the panel the reader steers by (Lightfield's meeting page). The page is as long as the meeting was, so nothing that acts on the meeting waits
 * below the transcript: sharing, renaming and deleting open from the header.
 */
function MeetingView({ meeting, page }: { meeting: MeetingDetail; page: MeetingPageState }) {
  const ui = useAppTranslation();
  const { live } = page;
  // Everything that changes the meeting belongs to whoever recorded it; a reader reads.
  const owned = meeting.owned;
  const timeline = useMemo(() => timelineOf(meeting), [meeting]);
  const [reading, setReading] = useState<string>();
  // The panel stands beside a wide page; a page too narrow for it opens it over the transcript.
  const wide = useWidePage();
  const [sheetOpen, setSheetOpen] = useState(false);
  // The recording bar is pinned to the top of the page and wraps on a narrow one; what is pinned under it starts
  // where it ends.
  const [barHeight, setBarHeight] = useState(0);
  const bar = useCallback((element: HTMLDivElement | null) => {
    if (!element) return;
    const observer = new ResizeObserver(() => setBarHeight(element.offsetHeight));
    observer.observe(element);
    return () => {
      observer.disconnect();
      setBarHeight(0);
    };
  }, []);
  function reach(utteranceId: string, block: TranscriptTarget["block"]) {
    // Over a narrow page the panel covers the transcript it is about to open.
    setSheetOpen(false);
    page.reveal(utteranceId, block);
  }
  return (
    <>
      {/* The shell header is shown on this page, so the way back fills it instead of repeating the title. */}
      <AppShellHeader
        title={meeting.title}
        breadcrumb={
          <DetailBreadcrumb
            parent={{ label: ui("Cuộc họp"), to: "/meetings" }}
            title={meeting.title}
            oneLine
          />
        }
        actions={
          !wide && (
            <span className="relative flex">
              <Button
                size="sm"
                prominence="secondary"
                aria-label={ui("Chi tiết cuộc họp")}
                onClick={() => setSheetOpen(true)}
              >
                <PanelRightOpen data-icon="inline-start" aria-hidden="true" />
                {ui("Chi tiết")}
              </Button>
              {/* Names are waiting for an answer inside the panel. */}
              {owned && meeting.speakers.some((speaker) => speaker.suggestion) && (
                <span
                  className="absolute -top-0.5 -right-0.5 size-2 rounded-full bg-status-info-emphasis"
                  aria-hidden="true"
                />
              )}
            </span>
          )
        }
      />
      <div className="flex items-start">
        <div
          className="min-w-0 flex-1"
          style={{ "--meeting-pinned-top": `${barHeight}px` } as CSSProperties}
        >
          {live.recording && live.recorder && (
            <div ref={bar} className="sticky top-0 z-20">
              <RecordingBar
                recorder={live.recorder}
                kind={meeting.kind}
                onStop={page.end}
                onBookmark={page.bookmark}
              />
            </div>
          )}
          <SettingsLayout wide className="gap-5 md:pt-8">
            <PageHeader
              icon={<Mic />}
              title={meeting.title}
              description={
                // Its owner is told by the recording bar and the notices; a reader has neither.
                !owned && meeting.status === "RECORDING" ? (
                  <StatusBadge tone="danger">{ui("Đang ghi")}</StatusBadge>
                ) : undefined
              }
              actions={
                owned && (
                  <>
                    {meeting.status === "RECORDING" && !live.recording && (
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
                            // Ending cannot be taken back, here as on the recording bar.
                            <Button tone="danger" prominence="secondary">
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
                      </>
                    )}
                    <MeetingSharing meeting={meeting} />
                    <MeetingActions
                      meeting={meeting}
                      deletable={!live.recording}
                      onDelete={() => page.remove.mutateAsync({ path: { meetingId: meeting.id } })}
                    />
                  </>
                )
              }
            />
            <MeetingNotices meeting={meeting} page={page} />
            <MeetingTabs meeting={meeting} page={page} timeline={timeline} onReading={setReading} />
          </SettingsLayout>
        </div>
        <MeetingPanel
          meeting={meeting}
          recorder={live.recording ? live.recorder : undefined}
          timeline={timeline}
          reading={reading}
          wide={wide}
          open={sheetOpen}
          onOpenChange={setSheetOpen}
          onReach={reach}
        />
      </div>
    </>
  );
}
