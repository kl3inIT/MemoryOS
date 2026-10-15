import { useAuiState, useMessageTiming } from "@assistant-ui/react";
import { useState } from "react";
import { formatUiDate, uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";

/**
 * Answer time from the saved message timestamp, revealed on hover or focus. An answer streamed in this session also
 * shows assistant-ui's own timing (MEM-267): how long until the first token, the whole answer, and its speed, which
 * helps compare models; a reloaded answer has only its time.
 */
export function ChatMessageTiming() {
  const ui = useAppTranslation();
  const createdAt = useAuiState((state) => state.message.metadata.custom.createdAt);
  const running = useAuiState((state) => state.message.status?.type === "running");
  const timing = useMessageTiming();
  // Read once when the message mounts, so a render stays pure.
  const [now] = useState(() => new Date());
  if (running || typeof createdAt !== "string") return null;
  const started = new Date(createdAt);
  if (Number.isNaN(started.getTime())) return null;
  const today = started.toDateString() === now.toDateString();
  const seconds = new Intl.NumberFormat(uiLocale(), { maximumFractionDigits: 1 });
  const stats = [
    timing?.firstTokenTime !== undefined
      ? ui("Bắt đầu trả lời sau {{seconds}} giây", {
          seconds: seconds.format(timing.firstTokenTime / 1000),
        })
      : null,
    timing?.totalStreamTime !== undefined
      ? ui("Xong trong {{seconds}} giây", { seconds: seconds.format(timing.totalStreamTime / 1000) })
      : null,
    timing?.tokensPerSecond !== undefined
      ? ui("{{speed}} token/giây", { speed: Math.round(timing.tokensPerSecond) })
      : null,
  ].filter((stat) => stat !== null);
  const time = formatUiDate(started, {
    ...(today ? {} : { day: "numeric", month: "short" }),
    hour: "2-digit",
    minute: "2-digit",
  });
  return (
    <span
      data-slot="message-timing"
      title={formatUiDate(started, { dateStyle: "full", timeStyle: "medium" })}
      className="px-1 text-xs text-content-muted opacity-0 transition-opacity group-focus-within/message:opacity-100 group-hover/message:opacity-100"
    >
      {[time, ...stats].join(" · ")}
    </span>
  );
}
