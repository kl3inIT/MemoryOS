import type { SpeechSynthesisAdapter } from "@assistant-ui/react";
import { describe, expect, it, vi } from "vitest";
import type { AudioPlayback } from "./audio-player";
import { createMemoryosSpeechAdapter } from "./memoryos-speech-adapter";

function deferred() {
  let resolve!: () => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<void>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}

function fakePlayback() {
  const started = deferred();
  const finished = deferred();
  const playback: AudioPlayback = {
    started: started.promise,
    finished: finished.promise,
    stop: vi.fn(),
  };
  return { playback, started, finished };
}

function statuses(utterance: SpeechSynthesisAdapter.Utterance) {
  const seen: string[] = [];
  utterance.subscribe(() =>
    seen.push(
      utterance.status.type === "ended"
        ? `ended:${utterance.status.reason}`
        : utterance.status.type,
    ),
  );
  return seen;
}

const audio = () => new ReadableStream<Uint8Array>();

describe("MemoryOS speech adapter", () => {
  it("requests speech text at the member's speed, runs while audio plays and finishes", async () => {
    const fake = fakePlayback();
    const synthesize = vi.fn(async () => audio());
    const utterance = createMemoryosSpeechAdapter({
      speed: () => 1.3,
      synthesize,
      play: () => fake.playback,
    }).speak("**Xin chào** [1]\n\n```js\nconsole.log(1)\n```");
    const seen = statuses(utterance);
    expect(utterance.status).toEqual({ type: "starting" });

    await vi.waitFor(() => expect(synthesize).toHaveBeenCalledOnce());
    expect(synthesize).toHaveBeenCalledWith("Xin chào", 1.3, expect.any(AbortSignal));
    fake.started.resolve();
    await vi.waitFor(() => expect(utterance.status).toEqual({ type: "running" }));
    fake.finished.resolve();
    await vi.waitFor(() => expect(utterance.status.type).toBe("ended"));
    expect(seen).toEqual(["running", "ended:finished"]);
  });

  it("cancelling while audio is requested aborts the request and never plays", async () => {
    let signal: AbortSignal | undefined;
    const play = vi.fn();
    const utterance = createMemoryosSpeechAdapter({
      speed: () => 1,
      synthesize: (_text, _speed, abort) => {
        signal = abort;
        return new Promise(() => {});
      },
      play,
    }).speak("Một câu trả lời.");
    await vi.waitFor(() => expect(signal).toBeDefined());
    utterance.cancel();
    expect(signal?.aborted).toBe(true);
    expect(utterance.status).toEqual({ type: "ended", reason: "cancelled" });
    expect(play).not.toHaveBeenCalled();
  });

  it("reports a failed request and ends with an error; an answer with nothing to read requests nothing", async () => {
    const failure = new Error("provider unavailable");
    const onError = vi.fn();
    const adapter = createMemoryosSpeechAdapter({
      speed: () => 1,
      synthesize: vi.fn(async () => {
        throw failure;
      }),
      onError,
    });
    const failed = adapter.speak("Một câu trả lời.");
    await vi.waitFor(() => expect(failed.status.type).toBe("ended"));
    expect(failed.status).toEqual({ type: "ended", reason: "error", error: failure });
    expect(onError).toHaveBeenCalledWith(failure);

    const synthesize = vi.fn(async () => audio());
    const empty = createMemoryosSpeechAdapter({ speed: () => 1, synthesize }).speak(
      "```sql\nSELECT 1\n```",
    );
    expect(empty.status).toEqual({ type: "starting" });
    await vi.waitFor(() => expect(empty.status.type).toBe("ended"));
    expect(synthesize).not.toHaveBeenCalled();
  });
});
