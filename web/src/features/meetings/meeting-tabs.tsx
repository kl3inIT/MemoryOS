import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { MinutesActions, MinutesItems, MinutesSummary } from "./meeting-minutes";
import { MeetingNotes } from "./meeting-notes";
import { Transcript } from "./meeting-transcript";
import type { MeetingDetail } from "./meetings-api";
import { SpeakerSuggestions } from "./speaker-suggestions";
import { TranscriptCorrections } from "./transcript-corrections";
import type { MeetingPageState } from "./use-meeting-page";

/**
 * The minutes, the transcript and the owner's notes. The minutes take the reader's place as soon as they land, as
 * ghiam-pro's conclusion tab does.
 */
export function MeetingTabs({ meeting, page }: { meeting: MeetingDetail; page: MeetingPageState }) {
  const ui = useAppTranslation();
  const { owned, minutes } = meeting;
  const shown = page.pane ?? (minutes.status === "READY" ? "summary" : "transcript");
  return (
    <TranscriptCorrections
      meeting={meeting}
      enabled={owned && meeting.status === "ENDED" && meeting.utterances.length > 0}
    >
      {(corrections) => (
        <Tabs value={shown} onValueChange={page.setPane}>
          {/* What acts on the open tab sits at the right of the tab row; on a phone it drops below the tabs. */}
          <div className="flex flex-wrap items-center justify-between gap-2">
            {/* Five tabs are wider than a phone: they scroll inside their own row, or choosing one
                scrolls the whole page sideways to reveal it. */}
            <TabsList className="max-w-full min-w-0 justify-start overflow-x-auto">
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
            {shown === "transcript" && corrections.trigger}
            {shown !== "transcript" && shown !== "notes" && minutes.status === "READY" && (
              <MinutesActions meeting={meeting} />
            )}
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
            <div className="grid gap-4">
              <SpeakerSuggestions meeting={meeting} onReveal={page.reveal} />
              {corrections.panel}
              <Transcript
                meeting={meeting}
                recorder={page.live.recording ? page.live.recorder : undefined}
                target={page.target}
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
