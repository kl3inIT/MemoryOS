import { useSyncExternalStore } from "react";
import type { MeetingRecorder, RecorderSnapshot } from "./meeting-recorder";

/** What a page shows while nothing is being recorded here. */
export const IDLE: RecorderSnapshot = { phase: "stopped", elapsedMs: 0, tracks: [], previews: {} };

const unsubscribed = () => () => undefined;

/**
 * One value from the recorder. The recorder publishes on every level reading, many times a second, so a
 * component subscribes only to what it shows and `select` returns a primitive or a reference the recorder
 * keeps between publications; anything built here would change on every publication and re-render each time.
 */
export function useRecorderValue<T>(
  recorder: MeetingRecorder | undefined,
  select: (snapshot: RecorderSnapshot) => T,
): T {
  return useSyncExternalStore(recorder?.subscribe ?? unsubscribed, () =>
    select(recorder?.getSnapshot() ?? IDLE),
  );
}
