import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Check, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import {
  dismissSpeakerSuggestion,
  nameSpeaker,
  patchMeeting,
  withSpeaker,
  type MeetingDetail,
  type MeetingSpeaker,
} from "./meetings-api";

/**
 * The names voices gave themselves, offered to the owner with the sentence they said it in. Accepting one is the
 * ordinary rename; dismissing keeps the automatic label and never asks again. Nothing is renamed without a press.
 */
export function SpeakerSuggestions({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const [pending, setPending] = useState<string>();
  const [error, setError] = useState<string | null>(null);
  const offered = meeting.speakers.filter((speaker) => speaker.suggestion);
  if (!meeting.owned || offered.length === 0) return null;

  async function decide(key: string, act: () => Promise<MeetingSpeaker>) {
    setPending(key);
    setError(null);
    try {
      const speaker = await act();
      patchMeeting(cache, meeting.id, (current) => withSpeaker(current, speaker));
    } catch (failed) {
      setError(problemMessage(presentProblem(failed, "mutation").message));
    } finally {
      setPending(undefined);
    }
  }

  return (
    <section className="grid gap-2">
      <h3 className="text-sm font-medium">
        {ui("Tên người nói ({{count}})", { count: offered.length })}
      </h3>
      {error && <p className="text-sm text-status-danger-content">{error}</p>}
      <ol className="grid gap-2">
        {offered.map((speaker) => {
          const key = `${speaker.track}/${speaker.label}`;
          const said = meeting.utterances.find(
            (utterance) => utterance.id === speaker.suggestion?.utteranceId,
          );
          const name = speaker.suggestion?.name ?? "";
          return (
            <li
              key={key}
              className="grid gap-2 rounded-xl border border-border-default px-3 py-3 sm:flex sm:items-center sm:justify-between"
            >
              <div className="min-w-0">
                <p className="text-sm">
                  {ui("Người nói {{label}} tự giới thiệu là {{name}}", {
                    label: speaker.label,
                    name,
                  })}
                </p>
                {said && (
                  <button
                    type="button"
                    className="truncate text-sm text-content-muted hover:underline"
                    onClick={() =>
                      document
                        .getElementById(said.id)
                        ?.scrollIntoView({ block: "center", behavior: "smooth" })
                    }
                  >
                    {said.text}
                  </button>
                )}
              </div>
              <div className="flex shrink-0 gap-2">
                <Button
                  size="sm"
                  disabled={pending !== undefined}
                  onClick={() =>
                    void decide(key, () =>
                      nameSpeaker(meeting.id, speaker.track, speaker.label, name),
                    )
                  }
                >
                  <Check aria-hidden="true" />
                  {ui("Đặt tên {{name}}", { name })}
                </Button>
                <Button
                  prominence="secondary"
                  size="sm"
                  disabled={pending !== undefined}
                  onClick={() =>
                    void decide(key, () =>
                      dismissSpeakerSuggestion(meeting.id, speaker.track, speaker.label),
                    )
                  }
                >
                  <X aria-hidden="true" />
                  {ui("Bỏ qua")}
                </Button>
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
