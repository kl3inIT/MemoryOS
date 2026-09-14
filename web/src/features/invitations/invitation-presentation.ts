import { formatUiDate } from "@/i18n/format";

export function formatInvitationDate(value: string) {
  return formatUiDate(value, { dateStyle: "medium", timeStyle: "short" });
}
