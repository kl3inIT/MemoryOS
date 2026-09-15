import { appText, type AppCopy } from "@/i18n/app-text";
import { ApiError, problemCode } from "@/lib/api";
import { sameOriginMutationHeaders } from "@/lib/api";
import { createChatVoiceTicket, synthesizeChatVoice } from "@/lib/hey-api/sdk.gen";
import { MicrophoneUnavailableError } from "./capture/audio-capture";
import { VoiceStreamError } from "./transcribe-socket";

/** A failed read-aloud; the kind selects static copy. */
export class ReadAloudError extends Error {
  readonly kind: "busy" | "unavailable" | "playback" | "empty";

  constructor(kind: ReadAloudError["kind"]) {
    super(`Read aloud failed: ${kind}`);
    this.name = "ReadAloudError";
    this.kind = kind;
  }
}

/** Static copy for a failed dictation or read-aloud; browser, socket and provider error text is never shown. */
export function voiceFailureCopy(error: unknown): AppCopy {
  if (error instanceof ReadAloudError)
    switch (error.kind) {
      case "busy":
        return appText("Đọc thành tiếng đang bận. Hãy thử lại sau ít phút.");
      case "empty":
        return appText("Câu trả lời này không có nội dung để đọc.");
      case "playback":
        return appText("Trình duyệt không phát được âm thanh của câu trả lời.");
      default:
        return appText("Không đọc được câu trả lời vì nhà cung cấp giọng nói không phản hồi.");
    }
  if (error instanceof MicrophoneUnavailableError)
    return error.permissionDenied
      ? appText("Trình duyệt chưa cho phép dùng micro. Hãy cho phép quyền micro rồi thử lại.")
      : appText("Không mở được micro. Kiểm tra thiết bị ghi âm rồi thử lại.");
  switch (error instanceof VoiceStreamError ? error.code : undefined) {
    case "VOICE_BUSY":
      return appText("Nhận dạng giọng nói đang bận. Hãy thử lại sau ít phút.");
    case "VOICE_SESSION_TOO_LONG":
    case "VOICE_AUDIO_TOO_LARGE":
      return appText("Bản ghi đã đạt giới hạn độ dài nên đã dừng.");
    case "VOICE_IDLE":
      return appText("Đã dừng ghi âm vì không nhận được âm thanh.");
    case "VOICE_PROVIDER_FAILED":
      return appText("Nhà cung cấp giọng nói không trả về văn bản. Hãy thử lại.");
    default:
      return appText("Không kết nối được dịch vụ nhận dạng giọng nói. Hãy thử lại.");
  }
}

/** A single-use WebSocket ticket; the capacity limit is reported as busy rather than as a connection failure. */
export async function requestVoiceTicket() {
  try {
    const { data } = await createChatVoiceTicket({
      headers: sameOriginMutationHeaders,
      throwOnError: true,
    });
    return data.ticket;
  } catch (error) {
    throw new VoiceStreamError(
      error instanceof ApiError && problemCode(error) === "CHAT_CAPACITY_EXCEEDED"
        ? "VOICE_BUSY"
        : "VOICE_CONNECTION",
    );
  }
}

/** MP3 for speech text, streamed as the provider produces it. An abort is passed through unchanged. */
export async function synthesizeSpeech(text: string, speed: number, signal: AbortSignal) {
  try {
    const { data } = await synthesizeChatVoice({
      body: { text, speed },
      headers: sameOriginMutationHeaders,
      parseAs: "stream",
      signal,
      throwOnError: true,
    });
    const stream = data as unknown;
    if (!(stream instanceof ReadableStream)) throw new Error("No audio stream");
    return stream as ReadableStream<Uint8Array>;
  } catch (error) {
    if (signal.aborted) throw error;
    throw new ReadAloudError(
      error instanceof ApiError && problemCode(error) === "CHAT_CAPACITY_EXCEEDED"
        ? "busy"
        : "unavailable",
    );
  }
}
