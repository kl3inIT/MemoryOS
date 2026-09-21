/** Client for one meeting track's recording WebSocket (`/api/meeting-stream`). */

export const MEETING_STREAM_PATH = "/api/meeting-stream";
const OPEN_TIMEOUT_MS = 8_000;
/** The server stores the last utterances after an end; a slow provider may take a few seconds. */
const FINISH_TIMEOUT_MS = 20_000;

export type MeetingTrack = "MIC" | "TAB";

export type StreamedUtterance = {
  id: string;
  track: MeetingTrack;
  speaker: string;
  startMs: number;
  endMs: number;
  text: string;
  confidence: number;
};

/** Codes after which reconnecting cannot help. */
export const TERMINAL_CODES = new Set([
  "MEETING_ENDED",
  "MEETING_NOT_FOUND",
  "MEETING_TOO_LONG",
  "MEETING_INVALID",
  "MEETING_INVALID_AUDIO",
  "MEETING_INVALID_MESSAGE",
  "MEETING_UNAVAILABLE",
]);

export class MeetingStreamError extends Error {
  readonly code: string;

  constructor(code: string) {
    super(`Meeting stream failed: ${code}`);
    this.name = "MeetingStreamError";
    this.code = code;
  }
}

export type MeetingSocket = {
  send: (pcm: ArrayBuffer) => void;
  /** Sends the end marker and resolves once the server stored the last utterances or gave up. */
  finish: () => Promise<void>;
  close: () => void;
};

export type MeetingSocketOptions = {
  meetingId: string;
  track: MeetingTrack;
  offsetMs: number;
  ticket: string;
  onPreview: (speaker: string, text: string) => void;
  onUtterance: (utterance: StreamedUtterance) => void;
  /** A server error or an unexpected close while recording. */
  onFailure: (error: MeetingStreamError) => void;
  location?: Pick<Location, "origin" | "protocol">;
  createSocket?: (url: string) => WebSocket;
};

export function meetingStreamUrl(
  options: Pick<MeetingSocketOptions, "meetingId" | "track" | "offsetMs" | "ticket">,
  location: Pick<Location, "origin" | "protocol"> = window.location,
) {
  const url = new URL(MEETING_STREAM_PATH, location.origin);
  url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
  url.searchParams.set("meeting", options.meetingId);
  url.searchParams.set("track", options.track);
  url.searchParams.set("offset", String(Math.max(0, Math.floor(options.offsetMs))));
  url.searchParams.set("ticket", options.ticket);
  return url.toString();
}

function parseUtterance(value: unknown): StreamedUtterance | undefined {
  if (!value || typeof value !== "object") return undefined;
  const item = value as Record<string, unknown>;
  if (
    typeof item.id !== "string" ||
    (item.track !== "MIC" && item.track !== "TAB") ||
    typeof item.speaker !== "string" ||
    typeof item.startMs !== "number" ||
    typeof item.endMs !== "number" ||
    typeof item.text !== "string"
  )
    return undefined;
  return {
    id: item.id,
    track: item.track,
    speaker: item.speaker,
    startMs: item.startMs,
    endMs: item.endMs,
    text: item.text,
    confidence: typeof item.confidence === "number" ? item.confidence : 1,
  };
}

/** Opens the socket and resolves once the server has opened the provider stream (`ready`). */
export function openMeetingSocket(options: MeetingSocketOptions): Promise<MeetingSocket> {
  return new Promise((resolve, reject) => {
    const url = meetingStreamUrl(options, options.location);
    const socket = options.createSocket ? options.createSocket(url) : new WebSocket(url);
    socket.binaryType = "arraybuffer";
    let ready = false;
    let settled = false;
    let finishing = false;
    let finished = false;
    let settleFinish: (() => void) | undefined;
    let reported = false;

    const fail = (code: string) => {
      if (!settled) {
        settled = true;
        window.clearTimeout(openTimer);
        reject(new MeetingStreamError(code));
        return;
      }
      if (!finishing && !reported) {
        reported = true;
        options.onFailure(new MeetingStreamError(code));
      }
      settleFinish?.();
    };
    const openTimer = window.setTimeout(() => {
      if (settled) return;
      socket.close();
      fail("MEETING_CONNECTION");
    }, OPEN_TIMEOUT_MS);

    const api: MeetingSocket = {
      send(pcm) {
        if (ready && !finishing && socket.readyState === WebSocket.OPEN) socket.send(pcm);
      },
      finish() {
        if (finishing) return Promise.resolve();
        finishing = true;
        if (socket.readyState !== WebSocket.OPEN) return Promise.resolve();
        socket.send(JSON.stringify({ type: "end" }));
        return new Promise<void>((done) => {
          const timer = window.setTimeout(() => settleFinish?.(), FINISH_TIMEOUT_MS);
          settleFinish = () => {
            window.clearTimeout(timer);
            settleFinish = undefined;
            done();
          };
          if (finished) settleFinish();
        });
      },
      close() {
        finishing = true;
        if (socket.readyState === WebSocket.CONNECTING || socket.readyState === WebSocket.OPEN)
          socket.close();
      },
    };

    socket.onmessage = (event: MessageEvent) => {
      if (typeof event.data !== "string") return;
      let message: Record<string, unknown>;
      try {
        const parsed: unknown = JSON.parse(event.data);
        if (!parsed || typeof parsed !== "object") return;
        message = parsed as Record<string, unknown>;
      } catch {
        return;
      }
      switch (message.type) {
        case "ready":
          if (settled) return;
          ready = true;
          settled = true;
          window.clearTimeout(openTimer);
          resolve(api);
          return;
        case "preview":
          if (typeof message.speaker === "string" && typeof message.text === "string")
            options.onPreview(message.speaker, message.text);
          return;
        case "utterance": {
          const utterance = parseUtterance(message.utterance);
          if (utterance) options.onUtterance(utterance);
          return;
        }
        case "finished":
          finished = true;
          settleFinish?.();
          return;
        case "error":
          fail(typeof message.code === "string" ? message.code : "MEETING_UNAVAILABLE");
          return;
      }
    };
    socket.onerror = () => {
      if (!settled) fail("MEETING_CONNECTION");
    };
    socket.onclose = () => {
      window.clearTimeout(openTimer);
      if (!settled) {
        fail("MEETING_CONNECTION");
        return;
      }
      if (!finished) fail("MEETING_CONNECTION");
    };
  });
}
