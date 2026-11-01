import { useCallback, useSyncExternalStore } from "react";

const MOBILE_QUERY = "(max-width: 767px)";
const WIDE_QUERY = "(min-width: 1024px)";

function useMediaQuery(query: string) {
  const subscribe = useCallback(
    (onChange: () => void) => {
      const list = window.matchMedia(query);
      list.addEventListener("change", onChange);
      return () => list.removeEventListener("change", onChange);
    },
    [query],
  );
  return useSyncExternalStore(subscribe, () => window.matchMedia(query).matches);
}

/** Whether the viewport is below the `md` breakpoint, read synchronously so the first render already knows. */
export function useIsMobile() {
  return useMediaQuery(MOBILE_QUERY);
}

/** Whether the viewport reaches the `lg` breakpoint, where a page has room for a side panel. */
export function useIsWide() {
  return useMediaQuery(WIDE_QUERY);
}
