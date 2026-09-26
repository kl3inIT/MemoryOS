import { startStreamCapture, type AudioCapture } from "@/features/voice/capture/audio-capture";
import {
  MeetingStreamError,
  openMeetingSocket,
  TERMINAL_CODES,
  type MeetingSocket,
  type MeetingTrack,
  type StreamedUtterance,
} from "./meeting-socket";

/** PCM16 mono at 24 kHz. */
const BYTES_PER_MS = 48;
/** Audio kept while a track reconnects; older audio is dropped rather than growing without bound. */
const MAX_QUEUED_BYTES = 30_000 * BYTES_PER_MS;
const RECONNECT_DELAYS_MS = [1_000, 2_000, 5_000, 10_000, 20_000];
/** Nojoin's quiet hint: a level under 6 on its 0–100 scale (RMS × 180) for 20 seconds. */
const QUIET_LEVEL = 6 / 180;
export const QUIET_AFTER_MS = 20_000;

type RecorderPhase = "recording" | "paused" | "stopping" | "stopped" | "failed";

type TrackSnapshot = {
  track: MeetingTrack;
  level: number;
  connected: boolean;
  reconnecting: boolean;
  /** The shared tab has stayed quiet long enough to suspect that its audio is not shared. */
  quiet: boolean;
  /** The person stopped sharing the tab. */
  ended: boolean;
};

export type RecorderSnapshot = {
  phase: RecorderPhase;
  elapsedMs: number;
  tracks: TrackSnapshot[];
  previews: Partial<Record<MeetingTrack, { speaker: string; text: string }>>;
  error?: string;
};

export type RecorderDependencies = {
  meetingId: string;
  issueTicket: (track: MeetingTrack) => Promise<string>;
  onUtterance: (utterance: StreamedUtterance) => void;
  openSocket?: typeof openMeetingSocket;
  capture?: typeof startStreamCapture;
  now?: () => number;
};

type Pipe = {
  track: MeetingTrack;
  stream: MediaStream;
  startOffsetMs: number;
  capture?: AudioCapture;
  socket?: MeetingSocket;
  connecting: boolean;
  attempt: number;
  timer?: number;
  queue: ArrayBuffer[];
  queuedBytes: number;
  capturedBytes: number;
  deliveredBytes: number;
  level: number;
  lastLoudAt: number;
  ended: boolean;
};

/**
 * Records a meeting's tracks. Each track has its own capture and socket; while a socket reconnects its audio is queued
 * and the new socket starts at the delivered offset, so the meeting clock continues. Pausing drops audio and closes the
 * sockets; resuming opens new ones where the recording left off.
 */
export class MeetingRecorder {
  private readonly pipes: Pipe[] = [];
  private readonly listeners = new Set<() => void>();
  private readonly deps: Required<RecorderDependencies>;
  private snapshot: RecorderSnapshot = {
    phase: "recording",
    elapsedMs: 0,
    tracks: [],
    previews: {},
  };
  private wakeLock?: { release: () => Promise<void> };

