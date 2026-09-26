import { QueryClient } from "@tanstack/react-query";
import { renderHook, act } from "@testing-library/react";
import { assert, describe, expect, it, vi } from "vitest";

type Snapshot = { phase: string; error?: string };

const recorders: FakeRecorder[] = [];

class FakeRecorder {
  snapshot: Snapshot = { phase: "idle" };
  private readonly listeners = new Set<() => void>();
  constructor() {
    recorders.push(this);
  }
  subscribe(listener: () => void) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
  getSnapshot() {
    return this.snapshot;
  }
  async start() {
    this.update({ phase: "recording" });
  }
  async stop() {
    this.update({ phase: "stopped" });
  }
  dispose() {}
  update(next: Snapshot) {
    this.snapshot = next;
    for (const listener of this.listeners) listener();
  }
}

vi.mock("./meeting-recorder", () => ({ MeetingRecorder: FakeRecorder }));
vi.mock("./meetings-api", () => ({
  finishMeeting: vi.fn(async (id: string) => ({ id })),
  issueMeetingTicket: vi.fn(),
  meetingQueryKey: (id: string) => ["meetings", id],
  invalidateMeetingList: vi.fn(),
}));

const { endMeeting, startRecording, useActiveMeeting, useRecordingFailure } =
  await import("./meeting-session");

describe("meeting session", () => {
  it("keeps why a recording failed after it stops, until the next recording starts", async () => {
    const cache = new QueryClient();
    const active = renderHook(() => useActiveMeeting());
    const failure = renderHook(() => useRecordingFailure());

    await act(() => startRecording("meeting-1", [], cache));
    const [recorder] = recorders;
    assert.isDefined(recorder);
    act(() => recorder.update({ phase: "failed", error: "MEETING_BUSY" }));

    expect(active.result.current).toBeUndefined();
    expect(failure.result.current).toEqual({ meetingId: "meeting-1", code: "MEETING_BUSY" });

    await act(() => startRecording("meeting-1", [], cache));
    expect(failure.result.current).toBeUndefined();
  });

  it("forgets the failure once that meeting ends", async () => {
    const cache = new QueryClient();
    const failure = renderHook(() => useRecordingFailure());

    await act(() => startRecording("meeting-2", [], cache));
    act(() => recorders.at(-1)!.update({ phase: "failed", error: "MEETING_TOO_LONG" }));
    expect(failure.result.current).toEqual({ meetingId: "meeting-2", code: "MEETING_TOO_LONG" });

    await act(() => endMeeting("meeting-2", cache));
    expect(failure.result.current).toBeUndefined();
  });
});
