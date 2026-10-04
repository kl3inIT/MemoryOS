import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Check, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  dismissMeetingSpeakerSuggestionMutation,
  nameMeetingSpeakerMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { patchMeeting, withSpeaker, type MeetingDetail, type MeetingSpeaker } from "./meetings-api";
import { useFailureText } from "./use-failure-text";

/**
 * The names voices gave themselves, offered to the owner with the sentence they said it in. Accepting one is the
 * ordinary rename; dismissing keeps the automatic label and never asks again. Nothing is renamed without a press.
 */
export function SpeakerSuggestions({
  meeting,
  onReveal,
}: {
  meeting: MeetingDetail;
  /** Brings the sentence a name was given in into view. */
  onReveal: (utteranceId: string) => void;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const failureText = useFailureText();
  const onSuccess = (speaker: MeetingSpeaker) =>
    patchMeeting(cache, meeting.id, (current) => withSpeaker(current, speaker));
  const accept = useMutation({ ...nameMeetingSpeakerMutation(), onSuccess });
  const dismiss = useMutation({ ...dismissMeetingSpeakerSuggestionMutation(), onSuccess });
  const offered = meeting.speakers.filter((speaker) => speaker.suggestion);
  if (!meeting.owned || offered.length === 0) return null;
  const busy = accept.isPending || dismiss.isPending;
  const failure = accept.error ?? dismiss.error;

  return (
    <div className="mt-1 grid grid-cols-1 gap-2">
      {failure ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {failureText(failure)}
        </p>
      ) : null}
      <ol className="grid grid-cols-1 gap-3">
        {offered.map((speaker) => {
          const key = `${speaker.track}/${speaker.label}`;
          const said = meeting.utterances.find(
            (utterance) => utterance.id === speaker.suggestion?.utteranceId,
          );
          const name = speaker.suggestion?.name ?? "";
          return (
            <li key={key} className="grid gap-1.5">
              <div className="min-w-0">
                <p className="text-sm">
                  {ui("Người nói {{label}} tự giới thiệu là {{name}}", {
                    label: speaker.label,
                    name,
                  })}
                </p>
                {/* The sentence the name was read from, quoted, and the way to it in the transcript. */}
                {said && (
                  <button
                    type="button"
                    className="block max-w-full truncate text-xs text-content-muted hover:underline"
                    aria-label={ui("Xem câu “{{text}}” trong transcript", { text: said.text })}
                    onClick={() => onReveal(said.id)}
                  >
                    “{said.text}”
                  </button>
                )}
              </div>
              <div className="flex flex-wrap gap-2">
                <Button
                  prominence="secondary"
                  size="sm"
                  disabled={busy}
                  onClick={() => {
                    dismiss.reset();
                    accept.mutate({
                      path: { meetingId: meeting.id, track: speaker.track, label: speaker.label },
                      body: { name },
                    });
                  }}
                >
                  <Check aria-hidden="true" />
                  {ui("Đặt tên {{name}}", { name })}
                </Button>
                <Button
                  prominence="tertiary"
                  size="sm"
                  disabled={busy}
                  onClick={() => {
                    accept.reset();
                    dismiss.mutate({
                      path: { meetingId: meeting.id, track: speaker.track, label: speaker.label },
                    });
                  }}
                >
                  <X aria-hidden="true" />
                  {ui("Bỏ qua")}
                </Button>
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
