import { useRef, useState, type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { correctMeetingWordsMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { UtteranceSpan } from "./meeting-socket";
import { foldCorrection, type MeetingDetail } from "./meetings-api";
import { useFailureText } from "./use-failure-text";

/**
 * A word the provider was unsure of, opened by the owner to write what was said. Enter saves, Escape leaves it as it
 * was; the change joins the other corrections and is taken back the same way.
 */
export function WordCorrection({
  meeting,
  utteranceId,
  text,
  span,
  children,
}: {
  meeting: MeetingDetail;
  utteranceId: string;
  text: string;
  span: UtteranceSpan;
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const failureText = useFailureText();
  const heard = text.slice(span.start, span.end);
  const input = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [word, setWord] = useState(heard);
  const correct = useMutation({
    ...correctMeetingWordsMutation(),
    onSuccess: (answer) => {
      foldCorrection(cache, meeting.id, answer);
      setOpen(false);
    },
  });

  function save() {
    const written = word.trim();
    if (!written || written === heard) {
      setOpen(false);
      return;
    }
    correct.mutate({
      path: { meetingId: meeting.id, utteranceId },
      body: { start: span.start, end: span.end, text: written },
    });
  }

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) {
          setWord(heard);
          correct.reset();
        }
      }}
    >
      <PopoverTrigger asChild>
        <button
          type="button"
          className="cursor-text rounded-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
          aria-label={ui("Sửa “{{word}}”", { word: heard })}
        >
          {children}
        </button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="w-64 p-2"
        // The word opens selected, ready to be typed over.
        onOpenAutoFocus={(event) => {
          event.preventDefault();
          input.current?.select();
        }}
      >
        <form
          className="grid gap-1.5"
          onSubmit={(event) => {
            event.preventDefault();
            save();
          }}
        >
          <Input
            ref={input}
            value={word}
            disabled={correct.isPending}
            maxLength={2000}
            aria-label={ui("Từ đúng")}
            onChange={(event) => setWord(event.target.value)}
          />
          {correct.isError && (
            <p role="alert" className="text-xs text-status-danger-content">
              {failureText(correct.error)}
            </p>
          )}
        </form>
      </PopoverContent>
    </Popover>
  );
}
