import { useId, useState, type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  nameMeetingSpeakerMutation,
  setMeetingLineSpeakerMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { MeetingUtterance } from "@/lib/hey-api/types.gen";
import { SpeakerBadge } from "./speaker-chip";
import { patchMeeting, withLineSpeaker, withSpeaker, type MeetingDetail } from "./meetings-api";
import type { MeetingTrack } from "./meeting-socket";
import { speakerName } from "./speakers";

type Scope = "line" | "voice";

type Choice =
  | { key: string; kind: "voice"; track: MeetingTrack; label: string; name: string }
  | { key: string; kind: "name"; name: string };

const fold = (name: string) => name.normalize("NFC").toLowerCase().replace(/\s+/g, " ").trim();

/**
 * Who spoke one line, as Nojoin's speaker picker: the meeting's voices and the expected participants, for this line
 * or, as renaming a voice in the panel does, for every line of its voice. A line keeps its track, so a voice of the
 * other track is offered by its name and only when it has one.
 */
export function LineSpeakerPicker({
  meeting,
  utterance,
  children,
}: {
  meeting: MeetingDetail;
  utterance: MeetingUtterance;
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const id = useId();
  const [open, setOpen] = useState(false);
  const [scope, setScope] = useState<Scope>("line");
  const [name, setName] = useState("");
  const done = () => {
    setOpen(false);
    setName("");
  };
  const moveLine = useMutation({
    ...setMeetingLineSpeakerMutation(),
    onSuccess: (moved) => {
      patchMeeting(cache, meeting.id, (current) => withLineSpeaker(current, moved));
      done();
    },
  });
  const renameVoice = useMutation({
    ...nameMeetingSpeakerMutation(),
    onSuccess: (saved) => {
      patchMeeting(cache, meeting.id, (current) => withSpeaker(current, saved));
      done();
    },
  });
  const pending = moveLine.isPending || renameVoice.isPending;
  const failed = moveLine.isError || renameVoice.isError;
  const current = speakerName(meeting, utterance.track, utterance.speaker, ui);
  // Online, the microphone is the owner and is never renamed as a whole; one of its lines still can be.
  const voiceScope = !(meeting.kind === "ONLINE" && utterance.track === "MIC");
  const effective: Scope = voiceScope ? scope : "line";

  const choices: Choice[] = [];
  const seen = new Set<string>();
  const isCurrent = (choice: Choice) =>
    choice.kind === "voice"
      ? choice.track === utterance.track && choice.label === utterance.speaker
      : fold(choice.name) === fold(current);
  // The line's own track first, so a name both tracks carry points at the voice the line would take.
  for (const speaker of [...meeting.speakers].sort(
    (a, b) => Number(a.track !== utterance.track) - Number(b.track !== utterance.track),
  )) {
    const own = speaker.track === utterance.track;
    if (!own && !speaker.name) continue;
    if (effective === "voice" && !speaker.name) continue;
    const shown = speakerName(meeting, speaker.track, speaker.label, ui);
    if (seen.has(fold(shown))) continue;
    seen.add(fold(shown));
    choices.push({
      key: `${speaker.track}:${speaker.label}`,
      kind: "voice",
      track: speaker.track,
      label: speaker.label,
      name: shown,
    });
  }
  for (const participant of meeting.participants) {
    if (seen.has(fold(participant))) continue;
    seen.add(fold(participant));
    choices.push({ key: `name:${participant}`, kind: "name", name: participant });
  }

  function choose(choice: Choice) {
    if (isCurrent(choice)) return done();
    if (effective === "voice") {
      renameVoice.mutate({
        path: { meetingId: meeting.id, track: utterance.track, label: utterance.speaker },
        body: { name: choice.name },
      });
      return;
    }
    moveLine.mutate({
      path: { meetingId: meeting.id, utteranceId: utterance.id },
      body:
        choice.kind === "voice"
          ? { speaker: { track: choice.track, label: choice.label } }
          : { name: choice.name },
    });
  }

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) {
          setScope("line");
          moveLine.reset();
          renameVoice.reset();
        }
      }}
    >
      <PopoverTrigger asChild>{children}</PopoverTrigger>
      <PopoverContent align="end" className="w-72 p-2">
        <div className="grid gap-2">
          {voiceScope && (
            <ToggleGroup
              type="single"
              size="sm"
              value={scope}
              aria-label={ui("Đổi cho")}
              onValueChange={(next) => next && setScope(next as Scope)}
              className="w-full"
            >
              <ToggleGroupItem value="line" size="sm" className="flex-1">
                {ui("Câu này")}
              </ToggleGroupItem>
              <ToggleGroupItem value="voice" size="sm" className="flex-1">
                {ui("Mọi câu của {{name}}", { name: current })}
              </ToggleGroupItem>
            </ToggleGroup>
          )}
          <ul className="grid max-h-60 gap-0.5 overflow-y-auto" aria-label={ui("Người nói")}>
            {choices.map((choice) => (
              <li key={choice.key}>
                <button
                  type="button"
                  disabled={pending}
                  onClick={() => choose(choice)}
                  className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-surface-subtle focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none disabled:opacity-60"
                >
                  {choice.kind === "voice" ? (
                    <SpeakerBadge meeting={meeting} track={choice.track} label={choice.label} />
                  ) : (
                    <span aria-hidden="true" className="size-6 shrink-0" />
                  )}
                  <span className="min-w-0 flex-1 truncate">{choice.name}</span>
                  {isCurrent(choice) && (
                    <Check className="size-4 text-content-muted" aria-label={ui("Đang chọn")} />
                  )}
                </button>
              </li>
            ))}
          </ul>
          <form
            className="flex gap-1.5"
            onSubmit={(event) => {
              event.preventDefault();
              const typed = name.trim();
              if (typed) choose({ key: "typed", kind: "name", name: typed });
            }}
          >
            <Input
              id={`${id}-name`}
              size="sm"
              value={name}
              maxLength={200}
              placeholder={ui("Tên khác")}
              aria-label={ui("Tên người nói")}
              onChange={(event) => setName(event.target.value)}
            />
            <Button size="sm" type="submit" pending={pending} disabled={!name.trim()}>
              {ui("Lưu")}
            </Button>
          </form>
          {failed && (
            <p role="alert" className="px-1 text-xs text-status-danger-content">
              {ui("Không đổi được người nói. Hãy thử lại.")}
            </p>
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
}
