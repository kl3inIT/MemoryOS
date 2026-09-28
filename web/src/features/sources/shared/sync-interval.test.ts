import { describe, expect, it } from "vitest";
import {
  MAX_SYNC_INTERVAL_MINUTES,
  formatSyncInterval,
  maxSyncIntervalValue,
  splitSyncInterval,
  syncIntervalMinutes,
  syncIntervalUnitName,
} from "./sync-interval";

describe("sync interval", () => {
  it("expresses saved minutes in the largest whole unit", () => {
    expect(splitSyncInterval(5)).toEqual({ value: 5, unit: "minute" });
    expect(splitSyncInterval(90)).toEqual({ value: 90, unit: "minute" });
    expect(splitSyncInterval(120)).toEqual({ value: 2, unit: "hour" });
    expect(splitSyncInterval(2_880)).toEqual({ value: 2, unit: "day" });
    expect(splitSyncInterval(10_080)).toEqual({ value: 1, unit: "week" });
    expect(splitSyncInterval(MAX_SYNC_INTERVAL_MINUTES).unit).toBe("minute");
  });

  it("accepts only whole values within the API limit", () => {
    expect(syncIntervalMinutes("3", "hour")).toBe(180);
    expect(syncIntervalMinutes("2", "week")).toBe(20_160);
    for (const value of ["", "0", "-1", "1.5", "1e3", " 2"])
      expect(syncIntervalMinutes(value, "day")).toBeNull();
    const weeks = maxSyncIntervalValue("week");
    expect(syncIntervalMinutes(String(weeks), "week")).toBeLessThanOrEqual(
      MAX_SYNC_INTERVAL_MINUTES,
    );
    expect(syncIntervalMinutes(String(weeks + 1), "week")).toBeNull();
    expect(syncIntervalMinutes(String(MAX_SYNC_INTERVAL_MINUTES), "minute")).toBe(
      MAX_SYNC_INTERVAL_MINUTES,
    );
  });

  it("names the interval in the interface language", () => {
    expect(formatSyncInterval(15)).toBe("15 minutes");
    expect(formatSyncInterval(60)).toBe("1 hour");
    expect(formatSyncInterval(4_320)).toBe("3 days");
    expect(syncIntervalUnitName("hour", 1)).toBe("hour");
    expect(syncIntervalUnitName("hour", 2)).toBe("hours");
  });
});
