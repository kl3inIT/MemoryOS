import type { ReactNode } from "react";
import { Mic, MicOff, Square } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { LEVEL_HISTORY, useVoiceSession, type VoiceSessionStore } from "./voice-session-store";

/**
 * The controls of a running dictation, shown where the microphone and the field's own action were: level meter,
 * mute and stop. `children` is the surface's own status text for assistive technology.
 */
export function RecordingControls({
  session,
  size = "md",
  onStop,
  children,
}: {
  session: VoiceSessionStore;
  size?: "md" | "lg";
  onStop: () => void;
  children?: ReactNode;
}) {
  const ui = useAppTranslation();
  const voice = useVoiceSession(session);
  const running = voice.phase === "running";
  return (
    <div role="group" aria-label={ui("Ghi âm")} className="flex min-w-0 items-center gap-1">
      {children}
      <LevelMeter levels={running ? voice.levels : []} muted={voice.muted} />
      <IconButton
        type="button"
        size={size}
        aria-label={voice.muted ? ui("Bật micro") : ui("Tắt micro")}
        aria-pressed={voice.muted}
        disabled={!running}
        onClick={() => session.setMuted(!voice.muted)}
      >
        {voice.muted ? <MicOff /> : <Mic />}
      </IconButton>
      <IconButton
        type="button"
        size={size}
        aria-label={ui("Dừng ghi âm")}
        pending={voice.phase === "finishing"}
        disabled={!running}
        onClick={onStop}
      >
        <Square className="fill-current" />
      </IconButton>
    </div>
  );
}

function LevelMeter({ levels, muted }: { levels: readonly number[]; muted: boolean }) {
  return (
    <div aria-hidden="true" className="mr-1 flex h-7 shrink-0 items-center gap-0.75">
      {Array.from({ length: LEVEL_HISTORY }, (_, index) => {
        const level = muted ? 0 : (levels[levels.length - LEVEL_HISTORY + index] ?? 0);
        return (
          <span
            key={index}
            className={cn(
              "w-0.75 shrink-0 rounded-full transition-all duration-100 motion-reduce:transition-none",
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
