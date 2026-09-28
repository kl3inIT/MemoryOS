import { useCallback, useEffect, useRef, useState } from "react";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { uiLanguage } from "@/i18n";
import type { AppCopy } from "@/i18n/app-text";
import { startVoiceDictation, type VoiceDictation } from "./voice-dictation";
import { requestVoiceTicket, voiceFailureCopy } from "./voice-failure";

export type DictationStatus = "idle" | "starting" | "listening" | "finishing" | "failed";

/**
 * Dictation into a plain text field, as Search and the file preview use it: the Tenant's speech-to-text
 * provider writes into the draft the person already has, and the text they had typed stays in front of what
 * is spoken. Chat's composer dictates through assistant-ui instead, which owns its own draft.
 */
export function useDictationInput({
  text,
  onText,
  onFinished,
}: {
  /** The draft as it stands; what is spoken is appended to it. */
  text: string;
  onText: (text: string) => void;
  /** Called once a transcript has been written, so the caller can put focus back where it was. */
  onFinished?: () => void;
}) {
  const { uiLanguage: sessionLanguage } = useApplicationSession();
  const dictation = useRef<VoiceDictation | null>(null);
  const attempt = useRef(0);
  const prefix = useRef("");
  const [status, setStatus] = useState<DictationStatus>("idle");
  const [failure, setFailure] = useState<AppCopy>();

  // Leaving the surface must release the microphone, whatever state the dictation is in.
  useEffect(
    () => () => {
      attempt.current += 1;
      dictation.current?.cancel();
    },
    [],
  );

  const toggle = useCallback(async () => {
    if (status === "listening") {
      const running = dictation.current;
      dictation.current = null;
      if (!running) return;
      setStatus("finishing");
      const transcript = await running.stop();
      if (transcript) onText([prefix.current, transcript.trim()].filter(Boolean).join(" "));
      setStatus("idle");
      onFinished?.();
      return;
    }
    if (status === "starting" || status === "finishing") return;
    const started = ++attempt.current;
    const current = () => attempt.current === started;
    prefix.current = text.trim();
    setFailure(undefined);
    setStatus("starting");
    try {
      const running = await startVoiceDictation({
        language: uiLanguage(sessionLanguage),
        requestTicket: requestVoiceTicket,
        onInterim: (transcript) => {
          if (current()) onText([prefix.current, transcript.trim()].filter(Boolean).join(" "));
        },
        onLevel: () => {},
        onFailure: (error) => {
          if (!current()) return;
          dictation.current = null;
          setFailure(voiceFailureCopy(error));
          setStatus("failed");
        },
      });
      if (!current()) {
        running.cancel();
        return;
      }
      dictation.current = running;
      setStatus("listening");
    } catch (error) {
      if (!current()) return;
      setFailure(voiceFailureCopy(error));
      setStatus("failed");
    }
  }, [onFinished, onText, sessionLanguage, status, text]);

  return { status, failure, listening: status === "listening", toggle };
}
