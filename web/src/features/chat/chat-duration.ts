import { useEffect, useState } from "react";
import { uiLocale } from "@/i18n/format";

/** Spoken duration for activity headers, e.g. "14 giây" / "14 seconds". */
export function spokenDuration(ms: number) {
  const seconds = Math.max(1, Math.round(ms / 1000));
  const format = (value: number, unit: "second" | "minute") =>
    new Intl.NumberFormat(uiLocale(), { style: "unit", unit, unitDisplay: "long" }).format(value);
  if (seconds < 60) return format(seconds, "second");
  const rest = seconds % 60;
  return [format(Math.floor(seconds / 60), "minute"), rest ? format(rest, "second") : ""]
    .filter(Boolean)
    .join(" ");
}

/**
 * Milliseconds since the message started, while it runs. Research turns last minutes, so the timeline says how long
 * it has been working; a finished turn has no reliable end timestamp in the browser, so it returns null.
 */
export function useElapsed(startedAt: unknown, running: boolean) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!running) return undefined;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [running]);
  if (!running || typeof startedAt !== "string") return null;
  const started = new Date(startedAt).getTime();
  if (Number.isNaN(started)) return null;
  return Math.max(0, now - started);
}
