import { useCallback, useEffect, useRef, useState } from "react";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { uiLanguage } from "@/i18n";
import type { AppCopy } from "@/i18n/app-text";
import { startVoiceDictation, type VoiceDictation } from "./voice-dictation";
import { requestVoiceTicket, voiceFailureCopy } from "./voice-failure";
import { VoiceSessionStore } from "./voice-session-store";

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
  // The meter and mute of this field's recording, read by its recording controls only.
  const [session] = useState(() => new VoiceSessionStore());
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
      session.stopping();
      const transcript = await running.stop();
      session.ended("stopped");
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
    session.starting();
    try {
      const running = await startVoiceDictation({
        language: uiLanguage(sessionLanguage),
        requestTicket: requestVoiceTicket,
        onInterim: (transcript) => {
          if (current()) onText([prefix.current, transcript.trim()].filter(Boolean).join(" "));
        },
        onLevel: session.level,
        onFailure: (error) => {
          if (!current()) return;
          dictation.current = null;
          session.ended("error", error);
          setFailure(voiceFailureCopy(error));
          setStatus("failed");
        },
      });
      if (!current()) {
        running.cancel();
        return;
      }
      dictation.current = running;
      session.started(running);
      setStatus("listening");
    } catch (error) {
      if (!current()) return;
      session.ended("error", error);
      setFailure(voiceFailureCopy(error));
      setStatus("failed");
    }
  }, [onFinished, onText, session, sessionLanguage, status, text]);

  return {
    status,
    failure,
    listening: status === "listening",
    /** A recording is being prepared, running or settling its final text. */
    recording: status === "starting" || status === "listening" || status === "finishing",
    session,
    toggle,
  };
}
