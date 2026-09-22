import { useSyncExternalStore } from "react";
import type { QueryClient } from "@tanstack/react-query";
import { MeetingRecorder } from "./meeting-recorder";
import type { MeetingTrack, StreamedUtterance } from "./meeting-socket";
import { issueMeetingTicket, meetingKey, meetingsKey, type MeetingDetail } from "./meetings-api";

/**
 * The one meeting being recorded in this browser tab. It lives outside any page, so moving to Chat or the library
 * keeps recording (Nojoin keeps capturing across in-app navigation), and the sidebar shows that it is live.
 */
type Active = { meetingId: string; recorder: MeetingRecorder };

let active: Active | undefined;
const listeners = new Set<() => void>();

function publish() {
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function activeMeeting() {
  return active;
}

export function useActiveMeeting() {
  return useSyncExternalStore(subscribe, () => active);
}

/** Starts recording a meeting's tracks; a meeting already recording elsewhere in this tab is stopped first. */
export async function startRecording(
  meetingId: string,
  sources: { track: MeetingTrack; stream: MediaStream; offsetMs: number }[],
  cache: QueryClient,
) {
  if (active && active.meetingId !== meetingId) await stopRecording();
  if (active) return active.recorder;
  const recorder = new MeetingRecorder({
    meetingId,
    issueTicket: (track) => issueMeetingTicket(meetingId, track),
    onUtterance: (utterance) => appendUtterance(cache, meetingId, utterance),
  });
  active = { meetingId, recorder };
  const unsubscribe = recorder.subscribe(() => {
    const phase = recorder.getSnapshot().phase;
    if (phase === "stopped" || phase === "failed") {
      unsubscribe();
      if (active?.recorder === recorder) active = undefined;
      publish();
    }
  });
  window.addEventListener("beforeunload", warnBeforeUnload);
  publish();
  try {
    await recorder.start(sources);
  } catch (error) {
    recorder.dispose();
    for (const source of sources) for (const track of source.stream.getTracks()) track.stop();
    if (active?.recorder === recorder) active = undefined;
    window.removeEventListener("beforeunload", warnBeforeUnload);
    publish();
    throw error;
  }
  return recorder;
}

export async function stopRecording() {
  const current = active;
  if (!current) return;
  await current.recorder.stop();
  if (active === current) active = undefined;
  window.removeEventListener("beforeunload", warnBeforeUnload);
  publish();
}

function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (!active) return;
  event.preventDefault();
}

/** Adds a live utterance to the cached meeting, keeping the transcript in time order. */
function appendUtterance(cache: QueryClient, meetingId: string, utterance: StreamedUtterance) {
  cache.setQueryData<MeetingDetail>(meetingKey(meetingId), (meeting) => {
    if (!meeting || meeting.utterances.some((item) => item.id === utterance.id)) return meeting;
    const speakers = meeting.speakers.some(
      (item) => item.track === utterance.track && item.label === utterance.speaker,
    )
      ? meeting.speakers
      : [...meeting.speakers, { track: utterance.track, label: utterance.speaker, name: null }];
    const utterances = [...meeting.utterances, utterance].sort(
      (left, right) => left.startMs - right.startMs || left.endMs - right.endMs,
    );
    return { ...meeting, speakers, utterances };
  });
  void cache.invalidateQueries({ queryKey: meetingsKey, exact: true });
}
