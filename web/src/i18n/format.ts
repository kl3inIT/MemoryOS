import { i18n } from "./index";

export function uiLocale() {
  return i18n.resolvedLanguage === "en" ? "en-US" : "vi-VN";
}

export function formatUiDate(value: string | Date, options?: Intl.DateTimeFormatOptions) {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString(uiLocale(), options);
}
