import { useId, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import type { MeetingTrack } from "./meeting-socket";
import { nameSpeaker, patchMeeting, withSpeaker, type MeetingDetail } from "./meetings-api";
import { speakerColor, speakerName } from "./speakers";

/** Who said a line; its owner names the voice from here. */
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
  const [error, setError] = useState<string>();
  const owner = meeting.kind === "ONLINE" && track === "MIC";
  const display = speakerName(meeting, track, label, ui);
  const dot = (
    <span
      className={cn("size-2.5 rounded-full", speakerColor(meeting, track, label))}
      aria-hidden="true"
    />
  );
  if (owner)
    return (
      <span className="inline-flex items-center gap-1.5 text-sm font-medium text-content-primary">
        {dot}
        {display}
      </span>
    );

  async function save(value: string | null) {
    setError(undefined);
    try {
      const saved = await nameSpeaker(meeting.id, track, label, value);
      patchMeeting(cache, meeting.id, (current) => withSpeaker(current, saved));
      setOpen(false);
      setName("");
    } catch {
      setError(ui("Không đổi được tên. Hãy thử lại."));
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild disabled={!meeting.owned}>
        <button
          type="button"
          className="inline-flex items-center gap-1.5 rounded text-sm font-medium text-content-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring enabled:hover:underline"
          disabled={!meeting.owned}
          aria-label={meeting.owned ? ui("Đặt tên cho {{name}}", { name: display }) : display}
        >
          {dot}
          {display}
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="grid w-64 gap-2 p-2">
        {meeting.participants.map((participant) => (
          <button
            key={participant}
            type="button"
            className="rounded-md px-2 py-1.5 text-left text-sm hover:bg-surface-subtle"
            onClick={() => void save(participant)}
          >
            {participant}
          </button>
        ))}
        <form
          className="flex gap-1.5"
          onSubmit={(event) => {
            event.preventDefault();
            if (name.trim()) void save(name.trim());
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
          <Button size="sm" type="submit" disabled={!name.trim()}>
            {ui("Lưu")}
          </Button>
        </form>
        {meeting.speakers.some(
          (speaker) => speaker.track === track && speaker.label === label && speaker.name,
        ) && (
          <Button size="sm" prominence="tertiary" onClick={() => void save(null)}>
            {ui("Bỏ tên")}
          </Button>
        )}
        {error && (
          <p role="alert" className="px-1 text-xs text-status-danger-content">
            {error}
          </p>
        )}
      </PopoverContent>
    </Popover>
  );
}
