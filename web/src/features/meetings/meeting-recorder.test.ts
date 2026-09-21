import { afterEach, describe, expect, it, vi } from "vitest";
import type { AudioCaptureHandlers } from "@/features/voice/capture/audio-capture";
import { MeetingRecorder, QUIET_AFTER_MS } from "./meeting-recorder";
import {
  MeetingStreamError,
  type MeetingSocket,
  type MeetingSocketOptions,
} from "./meeting-socket";

type Opened = {
  options: MeetingSocketOptions;
  sent: ArrayBuffer[];
  finish: ReturnType<typeof vi.fn<() => Promise<void>>>;
};

function stream() {
  return {
    getAudioTracks: () => [{ addEventListener: vi.fn() }],
    getTracks: () => [],
  } as unknown as MediaStream;
}

function harness() {
  let now = 0;
  const handlers = new Map<string, AudioCaptureHandlers>();
  const opened: Opened[] = [];
  const pending: {
    resolve: () => void;
    reject: (error: MeetingStreamError) => void;
    options: MeetingSocketOptions;
  }[] = [];
  let autoOpen = true;
  const recorder = new MeetingRecorder({
    meetingId: "meeting-1",
    issueTicket: vi.fn().mockResolvedValue("ticket"),
    onUtterance: vi.fn(),
    now: () => now,
    capture: vi.fn(async (_stream: MediaStream, handler: AudioCaptureHandlers) => {
      handlers.set(handlers.size === 0 ? "MIC" : "TAB", handler);
      return { setMuted: vi.fn(), stop: vi.fn() };
    }),
    openSocket: vi.fn(
      (options: MeetingSocketOptions) =>
        new Promise<MeetingSocket>((resolve, reject) => {
          const socket: Opened = {
            options,
            sent: [],
            finish: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
          };
          const open = () => {
            opened.push(socket);
            resolve({
              send: (pcm: ArrayBuffer) => socket.sent.push(pcm),
              finish: socket.finish,
              close: vi.fn(),
            });
          };
          if (autoOpen) open();
          else pending.push({ resolve: open, reject, options });
        }),
    ),
  });
  return {
    recorder,
    opened,
    pending,
    chunk: (track: "MIC" | "TAB", ms: number, level = 0.2) => {
      handlers.get(track)!.onLevel(level);
      handlers.get(track)!.onChunk(new ArrayBuffer(ms * 48));
    },
    hold: () => (autoOpen = false),
    release: () => (autoOpen = true),
    advance: (ms: number) => (now += ms),
  };
}

describe("meeting recorder", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("streams each track at its offset and queues audio until the track is connected", async () => {
    const h = harness();
    h.hold();
    const starting = h.recorder.start([
      { track: "MIC", stream: stream(), offsetMs: 5_000 },
      { track: "TAB", stream: stream(), offsetMs: 4_000 },
    ]);
    await vi.waitFor(() => expect(h.pending).toHaveLength(2));
    h.chunk("MIC", 100);
    h.chunk("MIC", 100);
    expect(h.pending[0].options.offsetMs).toBe(5_000);
    expect(h.pending[1].options.offsetMs).toBe(4_000);
    for (const socket of h.pending) socket.resolve();
    await starting;
    expect(h.opened[0].sent.map((pcm) => pcm.byteLength)).toEqual([4_800, 4_800]);
    expect(h.recorder.getSnapshot().elapsedMs).toBe(5_200);
  });

  it("reconnects a failed track at the delivered offset and replays what was queued meanwhile", async () => {
    vi.useFakeTimers();
    const h = harness();
    await h.recorder.start([{ track: "MIC", stream: stream(), offsetMs: 0 }]);
    h.chunk("MIC", 1_000);
    h.opened[0].options.onFailure(new MeetingStreamError("MEETING_CONNECTION"));
    h.chunk("MIC", 500);
    expect(h.recorder.getSnapshot().tracks[0].reconnecting).toBe(true);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(h.opened).toHaveLength(2);
    expect(h.opened[1].options.offsetMs).toBe(1_000);
    expect(h.opened[1].sent.map((pcm) => pcm.byteLength)).toEqual([24_000]);
  });

  it("stops everything on a code that reconnecting cannot fix", async () => {
    const h = harness();
    await h.recorder.start([{ track: "MIC", stream: stream(), offsetMs: 0 }]);
    h.opened[0].options.onFailure(new MeetingStreamError("MEETING_ENDED"));
    expect(h.recorder.getSnapshot()).toMatchObject({ phase: "failed", error: "MEETING_ENDED" });
  });

  it("pausing stores what was said and drops audio; resuming continues the clock without the pause", async () => {
    const h = harness();
    await h.recorder.start([{ track: "MIC", stream: stream(), offsetMs: 0 }]);
    h.chunk("MIC", 2_000);
    await h.recorder.pause();
    expect(h.opened[0].finish).toHaveBeenCalled();
    h.chunk("MIC", 3_000);
    await h.recorder.resume();
    expect(h.opened[1].options.offsetMs).toBe(2_000);
    expect(h.recorder.getSnapshot().elapsedMs).toBe(2_000);
  });

  it("flags a shared tab that stays quiet, as Nojoin's quiet hint does", async () => {
    const h = harness();
    await h.recorder.start([
      { track: "MIC", stream: stream(), offsetMs: 0 },
      { track: "TAB", stream: stream(), offsetMs: 0 },
    ]);
    h.advance(QUIET_AFTER_MS + 1);
    h.chunk("TAB", 100, 0);
    expect(h.recorder.getSnapshot().tracks.find((track) => track.track === "TAB")?.quiet).toBe(
      true,
    );
    h.chunk("TAB", 100, 0.2);
    expect(h.recorder.getSnapshot().tracks.find((track) => track.track === "TAB")?.quiet).toBe(
      false,
    );
  });
});
