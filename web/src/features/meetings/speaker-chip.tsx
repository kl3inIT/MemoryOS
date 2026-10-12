import { useId, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import type { MeetingTrack } from "./meeting-socket";
import { nameMeetingSpeakerMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { patchMeeting, withSpeaker, type MeetingDetail } from "./meetings-api";
import { speakerColor, speakerInitial, speakerName } from "./speakers";

/**
 * A voice's badge: its colour, filled, with its number or initial on it. Two voices are told apart by what the
 * badge reads as well as by its colour, so a turn is placed at a glance down a long page.
 */
export function SpeakerBadge({
  meeting,
  track,
  label,
  className,
}: {
  meeting: MeetingDetail;
  track: MeetingTrack;
  label: string;
  className?: string;
}) {
  const ui = useAppTranslation();
  return (
    <span
      aria-hidden="true"
      className={cn(
        "inline-grid size-6 shrink-0 place-items-center rounded-full text-xs leading-none font-semibold text-speaker-content",
        speakerColor(meeting, track, label),
        className,
      )}
    >
      {speakerInitial(meeting, track, label, ui)}
    </span>
  );
}

/** Who said a line: the voice's badge and its name. */
function SpeakerName({
  meeting,
  track,
  label,
}: {
  meeting: MeetingDetail;
  track: MeetingTrack;
  label: string;
}) {
  const ui = useAppTranslation();
  return (
    <span className="inline-flex items-center gap-2 text-sm font-medium text-content-primary">
      <SpeakerBadge meeting={meeting} track={track} label={label} />
      {speakerName(meeting, track, label, ui)}
    </span>
  );
}

/** A voice in the meeting's panel; its owner names it from here. */
export function SpeakerChip({
  meeting,
  track,
  label,
}: {
  meeting: MeetingDetail;
  track: MeetingTrack;
  label: string;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const id = useId();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const rename = useMutation({
    ...nameMeetingSpeakerMutation(),
    onSuccess: (saved) => {
      patchMeeting(cache, meeting.id, (current) => withSpeaker(current, saved));
      setOpen(false);
      setName("");
    },
  });
  // Online, the microphone is the owner, who needs no name; a reader names nobody.
  if (!meeting.owned || (meeting.kind === "ONLINE" && track === "MIC"))
    return <SpeakerName meeting={meeting} track={track} label={label} />;
  const display = speakerName(meeting, track, label, ui);

  function save(value: string | null) {
    rename.mutate({ path: { meetingId: meeting.id, track, label }, body: { name: value } });
  }

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) rename.reset();
      }}
    >
      <PopoverTrigger asChild>
        <button
          type="button"
          className="inline-flex items-center gap-2 rounded text-sm font-medium text-content-primary hover:underline focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
          aria-label={ui("Đặt tên cho {{name}}", { name: display })}
        >
          <SpeakerBadge meeting={meeting} track={track} label={label} />
          {display}
          <Pencil className="size-3 text-content-muted" aria-hidden="true" />
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-64 p-2">
        <div className="grid gap-2">
          {meeting.participants.map((participant) => (
            <button
              key={participant}
              type="button"
              className="rounded-md px-2 py-1.5 text-left text-sm hover:bg-surface-subtle focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
              disabled={rename.isPending}
              onClick={() => save(participant)}
            >
              {participant}
            </button>
          ))}
          <form
            className="flex gap-1.5"
            onSubmit={(event) => {
              event.preventDefault();
              if (name.trim()) save(name.trim());
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
            <Button size="sm" type="submit" pending={rename.isPending} disabled={!name.trim()}>
              {ui("Lưu")}
            </Button>
          </form>
          {meeting.speakers.some(
            (speaker) => speaker.track === track && speaker.label === label && speaker.name,
          ) && (
            <Button
              size="sm"
              prominence="tertiary"
              disabled={rename.isPending}
              onClick={() => save(null)}
            >
              {ui("Bỏ tên")}
            </Button>
          )}
          {rename.isError && (
            <p role="alert" className="px-1 text-xs text-status-danger-content">
              {ui("Không đổi được tên. Hãy thử lại.")}
            </p>
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
}
