import { useEffect, useRef, useState } from "react";
import { ComposerPrimitive, useAui, useAuiState } from "@assistant-ui/react";
import { Link } from "@tanstack/react-router";
import { Check, Mic, MicOff, X } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { useFilesBlocked } from "@/features/chat/composer/use-files-blocked";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { chatAutoPlayback, useAutoPlayback } from "./use-chat-auto-playback";
import { useVoiceAvailability } from "./use-voice-availability";
import { useVoiceSettings } from "./use-voice-settings";
import { voiceFailureCopy } from "./voice-failure";
import { chatDictationSession, useVoiceSession } from "./voice-session-store";

const METER_BARS = 40;

/** Microphone beside Send. A model manager without a provider gets the configuration page instead. */
export function ChatDictationButton({ disabled = false }: { disabled?: boolean }) {
  const ui = useAppTranslation();
  const supported = useAuiState((state) => state.thread.capabilities.dictation);
  const dictating = useAuiState((state) => state.composer.dictation != null);
  // As in Onyx, a new recording waits for the running answer.
  const running = useAuiState((state) => state.thread.isRunning);
  // The microphone would record the answer being read aloud.
  const reading = useAutoPlayback().phase !== "idle";
  const manager = useApplicationSession().capabilities.includes("MODELS_MANAGE");
  const availability = useVoiceAvailability();
  if (dictating) return null;
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
        <Link to="/admin/voice">
          <Mic />
        </Link>
      </IconButton>
    );
  return null;
}

/** Recording strip under the draft while dictation runs: status, elapsed time, level meter, mute and stop. */
export function ChatDictationStrip() {
  const ui = useAppTranslation();
  const voice = useVoiceSession();
  const status = useAuiState((state) => state.composer.dictation?.status.type);
  if (!status) return null;
  const finishing = voice.phase === "finishing";
  const running = status === "running" && !finishing;
  return (
    <div
      role="group"
      aria-label={ui("Ghi âm")}
      className="flex items-center gap-2 rounded-xl border border-border-subtle bg-surface-raised py-1.5 pr-1.5 pl-3 shadow-xs"
    >
      <span
        aria-hidden="true"
        className={cn(
          "size-2 shrink-0 rounded-full",
          running && !voice.muted
            ? "animate-pulse bg-status-danger-content motion-reduce:animate-none"
            : "bg-content-muted",
        )}
      />
      <span
        role="status"
        className="flex shrink-0 items-center gap-2 font-secondary-body text-content-secondary"
      >
        {running ? (
          <>
            <span className="hidden sm:inline">
              {voice.muted ? ui("Micro đang tắt") : ui("Đang nghe…")}
            </span>
            <ElapsedTime since={voice.startedAt} />
          </>
        ) : finishing ? (
          ui("Đang hoàn tất văn bản…")
        ) : (
          ui("Đang bật micro…")
        )}
      </span>
      <LevelMeter levels={running ? voice.levels : []} muted={voice.muted} />
      <IconButton
        size="sm"
        aria-label={voice.muted ? ui("Bật micro") : ui("Tắt micro")}
        aria-pressed={voice.muted}
        disabled={!running}
        onClick={() => chatDictationSession.setMuted(!voice.muted)}
      >
        {voice.muted ? <MicOff /> : <Mic />}
      </IconButton>
      <ComposerPrimitive.StopDictation asChild>
        <IconButton
          size="sm"
          prominence="secondary"
          aria-label={ui("Dừng ghi âm")}
          pending={finishing}
          disabled={status !== "running"}
        >
          <Check />
        </IconButton>
      </ComposerPrimitive.StopDictation>
    </div>
  );
}

function ElapsedTime({ since }: { since: number | undefined }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, []);
  const seconds = since === undefined ? 0 : Math.max(0, Math.floor((now - since) / 1000));
  return (
    <time aria-hidden="true" className="tabular-nums">
      {clock(seconds)}
    </time>
  );
}

/** Minutes and seconds, as on a recorder; numerals need no translation. */
function clock(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

function LevelMeter({ levels, muted }: { levels: readonly number[]; muted: boolean }) {
  return (
    <div
      aria-hidden="true"
      className="flex h-7 min-w-0 flex-1 items-center justify-end gap-[3px] overflow-hidden"
    >
      {Array.from({ length: METER_BARS }, (_, index) => {
        const level = muted ? 0 : (levels[levels.length - METER_BARS + index] ?? 0);
        return (
          <span
            key={index}
            className={cn(
              "w-[3px] shrink-0 rounded-full transition-[height] duration-100 motion-reduce:transition-none",
              muted ? "bg-content-disabled" : "bg-content-secondary",
            )}
            // Speech RMS sits around 0.01–0.1; the square root keeps quiet speech visible.
            style={{ height: `${Math.max(12, Math.min(100, Math.sqrt(level) * 250))}%` }}
          />
        );
      })}
    </div>
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

/** Auto-Send: once a stopped dictation has written its final text, the draft is sent as if Send were pressed. */
export function ChatDictationAutoSend() {
  const aui = useAui();
  const autoSend = useVoiceSettings().data?.autoSend === true;
  const { lastEnd } = useVoiceSession();
  const dictating = useAuiState((state) => state.composer.dictation != null);
  const sendable = useAuiState(
    (state) =>
      !state.thread.isRunning && !state.thread.isDisabled && state.composer.text.trim() !== "",
  );
  const filesBlocked = useFilesBlocked();
  const handled = useRef(lastEnd?.sequence ?? 0);
  useEffect(() => {
    if (dictating || !lastEnd || lastEnd.sequence === handled.current) return;
    handled.current = lastEnd.sequence;
    if (autoSend && lastEnd.reason === "stopped" && sendable && !filesBlocked) aui.composer.send();
  }, [aui, autoSend, dictating, filesBlocked, lastEnd, sendable]);
  return null;
}
