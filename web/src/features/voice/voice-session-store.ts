import { useSyncExternalStore } from "react";
import type { DictationEnd } from "./memoryos-dictation-adapter";
import type { VoiceDictation } from "./voice-dictation";

/** About four seconds of 100 ms levels, enough for the composer meter. */
const LEVEL_HISTORY = 40;

export type VoiceSessionSnapshot = {
  readonly phase: "idle" | "starting" | "running" | "finishing";
  readonly levels: readonly number[];
  readonly startedAt: number | undefined;
  readonly muted: boolean;
  readonly failure: unknown;
  /** The latest end; `sequence` changes for every dictation that ends. */
  readonly lastEnd: { readonly reason: DictationEnd; readonly sequence: number } | undefined;
};

const initial: VoiceSessionSnapshot = {
  phase: "idle",
  levels: [],
  startedAt: undefined,
  muted: false,
  failure: undefined,
  lastEnd: undefined,
};

/**
 * Presentation state of the browser's single dictation: meter, elapsed time, mute and the last outcome. The runtime
 * still owns the draft text and dictation status; no audio or transcript is kept here.
 */
export class VoiceSessionStore {
  private snapshot = initial;
  private readonly listeners = new Set<() => void>();
  private controls: Pick<VoiceDictation, "setMuted"> | undefined;

  readonly subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  readonly getSnapshot = () => this.snapshot;

  readonly starting = () =>
    this.update({
      phase: "starting",
      levels: [],
      startedAt: undefined,
      muted: false,
      failure: undefined,
    });

  readonly started = (controls: Pick<VoiceDictation, "setMuted">) => {
    this.controls = controls;
    this.update({ phase: "running", startedAt: Date.now() });
  };

  readonly level = (level: number) => {
    if (this.snapshot.phase !== "running") return;
    this.update({ levels: [...this.snapshot.levels, level].slice(-LEVEL_HISTORY) });
  };

  readonly stopping = () => this.update({ phase: "finishing" });

  readonly ended = (reason: DictationEnd, error?: unknown) => {
    this.controls = undefined;
    this.update({
      phase: "idle",
      muted: false,
      failure: reason === "error" ? (error ?? new Error("Dictation failed")) : undefined,
      lastEnd: { reason, sequence: (this.snapshot.lastEnd?.sequence ?? 0) + 1 },
    });
  };

  readonly setMuted = (muted: boolean) => {
    if (!this.controls) return;
    this.controls.setMuted(muted);
    this.update({ muted });
  };

  readonly dismissFailure = () => this.update({ failure: undefined });

  /** A voice failure outside dictation, such as read-aloud, shown in the same composer notice. */
  readonly fail = (error: unknown) => this.update({ failure: error });

  private update(change: Partial<VoiceSessionSnapshot>) {
    this.snapshot = { ...this.snapshot, ...change };
    for (const listener of this.listeners) listener();
  }
}

/** One microphone per tab, so Chat composers share one dictation state. */
export const chatDictationSession = new VoiceSessionStore();

export function useVoiceSession(store: VoiceSessionStore = chatDictationSession) {
  return useSyncExternalStore(store.subscribe, store.getSnapshot);
}
