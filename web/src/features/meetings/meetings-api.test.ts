import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import {
  invalidateMeetingList,
  meetingKey,
  meetingListKey,
  patchMeeting,
  transcribersKey,
  withAddedMinutesItem,
  withMinutesItem,
  withoutMinutesItem,
  withSpeaker,
  type MeetingDetail,
  type MeetingMinutesItem,
} from "./meetings-api";

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

function item(id: string, patch: Partial<MeetingMinutesItem> = {}): MeetingMinutesItem {
  return {
    id,
    text: `Việc ${id}`,
    owner: null,
    due: null,
    quote: null,
    sourceUtteranceId: null,
    done: false,
    edited: false,
    ...patch,
  };
}

function meeting(): MeetingDetail {
  return {
    id: "meeting-1",
    title: "Giao ban tuần",
    kind: "IN_PERSON",
    language: "vi",
    participants: [],
    terms: [],
    notes: "",
    status: "ENDED",
    provider: null,
    diarized: true,
    createdAt: "2026-09-24T09:00:00Z",
    endedAt: "2026-09-24T10:00:00Z",
    revision: 4,
    speakers: [
      { track: "MIC", label: "1", name: null },
      { track: "MIC", label: "2", name: "Chị Lan" },
    ],
    utterances: [
      {
        id: "u1",
        track: "MIC",
        speaker: "1",
        startMs: 0,
        endMs: 1000,
        text: "Chốt ngân sách.",
        confidence: 0.9,
        spans: [],
      },
    ],
    minutes: {
      status: "READY",
      failure: null,
      summary: "Cuộc họp chốt ngân sách.",
      kind: "Giao ban",
      generatedAt: "2026-09-24T10:01:00Z",
      decisions: [item("d1")],
      actions: [item("a1"), item("a2")],
      edited: false,
      topics: [item("t1")],
    },
    audio: { status: "NONE", failure: null, filename: null, sizeBytes: 0, provider: null },
    owned: true,
    readers: [],
    starred: [],
    bookmarks: [],
    correcting: false,
  };
}

describe("patching the cached meeting", () => {
  it("folds a change into a cached meeting and keeps the transcript it already had", () => {
    const cache = new QueryClient();
    const cached = meeting();
    cache.setQueryData(meetingKey(cached.id), cached);

    patchMeeting(cache, cached.id, (current) => ({
      ...current,
      notes: "Hỏi hạn mức",
      revision: 5,
    }));

    const patched = cache.getQueryData<MeetingDetail>(meetingKey(cached.id));
    expect(patched?.notes).toBe("Hỏi hạn mức");
    expect(patched?.revision).toBe(5);
    expect(patched?.utterances).toBe(cached.utterances);
  });

  it("leaves a meeting that is not cached for its next read", () => {
    const cache = new QueryClient();

    patchMeeting(cache, "meeting-2", (current) => ({ ...current, starred: ["u1"] }));

    expect(cache.getQueryData(meetingKey("meeting-2"))).toBeUndefined();
  });

  it("replaces one speaker by track and label", () => {
    const named = withSpeaker(meeting(), { track: "MIC", label: "1", name: "Anh Minh" });

    expect(named.speakers).toEqual([
      { track: "MIC", label: "1", name: "Anh Minh" },
      { track: "MIC", label: "2", name: "Chị Lan" },
    ]);
  });

  it("replaces a ticked item without calling the minutes the owner's", () => {
    const ticked = withMinutesItem(meeting(), item("a2", { done: true }));

    expect(ticked.minutes.actions.map((action) => action.done)).toEqual([false, true]);
    expect(ticked.minutes.edited).toBe(false);
  });

  it("marks the minutes the owner's once an item carries their words", () => {
    const rewritten = withMinutesItem(meeting(), item("d1", { text: "Chốt quý 4", edited: true }));

    expect(rewritten.minutes.decisions[0]?.text).toBe("Chốt quý 4");
    expect(rewritten.minutes.edited).toBe(true);
  });

  it("puts an item written in after the others of its kind", () => {
    const added = withAddedMinutesItem(meeting(), "ACTION", item("a3", { edited: true }));

    expect(added.minutes.actions.map((action) => action.id)).toEqual(["a1", "a2", "a3"]);
    expect(added.minutes.decisions.map((decision) => decision.id)).toEqual(["d1"]);
    expect(added.minutes.edited).toBe(true);
  });

  it("takes an item out and calls the minutes the owner's", () => {
    const removed = withoutMinutesItem(meeting(), "a1");

    expect(removed.minutes.actions.map((action) => action.id)).toEqual(["a2"]);
    expect(removed.minutes.topics.map((topic) => topic.id)).toEqual(["t1"]);
    expect(removed.minutes.edited).toBe(true);
  });
});
