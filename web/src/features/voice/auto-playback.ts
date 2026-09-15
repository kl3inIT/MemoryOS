import { playAudioStream, type AudioPlayback } from "./audio-player";
import { speechText } from "./speech-text";
import type { SpeechSocket } from "./synthesize-socket";
import { SpeechChunker, type ChunkerTimers } from "./tts-chunker";

/** `skipped`: the answer had nothing to read; only `finished` means the audio played to its end. */
export type AutoPlaybackEnd = "finished" | "stopped" | "skipped" | "error";

export type AutoPlaybackSnapshot = {
  readonly phase: "idle" | "loading" | "speaking";
  /** The answer being read, while a run is active. */
  readonly messageId: string | undefined;
  readonly muted: boolean;
  /** The latest end; `sequence` changes for every run that ends. */
  readonly lastEnd: { readonly reason: AutoPlaybackEnd; readonly sequence: number } | undefined;
};

export type AutoPlaybackOptions = {
  connect: (speed: number) => Promise<SpeechSocket>;
  play?: (audio: ReadableStream<Uint8Array>) => AudioPlayback;
  onError?: (error: unknown) => void;
  timers?: ChunkerTimers;
  now?: () => number;
};

type Run = {
  readonly chunker: SpeechChunker;
  readonly pending: string[];
  socket?: SpeechSocket;
  playback?: AudioPlayback;
  completed: boolean;
};

/** Onyx ends the hands-free loop five minutes after the member last used the microphone. */
const LISTEN_WINDOW_MS = 5 * 60_000;

/**
 * Onyx Auto-Playback: reads one answer aloud while it is generated. Parts from {@link SpeechChunker} go to the
 * read-aloud WebSocket and its MP3 plays as it arrives. A new run, a manual stop or another playback ends the run.
 */
export class AutoPlayback {
  private snapshot: AutoPlaybackSnapshot = {
    phase: "idle",
    messageId: undefined,
    muted: false,
    lastEnd: undefined,
  };
  private readonly listeners = new Set<() => void>();
  private readonly options: AutoPlaybackOptions;
  private run: Run | undefined;
  private manualDictationAt: number | undefined;

  constructor(options: AutoPlaybackOptions) {
    this.options = options;
  }

  readonly subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  readonly getSnapshot = () => this.snapshot;

  /** An answer started streaming; the socket opens now so the first part is not delayed by the handshake. */
  readonly begin = (messageId: string | undefined, speed: number) => {
    if (this.run) this.finish(this.run, "stopped");
    const pending: string[] = [];
    const run: Run = {
      pending,
      completed: false,
      chunker: new SpeechChunker((part) => {
        if (run.socket) run.socket.speak(part);
        else pending.push(part);
      }, this.options.timers),
    };
    this.run = run;
    this.publish({ phase: "loading", messageId, muted: false });
    this.options.connect(speed).then(
      (socket) => {
        if (this.run !== run) {
          socket.close();
          return;
        }
        run.socket = socket;
        for (const part of pending.splice(0)) socket.speak(part);
        if (run.completed) socket.end();
        const playback = (this.options.play ?? playAudioStream)(socket.audio);
        run.playback = playback;
        void playback.started.then(() => {
          if (this.run === run && !playback.reachedEnd) this.publish({ phase: "speaking" });
        });
        playback.finished.then(
          () => this.finish(run, playback.reachedEnd ? "finished" : "stopped"),
          (error: unknown) => this.finish(run, "error", error),
        );
      },
      (error: unknown) => this.finish(run, "error", error),
    );
  };

  /** The answer's markdown so far. */
  readonly append = (messageId: string | undefined, markdown: string) => {
    const run = this.run;
    if (!run || run.completed) return;
    if (messageId !== this.snapshot.messageId) this.publish({ messageId });
    run.chunker.update(speechText(markdown));
  };

  /** The answer is complete: the rest is read and the socket is told no more text follows. */
  readonly complete = (messageId: string | undefined, markdown: string) => {
    const run = this.run;
    if (!run || run.completed) return;
    run.completed = true;
    run.chunker.complete(speechText(markdown));
    if (run.chunker.count === 0) {
      this.finish(run, "skipped");
      return;
    }
    if (messageId !== this.snapshot.messageId) this.publish({ messageId });
    run.socket?.end();
  };

  /** A manual stop, a cancelled answer or leaving the conversation; the microphone does not listen again. */
  readonly stop = () => {
    if (this.run) this.finish(this.run, "stopped");
  };

  readonly setMuted = (muted: boolean) => {
    if (!this.run) return;
    this.run.playback?.setMuted(muted);
    this.publish({ muted });
  };

  readonly markManualDictation = () => {
    this.manualDictationAt = this.now();
  };

  /** Whether a finished reading may open the microphone again (Onyx auto-listen). */
  readonly listensAfterSpeech = () =>
    this.manualDictationAt !== undefined && this.now() - this.manualDictationAt < LISTEN_WINDOW_MS;

  private now() {
    return this.options.now ? this.options.now() : Date.now();
  }

  private finish(run: Run, reason: AutoPlaybackEnd, error?: unknown) {
    if (this.run !== run) return;
    this.run = undefined;
    run.chunker.cancel();
    run.socket?.close();
    run.playback?.stop();
    if (reason === "error") this.options.onError?.(error);
    this.publish({
      phase: "idle",
      messageId: undefined,
      muted: false,
      lastEnd: { reason, sequence: (this.snapshot.lastEnd?.sequence ?? 0) + 1 },
    });
  }

  private publish(change: Partial<AutoPlaybackSnapshot>) {
    this.snapshot = { ...this.snapshot, ...change };
    for (const listener of this.listeners) listener();
  }
}
