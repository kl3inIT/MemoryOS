import { i18n } from "./index";

export function uiLocale() {
  return i18n.resolvedLanguage === "en" ? "en-US" : "vi-VN";
}

const formatters = new Map<string, Intl.DateTimeFormat>();
const fieldOptions = [
  "dateStyle",
  "timeStyle",
  "weekday",
  "era",
  "year",
  "month",
  "day",
  "dayPeriod",
  "hour",
  "minute",
  "second",
  "fractionalSecondDigits",
] as const;

/** `toLocaleString` builds a formatter on every call, which a list of rows pays for on each render. */
function formatterFor(locale: string, options?: Intl.DateTimeFormatOptions) {
  const key = locale + JSON.stringify(options ?? {});
  let formatter = formatters.get(key);
  if (!formatter) {
    // Like `toLocaleString`, a date and its time when the options name no field of either.
    const named = fieldOptions.some((field) => options?.[field] !== undefined);
    formatter = new Intl.DateTimeFormat(
      locale,
      named
        ? options
        : {
            year: "numeric",
            month: "numeric",
            day: "numeric",
            hour: "numeric",
            minute: "numeric",
            second: "numeric",
            ...options,
          },
    );
    formatters.set(key, formatter);
  }
  return formatter;
}

export function formatUiDate(value: string | Date, options?: Intl.DateTimeFormatOptions) {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : formatterFor(uiLocale(), options).format(date);
}

/** A day, as every list and detail writes one: "3 thg 10, 2026". */
export function formatUiDay(value: string | Date) {
  return formatUiDate(value, { dateStyle: "medium" });
}

/** A day and its time, as every list and detail writes one: "09:19 3 thg 10, 2026". */
export function formatUiMoment(value: string | Date) {
  return formatUiDate(value, { dateStyle: "medium", timeStyle: "short" });
}

const relativeUnits: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ["day", 86_400],
  ["hour", 3_600],
  ["minute", 60],
];
const relativeLimitSeconds = 7 * 86_400;
/** Tolerates a server clock slightly ahead of the browser's. */
const clockSkewSeconds = 60;

/** "6 minutes ago" for times within the last week; null for older, future or invalid times. */
export function formatUiRelative(value: string, now = Date.now()) {
  const seconds = Math.round((now - new Date(value).getTime()) / 1_000);
  if (!Number.isFinite(seconds) || seconds < -clockSkewSeconds || seconds >= relativeLimitSeconds)
    return null;
  const elapsed = Math.max(seconds, 0);
  const [unit, size] = relativeUnits.find(([, size]) => elapsed >= size) ?? ["second", 1];
  const amount = Math.floor(elapsed / size);
  return new Intl.RelativeTimeFormat(uiLocale(), { numeric: "auto" }).format(
    amount === 0 ? 0 : -amount,
    unit,
  );
}
