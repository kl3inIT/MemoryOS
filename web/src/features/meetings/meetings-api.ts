import { sameOriginMutationHeaders } from "@/lib/api";
import {
  createMeeting,
  createMeetingTicket,
  deleteMeeting,
  endMeeting,
  getMeeting,
  listMeetings,
  markMeetingMinutesItem,
  nameMeetingSpeaker,
  rerunMeetingMinutes,
  updateMeetingNotes,
} from "@/lib/hey-api/sdk.gen";
import type {
  MeetingCreateRequest,
  MeetingDetail,
  MeetingMinutes,
  MeetingMinutesItem,
  MeetingSummary,
  MeetingUtterance,
} from "@/lib/hey-api/types.gen";
import type { MeetingTrack } from "./meeting-socket";

export type { MeetingDetail, MeetingMinutes, MeetingMinutesItem, MeetingSummary, MeetingUtterance };
export type MeetingKind = MeetingDetail["kind"];

export const meetingsKey = ["meetings"] as const;
export const meetingKey = (id: string) => [...meetingsKey, id] as const;

export async function loadMeetings(signal: AbortSignal): Promise<MeetingSummary[]> {
  const { data } = await listMeetings({ signal, throwOnError: true });
  return data;
}

export async function loadMeeting(id: string, signal: AbortSignal): Promise<MeetingDetail> {
  const { data } = await getMeeting({ path: { meetingId: id }, signal, throwOnError: true });
  return data;
}

export async function startMeeting(body: MeetingCreateRequest): Promise<MeetingDetail> {
  const { data } = await createMeeting({
    body,
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function issueMeetingTicket(meetingId: string, track: MeetingTrack): Promise<string> {
  const { data } = await createMeetingTicket({
    path: { meetingId },
    body: { track },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data.ticket;
}

export async function saveMeetingNotes(meetingId: string, notes: string, revision: number) {
  const { data } = await updateMeetingNotes({
    path: { meetingId },
    body: { notes, revision },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function nameSpeaker(
  meetingId: string,
  track: MeetingTrack,
  label: string,
  name: string | null,
) {
  const { data } = await nameMeetingSpeaker({
    path: { meetingId, track, label },
    body: { name },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function finishMeeting(meetingId: string) {
  const { data } = await endMeeting({
    path: { meetingId },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function rerunMinutes(meetingId: string) {
  const { data } = await rerunMeetingMinutes({
    path: { meetingId },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function markMinutesItem(meetingId: string, itemId: string, done: boolean) {
  const { data } = await markMeetingMinutesItem({
    path: { meetingId, itemId },
    body: { done },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function removeMeeting(meetingId: string) {
  await deleteMeeting({
    path: { meetingId },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
}

/** Where each track's recording left off, so a meeting reopened after a closed tab continues its clock. */
export function trackOffsets(meeting: MeetingDetail): Record<MeetingTrack, number> {
  const offsets: Record<MeetingTrack, number> = { MIC: 0, TAB: 0 };
  for (const utterance of meeting.utterances)
    offsets[utterance.track] = Math.max(offsets[utterance.track], utterance.endMs);
  return offsets;
}

/** A meeting's date and time in the interface language. */
export function formatWhen(iso: string, language: string) {
  return new Intl.DateTimeFormat(language, { dateStyle: "medium", timeStyle: "short" }).format(
    new Date(iso),
  );
}

export function formatClock(ms: number) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value: number) => String(value).padStart(2, "0");
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`;
}
