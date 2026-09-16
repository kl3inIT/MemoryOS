import type { DictationAdapter } from "@assistant-ui/react";
import {
  startVoiceDictation,
  type VoiceDictation,
  type VoiceDictationOptions,
} from "./voice-dictation";

export type DictationEnd = "stopped" | "cancelled" | "error";

export type MemoryosDictationAdapterOptions = {
  language: () => string;
  requestTicket: () => Promise<string>;
  /** Microphone permission, ticket and socket are being prepared. */
  onStarting?: () => void;
  onStarted?: (dictation: Pick<VoiceDictation, "setMuted">) => void;
  onLevel?: (level: number) => void;
  /** The recording stopped and the final transcript is pending. */
  onStopping?: () => void;
  onEnded?: (reason: DictationEnd, error?: unknown) => void;
  start?: (options: VoiceDictationOptions) => Promise<VoiceDictation>;
};

/**
 * assistant-ui dictation over the MemoryOS voice WebSocket. Interim text replaces the runtime preview; the final
 * transcript is emitted before {@link DictationAdapter.Session.stop} resolves, because the runtime unsubscribes as
 * soon as stop settles. Status is updated in place, as the runtime polls it.
 */
export function createMemoryosDictationAdapter(
  options: MemoryosDictationAdapterOptions,
): DictationAdapter {
  const start = options.start ?? startVoiceDictation;
  return {
    listen() {
      const starts = new Set<() => void>();
      const speech = new Set<(result: DictationAdapter.Result) => void>();
      const ends = new Set<(result: DictationAdapter.Result) => void>();
      let dictation: VoiceDictation | undefined;
      let finished = false;
      let stopping: Promise<void> | undefined;

      const end = (reason: DictationEnd, error?: unknown) => {
        if (finished) return;
        finished = true;
        session.status = { type: "ended", reason };
        options.onEnded?.(reason, error);
      };
      const subscribe =
        <T>(callbacks: Set<T>) =>
        (callback: T) => {
          callbacks.add(callback);
          return () => {
            callbacks.delete(callback);
          };
        };

      const session: DictationAdapter.Session = {
        status: { type: "starting" },
        stop: () => {
          stopping ??= (async () => {
            if (!dictation) {
              end("stopped");
              return;
            }
            options.onStopping?.();
            try {
              const transcript = await dictation.stop();
              const result = { transcript, isFinal: true };
              if (transcript) for (const callback of speech) callback(result);
              for (const callback of ends) callback(result);
              end("stopped");
            } catch (error) {
              end("error", error);
            }
          })();
          return stopping;
        },
        cancel: () => {
          dictation?.cancel();
          end("cancelled");
        },
        onSpeechStart: subscribe(starts),
        onSpeechEnd: subscribe(ends),
        onSpeech: subscribe(speech),
      };

      options.onStarting?.();
      start({
        language: options.language(),
        requestTicket: options.requestTicket,
        onInterim: (transcript) => {
          if (finished) return;
          for (const callback of speech) callback({ transcript, isFinal: false });
        },
        onLevel: (level) => options.onLevel?.(level),
        onFailure: (error) => {
          dictation = undefined;
          end("error", error);
        },
      }).then(
        (started) => {
          if (finished) {
            started.cancel();
            return;
          }
          dictation = started;
          session.status = { type: "running" };
          options.onStarted?.(started);
          for (const callback of starts) callback();
        },
        (error: unknown) => end("error", error),
      );
      return session;
    },
  };
}
