import { describe, expect, it, vi } from "vitest";
import type { AudioPlayback } from "./audio-player";
import { AutoPlayback } from "./auto-playback";
import type { SpeechSocket } from "./synthesize-socket";

function deferred<T = void>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}

function fakeSocket() {
  const spoken: string[] = [];
  const socket: SpeechSocket = {
    audio: new ReadableStream<Uint8Array>(),
    speak: (text) => {
      spoken.push(text);
    },
    end: vi.fn(),
    close: vi.fn(),
  };
  return { socket, spoken };
}

function fakePlayback() {
  const started = deferred();
  const finished = deferred();
  let reachedEnd = false;
  const playback: AudioPlayback = {
    started: started.promise,
    finished: finished.promise,
    get reachedEnd() {
      return reachedEnd;
    },
    stop: vi.fn(),
    setMuted: vi.fn(),
  };
  return {
    playback,
    started,
    playToEnd: () => {
      reachedEnd = true;
      finished.resolve();
    },
    stopElsewhere: () => finished.resolve(),
  };
}

const streaming = "Xin chào. Đây là câu trả lời đầu tiên của trợ lý. Phần tiếp theo";
const answer = `${streaming} được đọc sau cùng.`;

describe("Auto-Playback", () => {
  it("queues parts until the socket opens, reads the whole answer and ends as finished", async () => {
    const connection = deferred<SpeechSocket>();
    const { socket, spoken } = fakeSocket();
    const audio = fakePlayback();
    const connect = vi.fn(() => connection.promise);
    const auto = new AutoPlayback({ connect, play: () => audio.playback });

    auto.begin("answer-1", 1.2);
    expect(auto.getSnapshot()).toMatchObject({ phase: "loading", messageId: "answer-1" });
    auto.append("answer-1", streaming);
    connection.resolve(socket);
    await vi.waitFor(() =>
      expect(spoken).toEqual(["Xin chào. Đây là câu trả lời đầu tiên của trợ lý."]),
    );
    expect(connect).toHaveBeenCalledWith(1.2);

    auto.complete("answer-1", answer);
    expect(spoken.at(-1)).toBe("Phần tiếp theo được đọc sau cùng.");
    expect(socket.end).toHaveBeenCalledOnce();
    audio.started.resolve();
    await vi.waitFor(() => expect(auto.getSnapshot().phase).toBe("speaking"));
    audio.playToEnd();
    await vi.waitFor(() =>
      expect(auto.getSnapshot()).toMatchObject({
        phase: "idle",
        messageId: undefined,
        lastEnd: { reason: "finished", sequence: 1 },
      }),
    );
  });

  it("ends as stopped after a manual stop or when another playback takes over", async () => {
    const { socket, spoken } = fakeSocket();
    const audio = fakePlayback();
    const auto = new AutoPlayback({ connect: async () => socket, play: () => audio.playback });
    auto.begin("answer-1", 1);
    auto.append("answer-1", streaming);
    await vi.waitFor(() => expect(spoken).toHaveLength(1));
    auto.setMuted(true);
    expect(audio.playback.setMuted).toHaveBeenCalledWith(true);
    auto.stop();
    expect(socket.close).toHaveBeenCalled();
    expect(audio.playback.stop).toHaveBeenCalled();
    expect(auto.getSnapshot()).toMatchObject({ muted: false, lastEnd: { reason: "stopped" } });

    const replaced = fakePlayback();
    const play = vi.fn(() => replaced.playback);
    const other = new AutoPlayback({ connect: async () => fakeSocket().socket, play });
    other.begin("answer-2", 1);
    await vi.waitFor(() => expect(play).toHaveBeenCalled());
    replaced.stopElsewhere();
    await vi.waitFor(() => expect(other.getSnapshot().lastEnd?.reason).toBe("stopped"));
  });

  it("skips an answer with nothing to read and reports a failed connection", async () => {
    const { socket } = fakeSocket();
    const play = vi.fn();
    const onError = vi.fn();
    const auto = new AutoPlayback({ connect: async () => socket, play, onError });
    auto.begin("answer-1", 1);
    auto.complete("answer-1", "```sql\nSELECT 1\n```");
    expect(auto.getSnapshot().lastEnd).toEqual({ reason: "skipped", sequence: 1 });
    await vi.waitFor(() => expect(socket.close).toHaveBeenCalled());
    expect(play).not.toHaveBeenCalled();

    const failure = new Error("connection refused");
    const failing = new AutoPlayback({
      connect: async () => {
        throw failure;
      },
      onError,
    });
    failing.begin("answer-2", 1);
    await vi.waitFor(() => expect(onError).toHaveBeenCalledWith(failure));
    expect(failing.getSnapshot()).toMatchObject({ phase: "idle", lastEnd: { reason: "error" } });
    expect(onError).toHaveBeenCalledOnce();
  });

  it("listens again only within five minutes of a manual dictation", () => {
    let now = 1_000;
    const auto = new AutoPlayback({ connect: async () => fakeSocket().socket, now: () => now });
    expect(auto.listensAfterSpeech()).toBe(false);
    auto.markManualDictation();
    now += 5 * 60_000 - 1;
    expect(auto.listensAfterSpeech()).toBe(true);
    now += 1;
    expect(auto.listensAfterSpeech()).toBe(false);
  });
});
