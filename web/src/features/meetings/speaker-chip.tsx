import { useId, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import type { MeetingTrack } from "./meeting-socket";
import { nameMeetingSpeakerMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { patchMeeting, withSpeaker, type MeetingDetail } from "./meetings-api";
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
  const rename = useMutation({
    ...nameMeetingSpeakerMutation(),
    onSuccess: (saved) => {
      patchMeeting(cache, meeting.id, (current) => withSpeaker(current, saved));
      setOpen(false);
      setName("");
    },
  });
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
      <PopoverContent align="start" className="w-64 p-2">
        <div className="grid gap-2">
          {meeting.participants.map((participant) => (
            <button
              key={participant}
              type="button"
              className="rounded-md px-2 py-1.5 text-left text-sm hover:bg-surface-subtle"
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
