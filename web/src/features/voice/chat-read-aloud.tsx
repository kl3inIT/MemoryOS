import { ActionBarPrimitive, useAuiState } from "@assistant-ui/react";
import { LoaderCircle, Square, Volume2 } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";

/** Read-aloud on an answer: read, loading (still stoppable) and stop, as in Onyx. */
export function ChatReadAloudButton() {
  const ui = useAppTranslation();
  const supported = useAuiState((state) => state.thread.capabilities.speech);
  const status = useAuiState((state) => state.message.speech?.status.type);
  if (!supported) return null;
  if (status === undefined)
    return (
      <ActionBarPrimitive.Speak asChild>
        <IconButton
          aria-label={ui("Đọc thành tiếng")}
          title={ui("Đọc thành tiếng")}
          prominence="internal"
          size="sm"
        >
          <Volume2 />
        </IconButton>
      </ActionBarPrimitive.Speak>
    );
  const loading = status === "starting";
  return (
    <ActionBarPrimitive.StopSpeaking asChild>
      <IconButton
        aria-label={ui("Dừng đọc")}
        aria-busy={loading || undefined}
        title={loading ? ui("Đang tải âm thanh…") : ui("Dừng đọc")}
        prominence="internal"
        size="sm"
      >
        {loading ? (
          <LoaderCircle className="animate-spin motion-reduce:animate-none" />
        ) : (
          <Square />
        )}
      </IconButton>
    </ActionBarPrimitive.StopSpeaking>
  );
}
