import { ActionBarPrimitive, useAuiState } from "@assistant-ui/react";
import { TooltipIconButton } from "@/components/composites/tooltip-icon-button";
import { Square, Volume2 } from "lucide-react";
import { Spinner } from "@/components/ui/spinner";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useAutoPlayback } from "./use-chat-auto-playback";

/** Read-aloud on an answer: read, loading (still stoppable) and stop, as in Onyx. */
export function ChatReadAloudButton() {
  const ui = useAppTranslation();
  const supported = useAuiState((state) => state.thread.capabilities.speech);
  const status = useAuiState((state) => state.message.speech?.status.type);
  const id = useAuiState((state) => state.message.id);
  const automatic = useAutoPlayback();
  // As in Onyx, the answer being read automatically has no read-aloud button.
  if (!supported || (automatic.phase !== "idle" && automatic.messageId === id)) return null;
  if (status === undefined)
    return (
      <ActionBarPrimitive.Speak asChild>
        <TooltipIconButton aria-label={ui("Đọc thành tiếng")} prominence="internal" size="sm">
          <Volume2 />
        </TooltipIconButton>
      </ActionBarPrimitive.Speak>
    );
  const loading = status === "starting";
  return (
    <ActionBarPrimitive.StopSpeaking asChild>
      <TooltipIconButton
        aria-label={ui("Dừng đọc")}
        aria-busy={loading || undefined}
        prominence="internal"
        size="sm"
      >
        {loading ? <Spinner aria-hidden="true" /> : <Square />}
      </TooltipIconButton>
    </ActionBarPrimitive.StopSpeaking>
  );
}
