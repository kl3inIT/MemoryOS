import {
  startAudioCapture,
  type AudioCapture,
  type AudioCaptureHandlers,
} from "./capture/audio-capture";
import {
  openTranscriptionSocket,
  type TranscriptionSocket,
  type TranscriptionSocketOptions,
  VoiceStreamError,
} from "./transcribe-socket";

/** Audio captured while the ticket and socket are being prepared; about five seconds. */
const MAX_PENDING_CHUNKS = 50;

export type VoiceDictationOptions = {
  language: string;
  requestTicket: () => Promise<string>;
  onInterim: (text: string) => void;
  onLevel: (level: number) => void;
  onFailure: (error: VoiceStreamError) => void;
  capture?: (handlers: AudioCaptureHandlers) => Promise<AudioCapture>;
  connect?: (options: TranscriptionSocketOptions) => Promise<TranscriptionSocket>;
};

export type VoiceDictation = {
  setMuted: (muted: boolean) => void;
  /** Stops the microphone and resolves with the final transcript. */
  stop: () => Promise<string>;
  /** Discards the recording. */
  cancel: () => void;
};

/**
 * One dictation: microphone permission first, then a single-use ticket and the WebSocket, as in Onyx. Audio that
 * arrives before the socket opens is kept briefly and sent in order.
 */
export async function startVoiceDictation(options: VoiceDictationOptions): Promise<VoiceDictation> {
  const capture = options.capture ?? startAudioCapture;
  const connect = options.connect ?? openTranscriptionSocket;
  let socket: TranscriptionSocket | undefined;
  const pending: ArrayBuffer[] = [];
  const microphone = await capture({
    onChunk(pcm) {
      if (socket) socket.send(pcm);
      else if (pending.length < MAX_PENDING_CHUNKS) pending.push(pcm);
    },
    onLevel: options.onLevel,
  });
  try {
    const ticket = await options.requestTicket();
    socket = await connect({
      ticket,
      language: options.language,
      onInterim: options.onInterim,
      onFailure(error) {
        microphone.stop();
        options.onFailure(error);
      },
    });
  } catch (error) {
    microphone.stop();
    throw error instanceof VoiceStreamError ? error : new VoiceStreamError("VOICE_CONNECTION");
  }
  for (const pcm of pending.splice(0)) socket.send(pcm);
  const connected = socket;
  return {
    setMuted: microphone.setMuted,
    async stop() {
      microphone.stop();
      try {
        return await connected.finish();
      } finally {
        connected.close();
      }
    },
    cancel() {
      microphone.stop();
      connected.close();
    },
  };
}
