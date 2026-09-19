import workletUrl from "./pcm-capture.worklet.ts?worker&url";

export type AudioCaptureHandlers = {
  /** PCM16 little-endian mono 24 kHz, about 100 ms per chunk. */
  onChunk: (pcm: ArrayBuffer) => void;
  /** RMS level of the latest chunk, 0–1. */
  onLevel: (level: number) => void;
};

export type AudioCapture = {
  setMuted: (muted: boolean) => void;
  stop: () => void;
};

export class MicrophoneUnavailableError extends Error {
  readonly permissionDenied: boolean;

  constructor(permissionDenied: boolean, cause: unknown) {
    super(permissionDenied ? "Microphone permission denied" : "Microphone unavailable", { cause });
    this.name = "MicrophoneUnavailableError";
    this.permissionDenied = permissionDenied;
  }
}

/**
 * Starts microphone capture through an AudioWorklet. The permission prompt appears first; tracks, nodes and the
 * audio context are always released by {@link AudioCapture.stop} or when setup fails.
 */
export async function startAudioCapture(handlers: AudioCaptureHandlers): Promise<AudioCapture> {
  let stream: MediaStream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
    });
  } catch (error) {
    const denied =
      error instanceof DOMException &&
      (error.name === "NotAllowedError" || error.name === "SecurityError");
    throw new MicrophoneUnavailableError(denied, error);
  }
  const context = new AudioContext();
  try {
    // Permission prompts can outlast the click's activation, which leaves a new context suspended.
    if (context.state === "suspended") await context.resume();
    await context.audioWorklet.addModule(workletUrl);
    const source = context.createMediaStreamSource(stream);
    const node = new AudioWorkletNode(context, "memoryos-pcm-capture", {
      numberOfInputs: 1,
      numberOfOutputs: 0,
      channelCount: 1,
    });
    node.port.onmessage = (event: MessageEvent<{ pcm: ArrayBuffer; level: number }>) => {
      handlers.onChunk(event.data.pcm);
      handlers.onLevel(event.data.level);
    };
    source.connect(node);
    let stopped = false;
    return {
      setMuted(muted) {
        for (const track of stream.getAudioTracks()) track.enabled = !muted;
      },
      stop() {
        if (stopped) return;
        stopped = true;
        node.port.onmessage = null;
        source.disconnect();
        node.disconnect();
        for (const track of stream.getTracks()) track.stop();
        void context.close();
      },
    };
  } catch (error) {
    for (const track of stream.getTracks()) track.stop();
    void context.close();
    throw new MicrophoneUnavailableError(false, error);
  }
}
