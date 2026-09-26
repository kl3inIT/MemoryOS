/** Client for the voice transcription WebSocket (Onyx /voice/transcribe/stream parity). */

const TRANSCRIBE_STREAM_PATH = "/api/chat/voice/transcribe/stream";
const OPEN_TIMEOUT_MS = 5_000;
/**
 * The server finishes by transcribing the whole recording again, which takes provider time. Onyx waits three
 * seconds and keeps the interim text; MemoryOS waits longer so the final pass is normally used.
 */
export const FINAL_TIMEOUT_MS = 15_000;

export class VoiceStreamError extends Error {
  readonly code: string;

  constructor(code: string) {
    super(`Voice transcription failed: ${code}`);
    this.name = "VoiceStreamError";
    this.code = code;
  }
}

export type TranscriptionSocket = {
  send: (pcm: ArrayBuffer) => void;
  /** Asks for the final transcript; resolves with the latest text when the server does not answer in time. */
  finish: () => Promise<string>;
  close: () => void;
};

export type TranscriptionSocketOptions = {
  ticket: string;
  language: string;
  onInterim: (text: string) => void;
  /** A server error or unexpected close during recording. */
  onFailure: (error: VoiceStreamError) => void;
  location?: Pick<Location, "origin" | "protocol">;
  createSocket?: (url: string) => WebSocket;
};

function transcriptionUrl(
  ticket: string,
  language: string,
  location: Pick<Location, "origin" | "protocol"> = window.location,
) {
  const url = new URL(TRANSCRIBE_STREAM_PATH, location.origin);
  url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
  url.searchParams.set("ticket", ticket);
  url.searchParams.set("language", language);
  return url.toString();
}

export function openTranscriptionSocket(
  options: TranscriptionSocketOptions,
): Promise<TranscriptionSocket> {
  return new Promise((resolve, reject) => {
    const url = transcriptionUrl(options.ticket, options.language, options.location);
    const socket = options.createSocket ? options.createSocket(url) : new WebSocket(url);
    socket.binaryType = "arraybuffer";
    let opened = false;
    let finishing = false;
    let latest = "";
    let latestRevision = 0;
    let revisionsObserved = false;
    let settleFinal: ((text: string) => void) | undefined;
    let failure: VoiceStreamError | undefined;

    const openTimer = window.setTimeout(() => {
      if (opened) return;
      opened = true;
      socket.close();
      reject(new VoiceStreamError("VOICE_CONNECTION"));
    }, OPEN_TIMEOUT_MS);

    const api: TranscriptionSocket = {
      send(pcm) {
        if (socket.readyState === WebSocket.OPEN && !finishing) socket.send(pcm);
      },
      finish() {
        if (finishing) return Promise.resolve(latest);
        finishing = true;
        if (socket.readyState !== WebSocket.OPEN) return Promise.resolve(latest);
        socket.send(JSON.stringify({ type: "end" }));
        return new Promise<string>((done) => {
          const timer = window.setTimeout(() => settleFinal?.(latest), FINAL_TIMEOUT_MS);
          settleFinal = (text) => {
            window.clearTimeout(timer);
            settleFinal = undefined;
            done(text);
          };
        });
      },
      close() {
        finishing = true;
        if (socket.readyState === WebSocket.CONNECTING || socket.readyState === WebSocket.OPEN) {
          socket.close();
        }
      },
    };

    socket.onopen = () => {
      if (opened) return;
      opened = true;
      window.clearTimeout(openTimer);
      resolve(api);
    };
    socket.onmessage = (event: MessageEvent) => {
      if (typeof event.data !== "string") return;
      let message: unknown;
      try {
        message = JSON.parse(event.data);
      } catch {
        return;
      }
      if (!message || typeof message !== "object") return;
      const { type, text, isFinal, revision, code } = message as Record<string, unknown>;
      if (type === "transcript" && typeof text === "string") {
        if (revision === undefined) {
          if (revisionsObserved) return;
        } else {
          if (
            typeof revision !== "number" ||
            !Number.isSafeInteger(revision) ||
            revision <= latestRevision
          )
            return;
          revisionsObserved = true;
          latestRevision = revision;
        }
        latest = text;
        if (isFinal === true) settleFinal?.(text);
        else if (!finishing) options.onInterim(text);
      } else if (type === "error") {
        failure = new VoiceStreamError(typeof code === "string" ? code : "VOICE_UNAVAILABLE");
      }
    };
    socket.onclose = () => {
      window.clearTimeout(openTimer);
      if (!opened) {
        opened = true;
        reject(failure ?? new VoiceStreamError("VOICE_CONNECTION"));
        return;
      }
      if (settleFinal) {
        // A failed final pass keeps the interim text rather than discarding the recording.
        settleFinal(latest);
        return;
      }
      if (!finishing) options.onFailure(failure ?? new VoiceStreamError("VOICE_CONNECTION"));
    };
  });
}
