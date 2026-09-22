import { sameOriginMutationHeaders } from "@/lib/api";
import {
  acceptAllMeetingCorrections,
  acceptMeetingCorrection,
  bookmarkMeetingMoment,
  createMeeting,
  createMeetingTicket,
  deleteMeeting,
  endMeeting,
  exportMeetingMinutes,
  finalizeMeetingRecording,
  getMeeting,
  keepMeetingWording,
  listMeetingCorrections,
  listMeetings,
  listMeetingTranscribers,
  markMeetingMinutesItem,
  nameMeetingSpeaker,
  proposeMeetingCorrections,
  publishMeetingMinutes,
  removeMeetingBookmark,
  rerunMeetingMinutes,
  reserveMeetingRecording,
  revertAllMeetingCorrections,
  revertMeetingCorrection,
  shareMeeting as shareMeetingRequest,
  starMeetingUtterance,
  unstarMeetingUtterance,
  updateMeetingNotes,
} from "@/lib/hey-api/sdk.gen";
import type {
  MeetingAudio,
  MeetingCorrection,
  MeetingCorrectionRun,
  MeetingCreateRequest,
  MeetingHeadingRequest,
  MeetingDetail,
  MeetingMinutes,
  MeetingMinutesItem,
  MeetingReader,
  MeetingSummary,
  MeetingTranscriber,
  MeetingUtterance,
} from "@/lib/hey-api/types.gen";
import { putAuthorizedObject, sha256 } from "@/features/sources/direct-upload";
import type { MeetingTrack } from "./meeting-socket";

export type {
  MeetingAudio,
  MeetingCorrection,
  MeetingCorrectionRun,
  MeetingDetail,
  MeetingHeadingRequest,
  MeetingMinutes,
  MeetingMinutesItem,
  MeetingReader,
  MeetingSummary,
  MeetingTranscriber,
  MeetingUtterance,
};
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

export async function setUtteranceStar(meetingId: string, utteranceId: string, starred: boolean) {
  const request = starred ? starMeetingUtterance : unstarMeetingUtterance;
  const { data } = await request({
    path: { meetingId, utteranceId },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function addBookmark(meetingId: string, atMs: number, label?: string) {
  const { data } = await bookmarkMeetingMoment({
    path: { meetingId },
    body: { atMs, label: label ?? null },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function removeBookmark(meetingId: string, bookmarkId: string) {
  const { data } = await removeMeetingBookmark({
    path: { meetingId, bookmarkId },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export const correctionsKey = (id: string) => [...meetingKey(id), "corrections"] as const;

export async function loadCorrections(
  id: string,
  signal: AbortSignal,
): Promise<MeetingCorrection[]> {
  const { data } = await listMeetingCorrections({
    path: { meetingId: id },
    signal,
    throwOnError: true,
  });
  return data;
}

export async function proposeCorrections(meetingId: string): Promise<MeetingCorrectionRun> {
  const { data } = await proposeMeetingCorrections({
    path: { meetingId },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function acceptCorrection(meetingId: string, correctionId: string, text?: string) {
  const { data } = await acceptMeetingCorrection({
    path: { meetingId, correctionId },
    body: { text: text ?? null },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function keepWording(meetingId: string, correctionId: string) {
  const { data } = await keepMeetingWording({
    path: { meetingId, correctionId },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function acceptAllCorrections(meetingId: string, runId: string) {
  const { data } = await acceptAllMeetingCorrections({
    path: { meetingId },
    body: { runId },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function revertAllCorrections(meetingId: string, runId: string) {
  const { data } = await revertAllMeetingCorrections({
    path: { meetingId },
    body: { runId },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function revertCorrection(meetingId: string, correctionId: string) {
  const { data } = await revertMeetingCorrection({
    path: { meetingId, correctionId },
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

/** Downloads the minutes as a biên bản; the heading is printed, not stored, so it travels with the call. */
export async function exportMinutes(
  meetingId: string,
  heading: MeetingHeadingRequest,
): Promise<Blob> {
  const { data } = await exportMeetingMinutes({
    path: { meetingId },
    body: heading,
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data as Blob;
}

export const transcribersKey = [...meetingsKey, "transcribers"] as const;

/** The speech connections a recording may be transcribed with, the Tenant's own first. */
export async function loadTranscribers(signal: AbortSignal): Promise<MeetingTranscriber[]> {
  const { data } = await listMeetingTranscribers({ signal, throwOnError: true });
  return data;
}

/**
 * Sends a recording straight to object storage, as a library upload does, and then tells the meeting to transcribe
 * it. The bytes never pass through the API.
 */
export async function uploadRecording(
  meetingId: string,
  file: File,
  provider: MeetingTranscriber["provider"] | undefined,
  signal: AbortSignal,
  onProgress: (percent: number) => void,
): Promise<MeetingDetail> {
  const { data: reserved } = await reserveMeetingRecording({
    path: { meetingId },
    body: {
      filename: file.name,
      mediaType: file.type || "audio/mpeg",
      sizeBytes: file.size,
      sha256: await sha256(file, signal),
      provider,
    },
    headers: sameOriginMutationHeaders,
    signal,
    throwOnError: true,
  });
  await putAuthorizedObject(
    {
      method: reserved.method,
      uploadUrl: reserved.uploadUrl,
      requiredHeaders: reserved.requiredHeaders,
    },
    file,
    signal,
    onProgress,
  );
  const { data } = await finalizeMeetingRecording({
    path: { meetingId },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

/** Replaces who else may read the meeting; the server rechecks every member and Group. */
export async function shareMeeting(meetingId: string, members: string[], groups: string[]) {
  const { data } = await shareMeetingRequest({
    path: { meetingId },
    body: { members, groups },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

/** Takes the minutes into the caller's library so a conversation can use them; asking twice returns the same file. */
export async function publishMinutes(meetingId: string) {
  const { data } = await publishMeetingMinutes({
    path: { meetingId },
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

/** "ngày 21 tháng 9 năm 2026", the form a biên bản is written in. */
export function vietnameseDate(iso: string) {
  const at = new Date(iso);
  return `ngày ${at.getDate()} tháng ${at.getMonth() + 1} năm ${at.getFullYear()}`;
}

/** "09 giờ 00 ngày 21 tháng 9 năm 2026". */
export function vietnameseMoment(iso: string) {
  const at = new Date(iso);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${pad(at.getHours())} giờ ${pad(at.getMinutes())} ${vietnameseDate(iso)}`;
}

export function formatClock(ms: number) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value: number) => String(value).padStart(2, "0");
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`;
}
