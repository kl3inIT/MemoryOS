import { useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import type { UtteranceSpan } from "./meeting-socket";
import { correctWords, foldCorrection, type MeetingDetail } from "./meetings-api";

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
  const problemMessage = useProblemMessage();
  const heard = text.slice(span.start, span.end);
  const [open, setOpen] = useState(false);
  const [word, setWord] = useState(heard);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    const written = word.trim();
    if (!written || written === heard) {
      setOpen(false);
      return;
    }
    setPending(true);
    setError(null);
    try {
      foldCorrection(
        cache,
        meeting.id,
        await correctWords(meeting.id, utteranceId, span.start, span.end, written),
      );
      setOpen(false);
    } catch (failed) {
      setError(problemMessage(presentProblem(failed, "mutation").message));
    } finally {
      setPending(false);
    }
  }

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) {
          setWord(heard);
          setError(null);
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
      <PopoverContent align="start" className="grid w-64 gap-1.5 p-2">
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void save();
          }}
        >
          <Input
            autoFocus
            value={word}
            disabled={pending}
            maxLength={2000}
            aria-label={ui("Từ đúng")}
            onFocus={(event) => event.currentTarget.select()}
            onChange={(event) => setWord(event.target.value)}
          />
        </form>
        {error && <p className="text-xs text-status-danger-content">{error}</p>}
      </PopoverContent>
    </Popover>
  );
}
