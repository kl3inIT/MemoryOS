import { useCallback, useState } from "react";

/**
 * A refresh the person asked for. Polled views refetch on their own every few seconds, and a
 * control bound to the query's fetching flag would then blink busy and refuse clicks between
 * polls, so a refresh control reports work only for the refresh its own press started.
 */
export function useManualRefresh(refresh: () => Promise<unknown>) {
  const [pending, setPending] = useState(false);
  return {
    pending,
    refresh: useCallback(() => {
      setPending(true);
      // A failed refresh reports itself through the view it refreshed, not through this control.
      void refresh()
        .catch(() => undefined)
        .finally(() => setPending(false));
    }, [refresh]),
  };
}