  constructor(deps: RecorderDependencies) {
    this.deps = {
      openSocket: openMeetingSocket,
      capture: startStreamCapture,
      now: () => Date.now(),
      ...deps,
    };
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = () => this.snapshot;

  /** Starts every track; `offsets` continue a meeting that already has audio on a track. */
  async start(sources: { track: MeetingTrack; stream: MediaStream; offsetMs: number }[]) {
    const added: Pipe[] = [];
    for (const source of sources) {
      const pipe: Pipe = {
        track: source.track,
        stream: source.stream,
        startOffsetMs: source.offsetMs,
        connecting: false,
        attempt: 0,
        queue: [],
        queuedBytes: 0,
        capturedBytes: 0,
        deliveredBytes: 0,
        level: 0,
        lastLoudAt: this.deps.now(),
        ended: false,
      };
      this.pipes.push(pipe);
      added.push(pipe);
      this.watchEnd(pipe);
      pipe.capture = await this.deps.capture(pipe.stream, {
        onChunk: (pcm) => this.chunk(pipe, pcm),
        onLevel: (level) => this.level(pipe, level),
      });
    }
    await Promise.all(added.map((pipe) => this.connect(pipe)));
    if (!this.wakeLock) void this.lockScreen();
    this.publish();
  }

  /**
   * Records a newly shared tab: it replaces a tab whose sharing stopped, whose clock continues where it ended, or
   * adds the tab that was not shared at the start, starting at the meeting's current time.
   */
  async replaceTab(stream: MediaStream) {
    const old = this.pipes.find((pipe) => pipe.track === "TAB");
    if (!old) {
      await this.start([{ track: "TAB", stream, offsetMs: this.snapshot.elapsedMs }]);
      return;
    }
    old.capture?.stop();
    old.stream = stream;
    old.ended = false;
    old.lastLoudAt = this.deps.now();
    this.watchEnd(old);
    old.capture = await this.deps.capture(stream, {
      onChunk: (pcm) => this.chunk(old, pcm),
      onLevel: (level) => this.level(old, level),
    });
    if (this.snapshot.phase === "recording" && !old.socket && !old.connecting)
      await this.connect(old);
    this.publish();
  }

  async pause() {
    if (this.snapshot.phase !== "recording") return;
    this.update({ phase: "paused" });
    await Promise.all(this.pipes.map((pipe) => this.closeSocket(pipe)));
    this.publish();
  }

  async resume() {
    if (this.snapshot.phase !== "paused") return;
    this.update({ phase: "recording" });
    await Promise.all(this.pipes.filter((pipe) => !pipe.ended).map((pipe) => this.connect(pipe)));
    this.publish();
  }

  /**
   * Turns the microphone and the tab off at once, then waits while what they already sent is stored and releases the
   * sockets. Nothing new is captured once the person pressed stop.
   */
  async stop() {
    if (this.snapshot.phase === "stopping" || this.snapshot.phase === "stopped") return;
    this.update({ phase: "stopping" });
    for (const pipe of this.pipes) {
      pipe.capture?.stop();
      pipe.capture = undefined;
    }
    await Promise.all(this.pipes.map((pipe) => this.closeSocket(pipe)));
    this.release();
    this.update({ phase: "stopped", previews: {} });
  }

  /** Releases everything at once, for leaving the page. */
  dispose() {
    for (const pipe of this.pipes) {
      pipe.socket?.close();
      pipe.socket = undefined;
    }
    this.release();
    this.listeners.clear();
  }

  private release() {
    for (const pipe of this.pipes) {
      window.clearTimeout(pipe.timer);
      pipe.capture?.stop();
      pipe.capture = undefined;
      pipe.queue = [];
      pipe.queuedBytes = 0;
    }
    void this.wakeLock?.release().catch(() => undefined);
    this.wakeLock = undefined;
  }

  private chunk(pipe: Pipe, pcm: ArrayBuffer) {
    if (this.snapshot.phase !== "recording" || pipe.ended) return;
    pipe.capturedBytes += pcm.byteLength;
    if (pipe.socket) {
      pipe.socket.send(pcm);
      pipe.deliveredBytes += pcm.byteLength;
      return;
    }
    pipe.queue.push(pcm);
    pipe.queuedBytes += pcm.byteLength;
    while (pipe.queuedBytes > MAX_QUEUED_BYTES) {
      const dropped = pipe.queue.shift();
      if (!dropped) break;
      pipe.queuedBytes -= dropped.byteLength;
      // Dropped audio still advances the clock, so later speech keeps its true time.
      pipe.deliveredBytes += dropped.byteLength;
    }
  }

  private level(pipe: Pipe, level: number) {
    pipe.level = level;
    if (level >= QUIET_LEVEL) pipe.lastLoudAt = this.deps.now();
    this.publish();
  }

  private async connect(pipe: Pipe) {
    if (pipe.socket || pipe.connecting || this.snapshot.phase !== "recording") return;
    pipe.connecting = true;
    pipe.timer = undefined;
    this.publish();
    try {
      const ticket = await this.deps.issueTicket(pipe.track);
      const socket = await this.deps.openSocket({
        meetingId: this.deps.meetingId,
        track: pipe.track,
        offsetMs: pipe.startOffsetMs + pipe.deliveredBytes / BYTES_PER_MS,
        ticket,
        onPreview: (speaker, text) =>
          this.update({ previews: { ...this.snapshot.previews, [pipe.track]: { speaker, text } } }),
        onUtterance: (utterance) => {
          this.update({ previews: { ...this.snapshot.previews, [pipe.track]: undefined } });
          this.deps.onUtterance(utterance);
        },
        onFailure: (error) => this.socketFailed(pipe, error),
      });
      if (this.snapshot.phase !== "recording") {
        socket.close();
        return;
      }
      pipe.socket = socket;
      pipe.attempt = 0;
      for (const pcm of pipe.queue) {
        socket.send(pcm);
        pipe.deliveredBytes += pcm.byteLength;
      }
      pipe.queue = [];
      pipe.queuedBytes = 0;
    } catch (error) {
      this.socketFailed(
        pipe,
        error instanceof MeetingStreamError ? error : new MeetingStreamError("MEETING_CONNECTION"),
      );
    } finally {
      pipe.connecting = false;
      this.publish();
    }
  }

  private socketFailed(pipe: Pipe, error: MeetingStreamError) {
    pipe.socket = undefined;
    if (this.snapshot.phase !== "recording") return;
    if (TERMINAL_CODES.has(error.code) || pipe.attempt >= RECONNECT_DELAYS_MS.length) {
      this.release();
      for (const other of this.pipes) other.socket?.close();
      this.update({ phase: "failed", error: error.code, previews: {} });
      return;
    }
    const delay = RECONNECT_DELAYS_MS[pipe.attempt++];
    window.clearTimeout(pipe.timer);
    pipe.timer = window.setTimeout(() => void this.connect(pipe), delay);
    this.publish();
  }

  private async closeSocket(pipe: Pipe) {
    window.clearTimeout(pipe.timer);
    pipe.timer = undefined;
    const socket = pipe.socket;
    pipe.socket = undefined;
    if (!socket) return;
    await socket.finish();
    socket.close();
  }

  private watchEnd(pipe: Pipe) {
    if (pipe.track !== "TAB") return;
    for (const track of pipe.stream.getAudioTracks()) {
      track.addEventListener("ended", () => {
        pipe.ended = true;
        void this.closeSocket(pipe).then(() => this.publish());
        this.publish();
      });
    }
  }

  private async lockScreen() {
    const wake = (
      navigator as Navigator & {
        wakeLock?: { request: (type: "screen") => Promise<{ release: () => Promise<void> }> };
      }
    ).wakeLock;
    if (!wake) return;
    try {
      this.wakeLock = await wake.request("screen");
    } catch {
      // Best effort, as in Nojoin: a denied wake lock only means the screen may dim.
    }
  }

  private update(change: Partial<RecorderSnapshot>) {
    this.snapshot = { ...this.snapshot, ...change };
    this.publish();
  }

  private publish() {
    const now = this.deps.now();
    const mic = this.pipes.find((pipe) => pipe.track === "MIC") ?? this.pipes[0];
    this.snapshot = {
      ...this.snapshot,
      elapsedMs: mic ? mic.startOffsetMs + mic.capturedBytes / BYTES_PER_MS : 0,
      tracks: this.pipes.map((pipe) => ({
        track: pipe.track,
        level: pipe.level,
        connected: !!pipe.socket,
        reconnecting:
          !pipe.socket &&
          (pipe.connecting || pipe.timer !== undefined) &&
          this.snapshot.phase === "recording",
        quiet:
          pipe.track === "TAB" &&
          this.snapshot.phase === "recording" &&
          now - pipe.lastLoudAt >= QUIET_AFTER_MS,
        ended: pipe.ended,
      })),
    };
    for (const listener of this.listeners) listener();
  }
}
