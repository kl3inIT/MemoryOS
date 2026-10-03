import { i18n } from "./index";

export function uiLocale() {
  return i18n.resolvedLanguage === "en" ? "en-US" : "vi-VN";
}

export function formatUiDate(value: string | Date, options?: Intl.DateTimeFormatOptions) {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString(uiLocale(), options);
}

/** A day, as every list and detail writes one: "3 thg 10, 2026". */
export function formatUiDay(value: string | Date) {
  return formatUiDate(value, { dateStyle: "medium" });
}

/** A day and its time, as every list and detail writes one: "09:19 3 thg 10, 2026". */
export function formatUiMoment(value: string | Date) {
  return formatUiDate(value, { dateStyle: "medium", timeStyle: "short" });
}
