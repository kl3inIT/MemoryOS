import type { SpeechSynthesisAdapter } from "@assistant-ui/react";
import { playAudioStream, type AudioPlayback } from "./audio-player";
import { speechText } from "./speech-text";
import { ReadAloudError } from "./voice-failure";

/** The server limit for one read-aloud request. */
const MAX_TEXT_LENGTH = 32_000;

type EndedStatus = Extract<SpeechSynthesisAdapter.Status, { type: "ended" }>;

export type MemoryosSpeechAdapterOptions = {
  /** The member's playback speed when reading starts. */
  speed: () => number;
  synthesize: (
    text: string,
    speed: number,
    signal: AbortSignal,
  ) => Promise<ReadableStream<Uint8Array>>;
  play?: (stream: ReadableStream<Uint8Array>) => AudioPlayback;
  onError?: (error: unknown) => void;
};

/**
 * assistant-ui read-aloud through the MemoryOS synthesize endpoint. The runtime passes the message markdown. The utterance
 * is `starting` while audio is requested, `running` once it plays, and ends finished, cancelled or with an error.
 */
export function createMemoryosSpeechAdapter(
  options: MemoryosSpeechAdapterOptions,
): SpeechSynthesisAdapter {
  const play = options.play ?? ((stream: ReadableStream<Uint8Array>) => playAudioStream(stream));
  return {
    speak(markdown) {
      const listeners = new Set<() => void>();
      const controller = new AbortController();
      let playback: AudioPlayback | undefined;
      const utterance: SpeechSynthesisAdapter.Utterance = {
        status: { type: "starting" },
        cancel: () => end({ type: "ended", reason: "cancelled" }),
        subscribe: (callback) => {
          listeners.add(callback);
          return () => {
            listeners.delete(callback);
          };
        },
      };
      const ended = () => utterance.status.type === "ended";
      const publish = (status: SpeechSynthesisAdapter.Status) => {
        utterance.status = status;
        for (const listener of [...listeners]) listener();
      };
      function end(status: EndedStatus) {
        if (ended()) return;
        controller.abort();
        playback?.stop();
        publish(status);
      }

      void (async () => {
        // The runtime subscribes after speak returns; no status changes before that.
        await Promise.resolve();
        try {
          const text = speechText(markdown).slice(0, MAX_TEXT_LENGTH);
          if (!text) throw new ReadAloudError("empty");
          const stream = await options.synthesize(text, options.speed(), controller.signal);
          if (ended()) {
            void stream.cancel().catch(() => {});
            return;
          }
          playback = play(stream);
          await playback.started;
          if (ended()) return;
          publish({ type: "running" });
          await playback.finished;
          end({ type: "ended", reason: "finished" });
        } catch (error) {
          if (ended()) return;
          options.onError?.(error);
          end({ type: "ended", reason: "error", error });
        }
      })();
      return utterance;
    },
  };
}
