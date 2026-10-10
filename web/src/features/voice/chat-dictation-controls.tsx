import { useEffect, useRef } from "react";
import { ComposerPrimitive, useAui, useAuiState } from "@assistant-ui/react";
import { Link } from "@tanstack/react-router";
import { Mic, X } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { chatAutoPlayback, useAutoPlayback } from "./use-chat-auto-playback";
import { useVoiceAvailability } from "./use-voice-availability";
import { useVoiceSettings } from "./use-voice-settings";
import { voiceFailureCopy } from "./voice-failure";
import { RecordingControls } from "./recording-controls";
import { chatDictationSession, useVoiceSession } from "./voice-session-store";

/**
 * Microphone beside Send, shown while no dictation runs. A model manager without a provider gets the configuration
 * page instead.
 */
export function ChatDictationButton({ disabled = false }: { disabled?: boolean }) {
  const ui = useAppTranslation();
  const supported = useAuiState((state) => state.thread.capabilities.dictation);
  // As in Onyx, a new recording waits for the running answer.
  const running = useAuiState((state) => state.thread.isRunning);
  // The microphone would record the answer being read aloud.
  const reading = useAutoPlayback().phase !== "idle";
  const manager = useApplicationSession().capabilities.includes("MODELS_MANAGE");
  const availability = useVoiceAvailability();
  if (supported)
    return (
      <ComposerPrimitive.Dictate asChild>
        <IconButton
          aria-label={ui("Nhập bằng giọng nói")}
          title={ui("Nhập bằng giọng nói")}
          disabled={disabled || running || reading}
          onClick={chatAutoPlayback.markManualDictation}
        >
          <Mic />
        </IconButton>
      </ComposerPrimitive.Dictate>
    );
  if (manager && availability.data?.sttAvailable === false)
    return (
      <IconButton
        asChild
        aria-label={ui("Cấu hình nhập bằng giọng nói")}
        title={ui("Chưa có nhà cung cấp nhận dạng giọng nói. Mở trang cấu hình Giọng nói.")}
      >
        <Link to="/admin/ai-providers" search={{ tab: "voice" }}>
          <Mic />
        </Link>
      </IconButton>
    );
  return null;
}

/**
 * Recording controls in the composer toolbar while dictation runs, in place of the model picker and Send: level
 * meter, mute and stop.
 */
export function ChatDictationControls() {
  const ui = useAppTranslation();
  const aui = useAui();
  const voice = useVoiceSession();
  const statusText =
    voice.phase === "finishing"
      ? ui("Đang hoàn tất văn bản…")
      : voice.phase !== "running"
        ? ui("Đang bật micro…")
        : voice.muted
          ? ui("Micro đang tắt")
          : ui("Đang nghe…");
  return (
    <RecordingControls session={chatDictationSession} onStop={() => aui.composer.stopDictation()}>
      <span role="status" className="sr-only">
        {statusText}
      </span>
    </RecordingControls>
  );
}

/** Dictation and read-aloud failures above the composer, with static copy and a dismiss action. */
export function ChatVoiceFailure() {
  const ui = useAppTranslation();
  const { failure } = useVoiceSession();
  if (!failure) return null;
  return (
    <div
      role="alert"
      className="mb-2 flex items-center gap-2 rounded-xl bg-status-danger-surface py-1 pr-1 pl-3 text-sm text-status-danger-content"
    >
      <p className="mr-auto">{ui(voiceFailureCopy(failure))}</p>
      <IconButton size="sm" aria-label={ui("Đóng")} onClick={chatDictationSession.dismissFailure}>
        <X />
      </IconButton>
    </div>
  );
}

/**
 * Auto-Send: once a stopped dictation has written its final text, the draft is sent as if Send were pressed. The
 * composer says whether its attachments still block sending.
 */
export function ChatDictationAutoSend({ filesBlocked }: { filesBlocked: boolean }) {
  const aui = useAui();
  const autoSend = useVoiceSettings().data?.autoSend === true;
  const { lastEnd } = useVoiceSession();
  const dictating = useAuiState((state) => state.composer.dictation != null);
  const sendable = useAuiState(
    (state) =>
      !state.thread.isRunning && !state.thread.isDisabled && state.composer.text.trim() !== "",
  );
  const handled = useRef(lastEnd?.sequence ?? 0);
  useEffect(() => {
    if (dictating || !lastEnd || lastEnd.sequence === handled.current) return;
    handled.current = lastEnd.sequence;
    if (autoSend && lastEnd.reason === "stopped" && sendable && !filesBlocked) aui.composer.send();
  }, [aui, autoSend, dictating, filesBlocked, lastEnd, sendable]);
  return null;
}
