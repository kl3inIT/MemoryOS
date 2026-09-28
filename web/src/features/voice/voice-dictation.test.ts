import { describe, expect, it, vi } from "vitest";
import type { AudioCaptureHandlers } from "./capture/audio-capture";
import type { TranscriptionSocketOptions } from "./transcribe-socket";
import { VoiceStreamError } from "./transcribe-socket";
import { startVoiceDictation } from "./voice-dictation";

const chunk = (value: number) => new Uint8Array([value]).buffer;

function microphone() {
  let handlers: AudioCaptureHandlers | undefined;
  const stop = vi.fn();
  const capture = vi.fn(async (next: AudioCaptureHandlers) => {
    handlers = next;
    next.onChunk(chunk(1));
    return { stop, setMuted: vi.fn() };
  });
  return { capture, stop, handlers: () => handlers! };
}

describe("voice dictation", () => {
  it("sends audio captured before the socket opened in order, then finishes and releases both", async () => {
    const mic = microphone();
    const sent: number[] = [];
    const finish = vi.fn(async () => "xin chào");
    const close = vi.fn();
    const dictation = await startVoiceDictation({
      language: "vi",
      requestTicket: async () => "ticket",
      onInterim: vi.fn(),
      onLevel: vi.fn(),
      onFailure: vi.fn(),
      capture: mic.capture,
      connect: async () => ({
        send: (pcm) => sent.push(...new Uint8Array(pcm).subarray(0, 1)),
        finish,
        close,
      }),
    });
    mic.handlers().onChunk(chunk(2));
    expect(sent).toEqual([1, 2]);
    await expect(dictation.stop()).resolves.toBe("xin chào");
    expect(mic.stop).toHaveBeenCalled();
    expect(close).toHaveBeenCalled();
  });

  it("releases the microphone when the ticket cannot be issued", async () => {
    const mic = microphone();
    await expect(
      startVoiceDictation({
        language: "vi",
        requestTicket: async () => {
          throw new Error("busy");
        },
        onInterim: vi.fn(),
        onLevel: vi.fn(),
        onFailure: vi.fn(),
        capture: mic.capture,
        connect: vi.fn(),
      }),
    ).rejects.toBeInstanceOf(VoiceStreamError);
    expect(mic.stop).toHaveBeenCalled();
  });

  it("stops the microphone when the server ends the recording with an error", async () => {
    const mic = microphone();
    const onFailure = vi.fn();
    let socketOptions: TranscriptionSocketOptions | undefined;
    await startVoiceDictation({
      language: "en",
      requestTicket: async () => "ticket",
      onInterim: vi.fn(),
      onLevel: vi.fn(),
      onFailure,
      capture: mic.capture,
      connect: async (options) => {
        socketOptions = options;
        return { send: vi.fn(), finish: vi.fn(), close: vi.fn() };
      },
    });
    expect(socketOptions?.language).toBe("en");
    socketOptions?.onFailure(new VoiceStreamError("VOICE_SESSION_TOO_LONG"));
    expect(mic.stop).toHaveBeenCalled();
    expect(onFailure).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ code: "VOICE_SESSION_TOO_LONG" }),
    );
  });
});
