import { useAuiState } from "@assistant-ui/react";
import { formatUiDate } from "@/i18n/format";

/** Answer time from the saved message timestamp, revealed on hover or focus. */
export function ChatMessageTiming() {
  const createdAt = useAuiState((state) => state.message.metadata.custom.createdAt);
  const running = useAuiState((state) => state.message.status?.type === "running");
  if (running || typeof createdAt !== "string") return null;
  const started = new Date(createdAt);
  if (Number.isNaN(started.getTime())) return null;
  const today = started.toDateString() === new Date().toDateString();
  return (
    <span
      data-slot="message-timing"
      title={formatUiDate(started, { dateStyle: "full", timeStyle: "medium" })}
      className="px-1 text-xs text-content-muted opacity-0 transition-opacity group-focus-within/message:opacity-100 group-hover/message:opacity-100"
    >
      {formatUiDate(started, {
        ...(today ? {} : { day: "numeric", month: "short" }),
        hour: "2-digit",
        minute: "2-digit",
      })}
    </span>
  );
}
