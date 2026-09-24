import { uiLocale } from "@/i18n/format";

/** The longest automatic interval the schedule API accepts, in minutes. */
export const MAX_SYNC_INTERVAL_MINUTES = 2_147_483_647;

export const syncIntervalUnits = ["minute", "hour", "day", "week"] as const;
export type SyncIntervalUnit = (typeof syncIntervalUnits)[number];

const minutesPerUnit: Record<SyncIntervalUnit, number> = {
  minute: 1,
  hour: 60,
  day: 1_440,
  week: 10_080,
};

/** Expresses saved minutes in the largest unit that divides them evenly. */
export function splitSyncInterval(minutes: number): { value: number; unit: SyncIntervalUnit } {
  const unit =
    [...syncIntervalUnits].reverse().find((entry) => minutes % minutesPerUnit[entry] === 0) ??
    "minute";
  return { value: minutes / minutesPerUnit[unit], unit };
}

/** The largest whole number of `unit` that stays within the API limit. */
export function maxSyncIntervalValue(unit: SyncIntervalUnit) {
  return Math.floor(MAX_SYNC_INTERVAL_MINUTES / minutesPerUnit[unit]);
}

/** Minutes for a typed value, or null unless it is a whole number the API accepts. */
export function syncIntervalMinutes(value: string, unit: SyncIntervalUnit) {
  if (!/^\d+$/.test(value)) return null;
  const count = Number(value);
  return count >= 1 && count <= maxSyncIntervalValue(unit) ? count * minutesPerUnit[unit] : null;
}

function unitFormat(unit: SyncIntervalUnit) {
  return new Intl.NumberFormat(uiLocale(), { style: "unit", unit, unitDisplay: "long" });
}

/** "15 minutes", "2 hours", "1 week" in the interface language. */
export function formatSyncInterval(minutes: number) {
  const { value, unit } = splitSyncInterval(minutes);
  return unitFormat(unit).format(value);
}

/** The unit's name as it reads after `count`, e.g. "minutes" after 15. */
export function syncIntervalUnitName(unit: SyncIntervalUnit, count: number) {
  return unitFormat(unit)
    .formatToParts(count)
    .filter((part) => part.type === "unit")
    .map((part) => part.value)
    .join("");
}
