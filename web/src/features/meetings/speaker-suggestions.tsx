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
    <section className="grid gap-2">
      <h3 className="text-sm font-medium">
        {ui("Tên người nói ({{count}})", { count: offered.length })}
      </h3>
      {failure ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {failureText(failure)}
        </p>
      ) : null}
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
                    onClick={() => onReveal(said.id)}
                  >
                    {said.text}
                  </button>
                )}
              </div>
              <div className="flex shrink-0 gap-2">
                <Button
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
                  prominence="secondary"
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
    </section>
  );
}
