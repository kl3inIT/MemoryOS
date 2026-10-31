import { useAuiState } from "@assistant-ui/react";
import { ContextDisplay } from "@/components/assistant-ui/elements/context-display";
import { useAppTranslation } from "@/i18n/use-app-translation";

type ContextUse = { tokens: number; limit: number };

/**
 * How full the model's context was on the latest answer (MEM-267), as assistant-ui's Context display shows it, so a
 * long conversation can move to a new one before a turn fails on the limit. Nothing shows until an answer reports it.
 */
export function ChatContextUsage() {
  const ui = useAppTranslation();
  const context = useAuiState(
    (state) =>
      state.thread.messages.findLast((message) => message.role === "assistant")?.metadata.custom
        .context as ContextUse | undefined,
  );
  return (
    <ContextDisplay
      tokens={context?.tokens}
      limit={context?.limit}
      label={ui("Mức dùng context")}
      summary={(percent) => ui("Đã dùng {{percent}}% context", { percent })}
    />
  );
}
