import type { DictationAdapter } from "@assistant-ui/react";
import { describe, expect, it, vi } from "vitest";
import { createMemoryosDictationAdapter } from "./memoryos-dictation-adapter";
import type { VoiceDictation, VoiceDictationOptions } from "./voice-dictation";

function fakeStart() {
  let options: VoiceDictationOptions | undefined;
  let resolve: ((dictation: VoiceDictation) => void) | undefined;
  let reject: ((error: unknown) => void) | undefined;
  const dictation = {
    setMuted: vi.fn(),
    stop: vi.fn(async () => "xin chào"),
    cancel: vi.fn(),
  };
  const start = vi.fn(
    (next: VoiceDictationOptions) =>
      new Promise<VoiceDictation>((done, fail) => {
        options = next;
        resolve = done;
        reject = fail;
      }),
  );
  return {
    start,
    dictation,
    options: () => options!,
    resolve: () => resolve!(dictation),
    reject: (error: unknown) => reject!(error),
  };
}

describe("MemoryOS dictation adapter", () => {
  it("previews interim text and emits the final transcript before stop settles", async () => {
    const fake = fakeStart();
    const events: string[] = [];
    const session = createMemoryosDictationAdapter({
      language: () => "vi",
      requestTicket: async () => "ticket",
      onStarting: () => events.push("starting"),
      onStarted: (controls) => events.push(controls === fake.dictation ? "started" : "wrong"),
      onStopping: () => events.push("stopping"),
      onEnded: (reason) => events.push(`ended:${reason}`),
      start: fake.start,
    }).listen();
    expect(session.status).toEqual({ type: "starting" });
    expect(fake.options().language).toBe("vi");
    const results: DictationAdapter.Result[] = [];
    const started = vi.fn();
    session.onSpeech((result) => results.push(result));
    session.onSpeechStart(started);

    fake.resolve();
    await vi.waitFor(() => expect(session.status).toEqual({ type: "running" }));
    expect(started).toHaveBeenCalledOnce();
    fake.options().onInterim("xin");

    let finalBeforeSettle = false;
    await session.stop().then(() => {
      finalBeforeSettle = results.length === 2;
    });
    expect(results).toEqual([
      { transcript: "xin", isFinal: false },
      { transcript: "xin chào", isFinal: true },
    ]);
    expect(finalBeforeSettle).toBe(true);
    expect(session.status).toEqual({ type: "ended", reason: "stopped" });
    expect(events).toEqual(["starting", "started", "stopping", "ended:stopped"]);
  });

  it("cancels a dictation that is still starting", async () => {
    const fake = fakeStart();
    const session = createMemoryosDictationAdapter({
      language: () => "en",
      requestTicket: async () => "ticket",
      start: fake.start,
    }).listen();
    session.cancel();
    fake.resolve();
    await vi.waitFor(() => expect(fake.dictation.cancel).toHaveBeenCalled());
    expect(session.status).toEqual({ type: "ended", reason: "cancelled" });
  });

  it("ends with an error when the microphone or connection fails", async () => {
    const fake = fakeStart();
    const onEnded = vi.fn();
    const session = createMemoryosDictationAdapter({
      language: () => "vi",
      requestTicket: async () => "ticket",
      onEnded,
      start: fake.start,
    }).listen();
    const failure = new Error("permission denied");
    fake.reject(failure);
    await vi.waitFor(() => expect(session.status).toEqual({ type: "ended", reason: "error" }));
    expect(onEnded).toHaveBeenCalledExactlyOnceWith("error", failure);
  });
});
