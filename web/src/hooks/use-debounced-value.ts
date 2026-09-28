import { useEffect, useState } from "react";

/**
 * The value as it was once it stopped changing for `delayMs`. The first render returns the
 * initial value at once, so a debounced request starts from what the page already shows.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}
