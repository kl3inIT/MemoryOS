import { afterEach, describe, expect, it, vi } from "vitest";
import { FINAL_TIMEOUT_MS, openTranscriptionSocket, VoiceStreamError } from "./transcribe-socket";

class FakeSocket {
  readyState = 0;
  binaryType = "blob";
  readonly sent: unknown[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onclose: (() => void) | null = null;
  readonly url: string;

  constructor(url: string) {
    this.url = url;
  }

  send(data: unknown) {
    this.sent.push(data);
  }

  close() {
    if (this.readyState === 3) return;
    this.readyState = 3;
    this.onclose?.();
  }

  open() {
    this.readyState = 1;
    this.onopen?.();
  }

  receive(message: unknown) {
    this.onmessage?.({ data: JSON.stringify(message) } as MessageEvent);
  }
}

function connect({
  language = "vi",
  location = { origin: "https://memoryos.example", protocol: "https:" },
}: { language?: string; location?: Pick<Location, "origin" | "protocol"> } = {}) {
  let socket: FakeSocket | undefined;
  const onInterim = vi.fn();
  const onFailure = vi.fn();
  const opening = openTranscriptionSocket({
    ticket: "ticket-value",
    language,
    onInterim,
    onFailure,
    location,
    createSocket: (url) => (socket = new FakeSocket(url)) as unknown as WebSocket,
  });
  return { opening, socket: () => socket!, onInterim, onFailure };
}

describe("voice transcription socket", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("connects to the same-origin secure stream with the ticket and language", () => {
    expect(connect({ language: "en" }).socket().url).toBe(
      "wss://memoryos.example/api/chat/voice/transcribe/stream?ticket=ticket-value&language=en",
    );
    expect(
      connect({ location: { origin: "http://127.0.0.1:8080", protocol: "http:" } }).socket().url,
    ).toBe("ws://127.0.0.1:8080/api/chat/voice/transcribe/stream?ticket=ticket-value&language=vi");
  });

  it("streams interim text and resolves the final transcript after end", async () => {
    const { opening, socket, onInterim } = connect();
    socket().open();
    const transcription = await opening;
    expect(socket().binaryType).toBe("arraybuffer");
    const audio = new ArrayBuffer(4);
    transcription.send(audio);
    socket().receive({ type: "transcript", text: "xin", isFinal: false, revision: 1 });
    expect(onInterim).toHaveBeenCalledWith("xin");
    const final = transcription.finish();
    expect(socket().sent).toEqual([audio, JSON.stringify({ type: "end" })]);
    transcription.send(new ArrayBuffer(2));
    socket().receive({ type: "transcript", text: "xin chào", isFinal: true, revision: 2 });
    await expect(final).resolves.toBe("xin chào");
    expect(socket().sent).toHaveLength(2);
  });

  it("ignores delayed transcript revisions without regressing the composer", async () => {
    const { opening, socket, onInterim } = connect();
    socket().open();
    await opening;
    socket().receive({ type: "transcript", text: "xin chào", isFinal: false, revision: 2 });
    socket().receive({ type: "transcript", text: "xin", isFinal: false, revision: 1 });
    socket().receive({ type: "transcript", text: "invalid", isFinal: false, revision: "3" });
    socket().receive({ type: "transcript", text: "legacy delayed", isFinal: false });
    expect(onInterim).toHaveBeenCalledTimes(1);
    expect(onInterim).toHaveBeenCalledWith("xin chào");
  });

  it("does not publish an unsolicited final transcript as interim text", async () => {
    const { opening, socket, onInterim } = connect();
    socket().open();
    await opening;
    socket().receive({ type: "transcript", text: "unexpected final", isFinal: true, revision: 1 });
    expect(onInterim).not.toHaveBeenCalled();
  });

  it("keeps the latest interim text when the final transcript does not arrive in time", async () => {
    vi.useFakeTimers();
    const { opening, socket } = connect();
    socket().open();
    const transcription = await opening;
    socket().receive({ type: "transcript", text: "đoạn đầu", isFinal: false });
    const final = transcription.finish();
    vi.advanceTimersByTime(FINAL_TIMEOUT_MS);
    await expect(final).resolves.toBe("đoạn đầu");
  });

  it("reports the server error that ended a recording", async () => {
    const { opening, socket, onFailure } = connect();
    socket().open();
    await opening;
    socket().receive({ type: "error", code: "VOICE_IDLE" });
    socket().close();
    expect(onFailure).toHaveBeenCalledWith(new VoiceStreamError("VOICE_IDLE"));
    expect(onFailure.mock.calls[0][0].code).toBe("VOICE_IDLE");
  });

  it("rejects when the handshake is refused", async () => {
    const { opening, socket } = connect();
    socket().close();
    await expect(opening).rejects.toMatchObject({ code: "VOICE_CONNECTION" });
  });
});
