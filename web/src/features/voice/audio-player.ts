/** MP3 playback for read-aloud. One playback at a time: starting another stops the previous one. */

const MPEG = "audio/mpeg";

export type AudioPlayback = {
  /** Resolves once audio is playing, or when playback is stopped first. */
  readonly started: Promise<void>;
  /** Resolves at the end of the audio or on stop; rejects on a stream or decoding error. */
  readonly finished: Promise<void>;
  /** Whether the audio played to its end, as opposed to being stopped or failing. */
  readonly reachedEnd: boolean;
  stop: () => void;
  setMuted: (muted: boolean) => void;
};

export type AudioPlayerEnvironment = {
  createAudio: () => HTMLAudioElement;
  /** Absent where MediaSource cannot play MP3 (iOS Safari); the audio is then played after it has downloaded. */
  createMediaSource?: () => MediaSource;
  createObjectURL: (source: Blob | MediaSource) => string;
  revokeObjectURL: (url: string) => void;
};

function browserAudioEnvironment(): AudioPlayerEnvironment {
  const streaming = typeof MediaSource !== "undefined" && MediaSource.isTypeSupported(MPEG);
  return {
    createAudio: () => new Audio(),
    createMediaSource: streaming ? () => new MediaSource() : undefined,
    createObjectURL: (source) => URL.createObjectURL(source),
    revokeObjectURL: (url) => URL.revokeObjectURL(url),
  };
}

let active: AudioPlayback | undefined;

/** Plays MP3 as it arrives through MediaSource in `sequence` mode, as Onyx does, or from a Blob without MediaSource. */
export function playAudioStream(
  stream: ReadableStream<Uint8Array>,
  environment: AudioPlayerEnvironment = browserAudioEnvironment(),
): AudioPlayback {
  active?.stop();
  const audio = environment.createAudio();
  const reader = stream.getReader();
  let url: string | undefined;
  let settled = false;
  let reachedEnd = false;
  let resolveStarted!: () => void;
  let resolveFinished!: () => void;
  let rejectFinished!: (error: unknown) => void;
  const started = new Promise<void>((resolve) => {
    resolveStarted = resolve;
  });
  const finished = new Promise<void>((resolve, reject) => {
    resolveFinished = resolve;
    rejectFinished = reject;
  });
  finished.catch(() => {});

  function release() {
    audio.onplaying = null;
    audio.onended = null;
    audio.onerror = null;
    audio.pause();
    audio.removeAttribute("src");
    if (url) environment.revokeObjectURL(url);
    void reader.cancel().catch(() => {});
    if (active === playback) active = undefined;
  }
  function settle(error?: unknown) {
    if (settled) return;
    settled = true;
    release();
    resolveStarted();
    if (error === undefined) resolveFinished();
    else rejectFinished(error);
  }
  function play() {
    audio.play().catch((error: unknown) => settle(error));
  }

  audio.onplaying = () => resolveStarted();
  audio.onended = () => {
    reachedEnd = true;
    settle();
  };
  audio.onerror = () => settle(new Error("Audio playback failed"));

  async function feed(source: MediaSource) {
    try {
      const buffer = source.addSourceBuffer(MPEG);
      buffer.mode = "sequence";
      let playing = false;
      for (;;) {
        const { done, value } = await reader.read();
        if (settled) return;
        if (done) break;
        await append(buffer, value);
        if (!playing) {
          playing = true;
          play();
        }
      }
      if (!playing) throw new Error("No audio received");
      if (source.readyState === "open") source.endOfStream();
    } catch (error) {
      settle(error);
    }
  }

  async function download() {
    try {
      const chunks: Uint8Array[] = [];
      for (;;) {
        const { done, value } = await reader.read();
        if (settled) return;
        if (done) break;
        chunks.push(value);
      }
      if (chunks.length === 0) throw new Error("No audio received");
      url = environment.createObjectURL(new Blob(chunks as BlobPart[], { type: MPEG }));
      audio.src = url;
      play();
    } catch (error) {
      settle(error);
    }
  }

  const playback: AudioPlayback = {
    started,
    finished,
    get reachedEnd() {
      return reachedEnd;
    },
    stop: () => settle(),
    setMuted: (muted) => {
      audio.muted = muted;
    },
  };
  active = playback;
  if (environment.createMediaSource) {
    const source = environment.createMediaSource();
    url = environment.createObjectURL(source);
    source.addEventListener("sourceopen", () => void feed(source), { once: true });
    audio.src = url;
  } else {
    void download();
  }
  return playback;
}

function append(buffer: SourceBuffer, chunk: Uint8Array) {
  return new Promise<void>((resolve, reject) => {
    const done = () => {
      buffer.removeEventListener("updateend", done);
      buffer.removeEventListener("error", failed);
      resolve();
    };
    const failed = () => {
      buffer.removeEventListener("updateend", done);
      buffer.removeEventListener("error", failed);
      reject(new Error("Audio decoding failed"));
    };
    buffer.addEventListener("updateend", done);
    buffer.addEventListener("error", failed);
    buffer.appendBuffer(chunk as Uint8Array<ArrayBuffer>);
  });
}
