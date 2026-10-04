import { useEffect, useRef, useState } from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { MinutesActions, MinutesItems, MinutesSummary } from "./meeting-minutes";
import { MeetingNotes } from "./meeting-notes";
import { Transcript } from "./meeting-transcript";
import type { MeetingDetail, TimelineEntry } from "./meetings-api";
import { TranscriptCorrections } from "./transcript-corrections";
import { scrollerOf } from "./use-follow-end";
import type { MeetingPane } from "./meeting-panes";
import type { MeetingPageState } from "./use-meeting-page";

/**
 * The minutes, the transcript and the owner's notes. The minutes take the place of a reader still at the top of
 * the page as soon as they land, as ghiam-pro's conclusion tab does; one reading further down keeps their place.
 */
export function MeetingTabs({
  meeting,
  page,
  timeline,
  onReading,
}: {
  meeting: MeetingDetail;
  page: MeetingPageState;
  timeline: TimelineEntry[];
  /** Told which place in the timeline the transcript is being read at. */
  onReading: (entryId: string | undefined) => void;
}) {
  const ui = useAppTranslation();
  const { owned, minutes } = meeting;
  const ready = minutes.status === "READY";
  const tabs = useRef<HTMLDivElement>(null);
  const [opened, setOpened] = useState<MeetingPane>(ready ? "summary" : "transcript");
  const known = useRef(ready);
  useEffect(() => {
    if (ready === known.current) return;
    known.current = ready;
    if (ready && tabs.current && scrollerOf(tabs.current).scrollTop === 0) setOpened("summary");
  }, [ready]);
  const shown = page.pane ?? opened;
  // Where the transcript shows its search: under the tabs, in the part of the page that stays in view.
  const [toolsSlot, setToolsSlot] = useState<HTMLElement | null>(null);
  return (
    <TranscriptCorrections
      meeting={meeting}
      enabled={owned && meeting.status === "ENDED" && meeting.utterances.length > 0}
    >
      {(corrections) => (
        <Tabs ref={tabs} value={shown} onValueChange={(next) => page.setPane(next as MeetingPane)}>
          {/* A meeting is hours long, so the tabs and the open tab's tools stay at the top of the window, under
              the recording bar; a phone has no room to give them. */}
          <div className="z-10 grid grid-cols-1 gap-2 bg-background md:sticky md:top-(--meeting-pinned-top) md:py-2">
            {/* What acts on the open tab sits at the right of the tab row; on a phone it drops below the tabs. */}
            <div className="flex flex-wrap items-center justify-between gap-2">
              {/* Five tabs are wider than a phone: they scroll inside their own row, or choosing one
                scrolls the whole page sideways to reveal it. The row is taller than the list, so the mark
                under the open tab does not make it scroll up and down as well. */}
              <div className="max-w-full min-w-0 overflow-x-auto pb-1">
                <TabsList variant="line" className="justify-start">
                  {minutes.status !== "NONE" && (
                    <TabsTrigger value="summary">{ui("Tóm tắt")}</TabsTrigger>
                  )}
                  {minutes.actions.length > 0 && (
                    <TabsTrigger value="actions">
                      {ui("Việc cần làm")}
                      <Count value={minutes.actions.length} />
                    </TabsTrigger>
                  )}
                  {minutes.decisions.length > 0 && (
                    <TabsTrigger value="decisions">
                      {ui("Quyết định")}
                      <Count value={minutes.decisions.length} />
                    </TabsTrigger>
                  )}
                  <TabsTrigger value="transcript">{ui("Transcript")}</TabsTrigger>
                  {owned && <TabsTrigger value="notes">{ui("Ghi chú của tôi")}</TabsTrigger>}
                </TabsList>
              </div>
              {shown === "transcript" && corrections.trigger}
              {shown !== "transcript" && shown !== "notes" && minutes.status === "READY" && (
                <MinutesActions meeting={meeting} />
              )}
            </div>
            <div ref={setToolsSlot} className="empty:hidden" />
          </div>
          {minutes.status !== "NONE" && (
            <TabsContent value="summary" className="mt-2">
              <MinutesSummary meeting={meeting} />
            </TabsContent>
          )}
          <TabsContent value="actions" className="mt-2">
            <MinutesItems
              meeting={meeting}
              items={minutes.actions}
              kind="ACTION"
              onReveal={page.reveal}
            />
          </TabsContent>
          <TabsContent value="decisions" className="mt-2">
            <MinutesItems
              meeting={meeting}
              items={minutes.decisions}
              kind="DECISION"
              onReveal={page.reveal}
            />
          </TabsContent>
          <TabsContent value="transcript" className="mt-2">
            {/* One column no wider than the page: a sentence that does not wrap never widens it. */}
            <div className="grid grid-cols-1 gap-4">
              {corrections.panel}
              <Transcript
                meeting={meeting}
                recorder={page.live.recording ? page.live.recorder : undefined}
                target={page.target}
                timeline={timeline}
                corrections={corrections.applied}
                undoAll={corrections.undoAll}
                toolsSlot={toolsSlot}
                onReading={onReading}
                onStar={page.star}
              />
            </div>
          </TabsContent>
          {owned && (
            <TabsContent value="notes" className="mt-2">
              <MeetingNotes meeting={meeting} />
            </TabsContent>
          )}
        </Tabs>
      )}
    </TranscriptCorrections>
  );
}

function Count({ value }: { value: number }) {
  return <span className="ml-1 text-xs text-content-muted tabular-nums">{value}</span>;
}
