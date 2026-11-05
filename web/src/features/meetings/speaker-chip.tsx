import { useId, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import type { MeetingTrack } from "./meeting-socket";
import { nameMeetingSpeaker } from "@/lib/hey-api/sdk.gen";
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
  compact,
}: {
  meeting: MeetingDetail;
  track: MeetingTrack;
  label: string;
  compact?: boolean;
}) {
  const ui = useAppTranslation();
  return (
    <span
      className={cn(
        "inline-flex items-center font-medium text-content-primary",
        compact ? "gap-1.5 text-xs" : "gap-2 text-sm",
      )}
    >
      <SpeakerBadge
        meeting={meeting}
        track={track}
        label={label}
        className={compact ? COMPACT_BADGE : undefined}
      />
      {speakerName(meeting, track, label, ui)}
    </span>
  );
}

/** The badge beside a line of small text, such as who owns a piece of work. */
const COMPACT_BADGE = "size-4 text-2xs";

/**
 * A voice in the meeting's panel, or the voice that owns a piece of work; its owner names it from either, and the name
 * reaches every place the voice appears.
 */
export function SpeakerChip({
  meeting,
  track,
  label,
  twins = [],
  compact,
}: {
  meeting: MeetingDetail;
  track: MeetingTrack;
  label: string;
  /** Other voices shown under the same name, such as one person heard on both tracks: renamed together. */
  twins?: { track: MeetingTrack; label: string }[];
  compact?: boolean;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const id = useId();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const rename = useMutation({
    mutationFn: async (value: string | null) => {
      const saved = [];
      for (const voice of [{ track, label }, ...twins]) {
        const { data } = await nameMeetingSpeaker({
          path: { meetingId: meeting.id, track: voice.track, label: voice.label },
          body: { name: value },
        });
        saved.push(data);
      }
      return saved;
    },
    onSuccess: (saved) => {
      patchMeeting(cache, meeting.id, (current) => saved.reduce(withSpeaker, current));
      setOpen(false);
      setName("");
    },
  });
  // Online, the microphone is the owner, who needs no name; a reader names nobody.
  if (!meeting.owned || (meeting.kind === "ONLINE" && track === "MIC"))
    return <SpeakerName meeting={meeting} track={track} label={label} compact={compact} />;
  const display = speakerName(meeting, track, label, ui);

  function save(value: string | null) {
    rename.mutate(value);
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
          className={cn(
            "inline-flex items-center rounded font-medium text-content-primary hover:underline focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none",
            compact ? "gap-1.5 text-xs" : "gap-2 text-sm",
          )}
          aria-label={ui("Đặt tên cho {{name}}", { name: display })}
        >
          <SpeakerBadge
            meeting={meeting}
            track={track}
            label={label}
            className={compact ? COMPACT_BADGE : undefined}
          />
          {display}
          {/* Beside a piece of work the item's own pencil edits it; a second one would read as the same action. */}
          {!compact && <Pencil className="size-3 text-content-muted" aria-hidden="true" />}
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
