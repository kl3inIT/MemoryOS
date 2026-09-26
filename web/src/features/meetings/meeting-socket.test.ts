import { describe, expect, it, vi } from "vitest";
import { MeetingStreamError, openMeetingSocket } from "./meeting-socket";

class FakeSocket {
  readyState = 0;
  binaryType = "blob";
  readonly sent: unknown[] = [];
  onmessage: ((event: MessageEvent) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
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

  receive(message: unknown) {
    this.readyState = 1;
    this.onmessage?.({ data: JSON.stringify(message) } as MessageEvent);
  }
}

function connect() {
  let socket: FakeSocket | undefined;
  const onPreview = vi.fn();
  const onUtterance = vi.fn();
  const onFailure = vi.fn();
  const opening = openMeetingSocket({
    meetingId: "meeting-1",
    track: "TAB",
    offsetMs: 61_234.5,
    ticket: "ticket-value",
    onPreview,
    onUtterance,
    onFailure,
    location: { origin: "https://memoryos.example", protocol: "https:" },
    createSocket: (url) => (socket = new FakeSocket(url)) as unknown as WebSocket,
  });
  return { opening, socket: () => socket!, onPreview, onUtterance, onFailure };
}

describe("meeting track socket", () => {
  it("addresses the track at a whole-millisecond offset over a secure same-origin socket", async () => {
    const { opening, socket } = connect();
    expect(socket().url).toBe(
      "wss://memoryos.example/api/meeting-stream?meeting=meeting-1&track=TAB&offset=61234&ticket=ticket-value",
    );
    socket().receive({ type: "ready" });
    (await opening).close();
  });

  it("opens on ready, sends audio, relays previews and stored utterances, and finishes after the server", async () => {
    const { opening, socket, onPreview, onUtterance, onFailure } = connect();
    socket().receive({ type: "ready" });
    const live = await opening;
    const pcm = new ArrayBuffer(4);
    live.send(pcm);
    expect(socket().sent).toEqual([pcm]);

    socket().receive({ type: "preview", track: "TAB", speaker: "2", text: "Chốt ngân" });
    socket().receive({
      type: "utterance",
      utterance: {
        id: "u1",
        track: "TAB",
        speaker: "2",
        startMs: 1000,
        endMs: 2500,
        text: "Chốt ngân sách.",
        confidence: 0.9,
        spans: [{ start: 5, end: 14, confidence: 0.42 }, { start: 99 }],
      },
    });
    socket().receive({ type: "utterance", utterance: { id: "bad" } });
    expect(onPreview).toHaveBeenCalledWith("2", "Chốt ngân");
    expect(onUtterance).toHaveBeenCalledTimes(1);
    expect(onUtterance).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({
        text: "Chốt ngân sách.",
        spans: [{ start: 5, end: 14, confidence: 0.42 }],
      }),
    );

    const finishing = live.finish();
    expect(socket().sent.at(-1)).toBe(JSON.stringify({ type: "end" }));
    socket().receive({ type: "finished" });
    await finishing;
    socket().close();
    expect(onFailure).not.toHaveBeenCalled();
  });

  it("rejects a refused open with the server code", async () => {
    const { opening, socket } = connect();
    socket().receive({ type: "error", code: "MEETING_ENDED" });
    await expect(opening).rejects.toEqual(new MeetingStreamError("MEETING_ENDED"));
  });

  it("reports a failure while recording once, even when the close follows the error", async () => {
    const { opening, socket, onFailure } = connect();
    socket().receive({ type: "ready" });
    await opening;
    socket().receive({ type: "error", code: "MEETING_PROVIDER_FAILED" });
    socket().close();
    expect(onFailure).toHaveBeenCalledTimes(1);
    expect(onFailure).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ code: "MEETING_PROVIDER_FAILED" }),
    );
  });
});
