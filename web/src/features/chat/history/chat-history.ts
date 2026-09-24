import { appText, type AppCopy } from "@/i18n/app-text";
import type { ChatHistoryEntry } from "@/lib/hey-api/types.gen";

export type HistoryPeriod = "1d" | "7d" | "30d" | "90d";

export const periodLabels: Record<HistoryPeriod, AppCopy> = {
  "1d": "Last 24 hours",
  "7d": "Last 7 days",
  "30d": "Last 30 days",
  "90d": "Last 90 days",
};

const days: Record<HistoryPeriod, number> = { "1d": 1, "7d": 7, "30d": 30, "90d": 90 };

export function periodStart(period: HistoryPeriod, now = new Date()): string {
  return new Date(now.getTime() - days[period] * 24 * 60 * 60 * 1000).toISOString();
}

export const feedbackLabels: Record<ChatHistoryEntry["feedback"], AppCopy> = {
  POSITIVE: "Marked good",
  NEGATIVE: "Marked bad",
  MIXED: "Mixed",
  NONE: "Not rated",
};

export const feedbackTones: Record<
  ChatHistoryEntry["feedback"],
  "success" | "danger" | "warning" | "neutral"
> = {
  POSITIVE: "success",
  NEGATIVE: "danger",
  MIXED: "warning",
  NONE: "neutral",
};

/** Who asked, once the Tenant decides whether that may be shown. */
export function askerName(entry: Pick<ChatHistoryEntry, "person" | "email">): AppCopy | string {
  return entry.person ?? entry.email ?? appText("Hidden");
}
