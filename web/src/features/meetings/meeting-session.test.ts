import { QueryClient } from "@tanstack/react-query";
import { renderHook, act } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

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
  finishMeeting: vi.fn(),
  issueMeetingTicket: vi.fn(),
  meetingKey: (id: string) => ["meetings", id],
  invalidateMeetingList: vi.fn(),
}));

const { startRecording, useActiveMeeting, useRecordingFailure } = await import("./meeting-session");

describe("meeting session", () => {
  it("keeps why a recording failed after it stops, until the next recording starts", async () => {
    const cache = new QueryClient();
    const active = renderHook(() => useActiveMeeting());
    const failure = renderHook(() => useRecordingFailure());

    await act(() => startRecording("meeting-1", [], cache));
    act(() => recorders[0].update({ phase: "failed", error: "MEETING_BUSY" }));

    expect(active.result.current).toBeUndefined();
    expect(failure.result.current).toEqual({ meetingId: "meeting-1", code: "MEETING_BUSY" });

    await act(() => startRecording("meeting-1", [], cache));
    expect(failure.result.current).toBeUndefined();
  });
});
