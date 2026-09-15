import { useEffect, useRef } from "react";
import { useAui, useAuiState, type ThreadMessage } from "@assistant-ui/react";
import { Square, Volume2, VolumeX } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { chatAutoPlayback, useAutoPlayback } from "./use-chat-auto-playback";
import { useVoiceAvailability } from "./use-voice-availability";
import { useVoiceSettings } from "./use-voice-settings";

function lastAnswer(messages: readonly ThreadMessage[]) {
  const last = messages.at(-1);
  return last?.role === "assistant" ? last : undefined;
}

function answerText(message: ThreadMessage | undefined) {
  if (!message) return "";
  return message.content
    .map((part) => (part.type === "text" ? part.text : ""))
    .filter(Boolean)
    .join("\n\n");
}

/**
 * Auto-Playback for the conversation: an answer that starts streaming after this view mounted is read aloud as it grows.
 * History and resumed answers are not read; stopping the answer or leaving the conversation stops the reading.
 */
export function ChatAutoPlayback() {
  const settings = useVoiceSettings();
  const available = useVoiceAvailability().data?.ttsAvailable === true;
  const enabled = available && settings.data?.autoPlayback === true;
  const speed = settings.data?.playbackSpeed ?? 1;
  const threadId = useAuiState((state) => state.threadListItem.id);
  const running = useAuiState((state) => state.thread.isRunning);
  const answerId = useAuiState((state) => lastAnswer(state.thread.messages)?.id);
  const text = useAuiState((state) => answerText(lastAnswer(state.thread.messages)));
  const cancelled = useAuiState((state) => {
    const answer = lastAnswer(state.thread.messages);
    return (
      (answer?.status?.type === "incomplete" && answer.status.reason === "cancelled") ||
      answer?.metadata.custom.serverStatus === "CANCELED"
    );
  });
  const wasRunning = useRef(running);
  const reading = useRef(false);

  useEffect(() => {
    if (running && !wasRunning.current && enabled) {
      reading.current = true;
      chatAutoPlayback.begin(answerId, speed);
    }
    wasRunning.current = running;
    if (!reading.current) return;
    if (!enabled) {
      reading.current = false;
      chatAutoPlayback.stop();
    } else if (running) {
      chatAutoPlayback.append(answerId, text);
    } else {
      reading.current = false;
      if (cancelled) chatAutoPlayback.stop();
      else chatAutoPlayback.complete(answerId, text);
    }
  }, [answerId, cancelled, enabled, running, speed, text]);

  useEffect(() => {
    const active = reading;
    return () => {
      if (!active.current) return;
      active.current = false;
      chatAutoPlayback.stop();
    };
  }, [threadId]);

  return null;
}

/** Shown above the composer while an answer is read automatically: status, mute and stop. */
export function ChatSpeakingIndicator() {
  const ui = useAppTranslation();
  const { phase, muted } = useAutoPlayback();
  if (phase === "idle") return null;
  const speaking = phase === "speaking";
  return (
    <div
      role="group"
      aria-label={ui("Đọc tự động")}
      className="mb-2 flex items-center gap-2 rounded-xl bg-surface-sunken py-1 pr-1 pl-3 text-sm text-content-secondary"
    >
      <span aria-hidden="true" className="flex h-4 items-center gap-[3px]">
        {[0, 1, 2, 3].map((bar) => (
          <span
            key={bar}
            className={cn(
              "w-[3px] rounded-full bg-content-secondary",
              speaking && !muted ? "h-4 animate-pulse motion-reduce:animate-none" : "h-1.5",
            )}
            style={{ animationDelay: `${bar * 150}ms` }}
          />
        ))}
      </span>
      <span role="status" className="mr-auto">
        {speaking ? ui("MemoryOS đang đọc câu trả lời") : ui("Đang chuẩn bị giọng đọc…")}
      </span>
      <IconButton
        size="sm"
        aria-label={muted ? ui("Bật tiếng") : ui("Tắt tiếng")}
        aria-pressed={muted}
        disabled={!speaking}
        onClick={() => chatAutoPlayback.setMuted(!muted)}
      >
        {muted ? <VolumeX /> : <Volume2 />}
      </IconButton>
      <IconButton
        size="sm"
        prominence="secondary"
        aria-label={ui("Dừng đọc")}
        onClick={chatAutoPlayback.stop}
      >
        <Square />
      </IconButton>
    </div>
  );
}

/**
 * Onyx auto-listen: after an automatic reading plays to its end, the microphone opens again, but only when the member
 * used the microphone in the last five minutes, the composer is free and Auto-Playback is still on.
 */
export function ChatAutoListen() {
  const aui = useAui();
  const settings = useVoiceSettings();
  const supported = useAuiState((state) => state.thread.capabilities.dictation);
  const free = useAuiState((state) => !state.thread.isRunning && state.composer.dictation == null);
  const { lastEnd } = useAutoPlayback();
  const enabled = supported && settings.data?.autoPlayback === true;
  const latest = useRef({ enabled, free });
  const handled = useRef(lastEnd?.sequence ?? 0);

  useEffect(() => {
    latest.current = { enabled, free };
  });

  useEffect(() => {
    if (!lastEnd || lastEnd.sequence === handled.current) return;
    handled.current = lastEnd.sequence;
    if (lastEnd.reason !== "finished" || !chatAutoPlayback.listensAfterSpeech()) return;
    const timer = window.setTimeout(() => {
      if (latest.current.enabled && latest.current.free) aui.composer.startDictation();
    }, 400);
    return () => window.clearTimeout(timer);
  }, [aui, lastEnd]);

  return null;
}
