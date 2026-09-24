import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { invalidateMeetingList, meetingKey, meetingListKey, transcribersKey } from "./meetings-api";

describe("invalidateMeetingList", () => {
  it("marks the actor-scoped list stale without touching a meeting or the transcribers", async () => {
    const cache = new QueryClient();
    const list = [...meetingListKey, "actor-1", 3];
    cache.setQueryData(list, []);
    cache.setQueryData(meetingKey("meeting-1"), { id: "meeting-1" });
    cache.setQueryData(transcribersKey, []);

    await invalidateMeetingList(cache);

    expect(cache.getQueryState(list)?.isInvalidated).toBe(true);
    expect(cache.getQueryState(meetingKey("meeting-1"))?.isInvalidated).toBe(false);
    expect(cache.getQueryState(transcribersKey)?.isInvalidated).toBe(false);
  });
});
