import { describe, expect, it } from "vitest";
import { openSpeechSocket } from "./synthesize-socket";

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

  receiveAudio(text: string) {
    this.onmessage?.({ data: new TextEncoder().encode(text).buffer } as MessageEvent);
  }
}

function connect() {
  let socket: FakeSocket | undefined;
  const opening = openSpeechSocket({
    ticket: "ticket-value",
    speed: 1.3,
    location: { origin: "https://memoryos.example", protocol: "https:" },
    createSocket: (url) => (socket = new FakeSocket(url)) as unknown as WebSocket,
  });
  return { opening, socket: () => socket! };
}

async function read(stream: ReadableStream<Uint8Array>) {
  const reader = stream.getReader();
  const chunks: string[] = [];
  for (;;) {
    const { done, value } = await reader.read();
    if (done) return chunks;
    chunks.push(new TextDecoder().decode(value));
  }
}

describe("read-aloud socket", () => {
  it("opens the same-origin stream with the ticket and sends the speed before any text", async () => {
    const { opening, socket } = connect();
    expect(socket().url).toBe(
      "wss://memoryos.example/api/chat/voice/synthesize/stream?ticket=ticket-value",
    );
    socket().open();
    const speech = await opening;
    expect(socket().binaryType).toBe("arraybuffer");
    speech.speak("Xin chào.");
    speech.speak("Tạm biệt.");
    speech.end();
    speech.speak("Không gửi sau khi kết thúc.");
    expect(socket().sent).toEqual([
      JSON.stringify({ type: "config", speed: 1.3 }),
      JSON.stringify({ type: "synthesize", text: "Xin chào." }),
      JSON.stringify({ type: "synthesize", text: "Tạm biệt." }),
      JSON.stringify({ type: "end" }),
    ]);
  });

  it("streams audio in order until audio_done", async () => {
    const { opening, socket } = connect();
    socket().open();
    const speech = await opening;
    socket().receiveAudio("mp3-1;");
    socket().receiveAudio("mp3-2;");
    socket().receive({ type: "audio_done" });
    socket().close();
    await expect(read(speech.audio)).resolves.toEqual(["mp3-1;", "mp3-2;"]);
  });

  it("fails the audio with the server code, and closing by hand ends it without an error", async () => {
    const failed = connect();
    failed.socket().open();
    const speech = await failed.opening;
    failed.socket().receive({ type: "error", code: "VOICE_BUSY" });
    failed.socket().close();
    await expect(read(speech.audio)).rejects.toMatchObject({ code: "VOICE_BUSY" });

    const stopped = connect();
    stopped.socket().open();
    const reading = await stopped.opening;
    reading.close();
    expect(stopped.socket().readyState).toBe(3);
    await expect(read(reading.audio)).resolves.toEqual([]);
  });

  it("rejects when the handshake is refused", async () => {
    const { opening, socket } = connect();
    socket().close();
    await expect(opening).rejects.toMatchObject({ code: "VOICE_CONNECTION" });
  });
});
