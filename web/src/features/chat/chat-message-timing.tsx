import { useAppTranslation } from "@/i18n/use-app-translation";
import { useAuiState } from "@assistant-ui/react";
import { formatUiDate } from "@/i18n/format";

/** Answer time and duration from saved message timestamps, revealed on hover or focus. */
export function ChatMessageTiming() {
  const ui = useAppTranslation();
  const createdAt = useAuiState((state) => state.message.metadata.custom.createdAt);
  const finishedAt = useAuiState((state) => state.message.metadata.custom.finishedAt);
  const running = useAuiState((state) => state.message.status?.type === "running");
  if (running || typeof createdAt !== "string") return null;
  const started = new Date(createdAt);
  if (Number.isNaN(started.getTime())) return null;
  const finished = typeof finishedAt === "string" ? new Date(finishedAt).getTime() : NaN;
  const seconds = Math.round((finished - started.getTime()) / 1000);
  const today = started.toDateString() === new Date().toDateString();
  const time = formatUiDate(started, {
    ...(today ? {} : { day: "numeric", month: "short" }),
    hour: "2-digit",
    minute: "2-digit",
  });
  const duration =
    Number.isNaN(seconds) || seconds < 0
      ? undefined
      : seconds < 60
        ? ui("{{v1}} giây", { v1: String(seconds) })
        : ui("{{v1}} phút {{v2}} giây", {
            v1: String(Math.floor(seconds / 60)),
            v2: String(seconds % 60),
          });
  return (
    <span
      data-slot="message-timing"
      title={formatUiDate(started, { dateStyle: "full", timeStyle: "medium" })}
      className="px-1 text-xs text-content-muted opacity-0 transition-opacity group-focus-within/message:opacity-100 group-hover/message:opacity-100"
    >
      {duration ? ui("{{v1}} · {{v2}}", { v1: time, v2: duration }) : time}
    </span>
  );
}
