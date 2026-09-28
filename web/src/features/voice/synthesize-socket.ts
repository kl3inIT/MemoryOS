import { VoiceStreamError } from "./transcribe-socket";

/** Client for the streaming read-aloud WebSocket (Onyx /voice/synthesize/stream parity). */

const SYNTHESIZE_STREAM_PATH = "/api/chat/voice/synthesize/stream";
const OPEN_TIMEOUT_MS = 5_000;

export type SpeechSocket = {
  /** MP3 as the server produces it; ends after `audio_done` and errors with the server code on a failure. */
  readonly audio: ReadableStream<Uint8Array>;
  /** Queues one part of the answer to read. */
  speak: (text: string) => void;
  /** No more parts; the audio ends once the queued parts are read. */
  end: () => void;
  close: () => void;
};

export type SpeechSocketOptions = {
  ticket: string;
  speed: number;
  location?: Pick<Location, "origin" | "protocol">;
  createSocket?: (url: string) => WebSocket;
};

function speechSocketUrl(
  ticket: string,
  location: Pick<Location, "origin" | "protocol"> = window.location,
) {
  const url = new URL(SYNTHESIZE_STREAM_PATH, location.origin);
  url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
  url.searchParams.set("ticket", ticket);
  return url.toString();
}

export function openSpeechSocket(options: SpeechSocketOptions): Promise<SpeechSocket> {
  return new Promise((resolve, reject) => {
    const url = speechSocketUrl(options.ticket, options.location);
    const socket = options.createSocket ? options.createSocket(url) : new WebSocket(url);
    socket.binaryType = "arraybuffer";
    let opened = false;
    let ended = false;
    let settled = false;
    let failure: VoiceStreamError | undefined;
    let controller!: ReadableStreamDefaultController<Uint8Array>;

    const closeSocket = () => {
      if (socket.readyState === WebSocket.CONNECTING || socket.readyState === WebSocket.OPEN)
        socket.close();
    };
    const audio = new ReadableStream<Uint8Array>({
      start(stream) {
        controller = stream;
      },
      cancel() {
        settled = true;
        ended = true;
        closeSocket();
      },
    });
    const settle = (error?: VoiceStreamError) => {
      if (settled) return;
      settled = true;
      if (error) controller.error(error);
      else controller.close();
    };

    const openTimer = window.setTimeout(() => {
      if (opened) return;
      opened = true;
      closeSocket();
      reject(new VoiceStreamError("VOICE_CONNECTION"));
    }, OPEN_TIMEOUT_MS);

    const api: SpeechSocket = {
      audio,
      speak(text) {
        if (!ended && socket.readyState === WebSocket.OPEN)
          socket.send(JSON.stringify({ type: "synthesize", text }));
      },
      end() {
        if (ended) return;
        ended = true;
        if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: "end" }));
      },
      close() {
        ended = true;
        settle();
        closeSocket();
      },
    };

    socket.onopen = () => {
      if (opened) return;
      opened = true;
      window.clearTimeout(openTimer);
      socket.send(JSON.stringify({ type: "config", speed: options.speed }));
      resolve(api);
    };
    socket.onmessage = (event: MessageEvent) => {
      // With binaryType "arraybuffer", every non-text frame is MP3 audio.
      if (typeof event.data !== "string") {
        if (!settled) controller.enqueue(new Uint8Array(event.data as ArrayBuffer));
        return;
      }
      let message: unknown;
      try {
        message = JSON.parse(event.data);
      } catch {
        return;
      }
      if (!message || typeof message !== "object") return;
      const { type, code } = message as Record<string, unknown>;
      if (type === "audio_done") settle();
      else if (type === "error")
        failure = new VoiceStreamError(typeof code === "string" ? code : "VOICE_UNAVAILABLE");
    };
    socket.onclose = () => {
      window.clearTimeout(openTimer);
      if (!opened) {
        opened = true;
        reject(failure ?? new VoiceStreamError("VOICE_CONNECTION"));
        return;
      }
      settle(failure ?? new VoiceStreamError("VOICE_CONNECTION"));
    };
  });
}
