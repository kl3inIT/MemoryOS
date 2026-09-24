import { sameOriginMutationHeaders } from "@/lib/api";
import {
  acceptAllMeetingCorrections,
  acceptMeetingCorrection,
  addMeetingMinutesItem,
  correctMeetingWords,
  bookmarkMeetingMoment,
  createMeeting,
  createMeetingTicket,
  deleteMeeting,
  dismissMeetingSpeakerSuggestion,
  editMeetingMinutesItem,
  editMeetingMinutesSummary,
  endMeeting,
  exportMeetingMinutes,
  finalizeMeetingRecording,
  getMeeting,
  getMeetingMinutesHeading,
  keepMeetingWording,
  listMeetingCorrections,
  listMeetings,
  listMeetingTranscribers,
  markMeetingMinutesItem,
  nameMeetingSpeaker,
  proposeMeetingCorrections,
  exportMeetingTranscript,
  publishMeetingMinutes,
  removeMeetingBookmark,
  removeMeetingMinutesItem,
  rerunMeetingMinutes,
  reserveMeetingRecording,
  revertAllMeetingCorrections,
  revertMeetingCorrection,
  saveMeetingMinutesHeading,
  shareMeeting as shareMeetingRequest,
  starMeetingUtterance,
  unstarMeetingUtterance,
  updateMeeting,
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

/** Keeps the automatic label for a voice and stops offering the name it gave itself. */
export async function dismissSpeakerSuggestion(
  meetingId: string,
  track: MeetingTrack,
  label: string,
) {
  const { data } = await dismissMeetingSpeakerSuggestion({
    path: { meetingId, track, label },
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

/** The owner writes what was said at one marked stretch of a line. */
export async function correctWords(
  meetingId: string,
  utteranceId: string,
  start: number,
  end: number,
  text: string,
) {
  const { data } = await correctMeetingWords({
    path: { meetingId, utteranceId },
    body: { start, end, text },
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

/** Writes the minutes again from the transcript. Saying so is required once they were corrected by hand. */
export async function rerunMinutes(meetingId: string, discardEdits = false) {
  const { data } = await rerunMeetingMinutes({
    path: { meetingId },
    query: { discardEdits },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function editMinutesSummary(meetingId: string, summary: string) {
  const { data } = await editMeetingMinutesSummary({
    path: { meetingId },
    body: { summary },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function editMinutesItem(
  meetingId: string,
  itemId: string,
  text: string,
  owner: string,
  due: string,
) {
  const { data } = await editMeetingMinutesItem({
    path: { meetingId, itemId },
    body: { text, owner: owner.trim() || null, due: due.trim() || null },
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
  format: "DOCX" | "PDF" = "DOCX",
): Promise<Blob> {
  const { data } = await exportMeetingMinutes({
    path: { meetingId },
    query: { format },
    body: heading,
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data as Blob;
}

/** Writes in a decision or a piece of work the model missed. */
export async function addMinutesItem(
  meetingId: string,
  kind: "ACTION" | "DECISION",
  text: string,
  owner: string,
  due: string,
) {
  const { data } = await addMeetingMinutesItem({
    path: { meetingId },
    body: { kind, text, owner: owner || null, due: due || null },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export async function removeMinutesItem(meetingId: string, itemId: string) {
  const { data } = await removeMeetingMinutesItem({
    path: { meetingId, itemId },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

export const meetingHeadingKey = (id: string) => [...meetingKey(id), "heading"] as const;

/** The biên bản heading as the owner last saved it; `saved` is false until they do. */
export async function loadMinutesHeading(meetingId: string, signal: AbortSignal) {
  const { data } = await getMeetingMinutesHeading({
    path: { meetingId },
    signal,
    throwOnError: true,
  });
  return data;
}

export async function saveMinutesHeading(meetingId: string, heading: MeetingHeadingRequest) {
  const { data } = await saveMeetingMinutesHeading({
    path: { meetingId },
    body: heading,
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

/** Renames the meeting and replaces who was in it; the server trims and de-duplicates the names. */
export async function updateMeetingDetails(
  meetingId: string,
  title: string,
  participants: string[],
) {
  const { data } = await updateMeeting({
    path: { meetingId },
    body: { title, participants },
    headers: sameOriginMutationHeaders,
    throwOnError: true,
  });
  return data;
}

/** Downloads what was said, for reading elsewhere or for sending to somebody who was not there. */
export async function exportTranscript(meetingId: string, format: "DOCX" | "PDF"): Promise<Blob> {
  const { data } = await exportMeetingTranscript({
    path: { meetingId },
    query: { format },
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
