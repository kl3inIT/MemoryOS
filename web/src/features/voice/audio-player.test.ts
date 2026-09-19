import { describe, expect, it, vi } from "vitest";
import { playAudioStream, type AudioPlayerEnvironment } from "./audio-player";

function audioStream(...chunks: string[]) {
  const cancel = vi.fn();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(new TextEncoder().encode(chunk));
      controller.close();
    },
    cancel,
  });
  return { stream, cancel };
}

function fakeAudio() {
  return {
    src: "",
    onplaying: null as (() => void) | null,
    onended: null as (() => void) | null,
    onerror: null as (() => void) | null,
    play: vi.fn(async () => {}),
    pause: vi.fn(),
    removeAttribute: vi.fn(),
  };
}

function environment(audio: ReturnType<typeof fakeAudio>, mediaSource?: () => MediaSource) {
  return {
    createAudio: () => audio as unknown as HTMLAudioElement,
    createMediaSource: mediaSource,
    createObjectURL: vi.fn((source: Blob | MediaSource) =>
      source instanceof Blob ? `blob:audio-${source.size}` : "blob:media-source",
    ),
    revokeObjectURL: vi.fn(),
  } satisfies AudioPlayerEnvironment;
}

describe("read-aloud audio player", () => {
  it("plays downloaded MP3 where MediaSource is unavailable and releases the object URL at the end", async () => {
    const audio = fakeAudio();
    const env = environment(audio);
    const playback = playAudioStream(audioStream("mp3-1;", "mp3-2;").stream, env);

    await vi.waitFor(() => expect(audio.play).toHaveBeenCalledOnce());
    expect(audio.src).toBe("blob:audio-12");
    audio.onplaying?.();
    await playback.started;
    audio.onended?.();
    await playback.finished;
    expect(env.revokeObjectURL).toHaveBeenCalledWith("blob:audio-12");
  });

  it("appends chunks in sequence mode, starts after the first chunk and ends the media stream", async () => {
    const audio = fakeAudio();
    const appended: string[] = [];
    const buffer = Object.assign(new EventTarget(), {
      mode: "segments",
      appendBuffer: vi.fn((chunk: Uint8Array) => {
        appended.push(new TextDecoder().decode(chunk));
        queueMicrotask(() => buffer.dispatchEvent(new Event("updateend")));
      }),
    });
    const source = Object.assign(new EventTarget(), {
      readyState: "open",
      addSourceBuffer: vi.fn(() => buffer),
      endOfStream: vi.fn(),
    });
    const env = environment(audio, () => source as unknown as MediaSource);
    const playback = playAudioStream(audioStream("a", "b", "c").stream, env);
    expect(audio.src).toBe("blob:media-source");
    source.dispatchEvent(new Event("sourceopen"));

    await vi.waitFor(() => expect(source.endOfStream).toHaveBeenCalledOnce());
    expect(source.addSourceBuffer).toHaveBeenCalledWith("audio/mpeg");
    expect(buffer.mode).toBe("sequence");
    expect(appended).toEqual(["a", "b", "c"]);
    expect(audio.play).toHaveBeenCalledOnce();
    audio.onended?.();
    await expect(playback.finished).resolves.toBeUndefined();
  });

  it("stops the previous playback when another starts and rejects on a decoding error", async () => {
    const first = fakeAudio();
    const earlier = audioStream("one");
    const firstPlayback = playAudioStream(earlier.stream, environment(first));
    await vi.waitFor(() => expect(first.play).toHaveBeenCalled());

    const second = fakeAudio();
    const secondPlayback = playAudioStream(audioStream("two").stream, environment(second));
    await expect(firstPlayback.finished).resolves.toBeUndefined();
    expect(first.pause).toHaveBeenCalled();

    await vi.waitFor(() => expect(second.play).toHaveBeenCalled());
    second.onerror?.();
    await expect(secondPlayback.finished).rejects.toThrow("Audio playback failed");
  });
});
