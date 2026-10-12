import { useSyncExternalStore } from "react";

/** From here up the panel stands beside the page; below it opens over the page. */
const WIDE_QUERY = "(min-width: 1280px)";

function subscribeWidth(notify: () => void) {
  const query = window.matchMedia(WIDE_QUERY);
  query.addEventListener("change", notify);
  return () => query.removeEventListener("change", notify);
}

/** Whether the page is wide enough to keep the meeting's panel beside it. */
export function useWidePage() {
  return useSyncExternalStore(
    subscribeWidth,
    () => window.matchMedia(WIDE_QUERY).matches,
    () => false,
  );
}
