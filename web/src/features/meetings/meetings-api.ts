import type { QueryClient } from "@tanstack/react-query";
import {
  getMeetingMinutesHeadingQueryKey,
  getMeetingQueryKey,
  listMeetingCorrectionsQueryKey,
  listMeetingsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import {
  createMeetingTicket,
  deleteMeeting,
  endMeeting,
  finalizeMeetingRecording,
  reserveMeetingRecording,
} from "@/lib/hey-api/sdk.gen";
import type {
  MeetingCorrection,
  MeetingCorrectionApplied,
  MeetingDetail,
  MeetingHeadingRequest,
  MeetingMinutesItem,
  MeetingSpeaker,
  MeetingSummary,
  MeetingTranscriber,
  MeetingUtterance,
} from "@/lib/hey-api/types.gen";
import { putAuthorizedObject, sha256 } from "@/lib/direct-upload";
import type { NamedRef, Person } from "@/features/identity/principals";
import type { MeetingTrack } from "./meeting-socket";

export type {
  MeetingCorrection,
  MeetingDetail,
  MeetingHeadingRequest,
  MeetingMinutesItem,
  MeetingSpeaker,
  MeetingSummary,
  MeetingTranscriber,
};
export type MeetingKind = MeetingDetail["kind"];

/** The cached meeting with its transcript, as the meeting page reads it. */
export const meetingQueryKey = (meetingId: string) => getMeetingQueryKey({ path: { meetingId } });
/** The owner's correction proposals of one meeting. */
export const correctionsQueryKey = (meetingId: string) =>
  listMeetingCorrectionsQueryKey({ path: { meetingId } });
/** The biên bản heading the owner last saved for one meeting. */
export const headingQueryKey = (meetingId: string) =>
  getMeetingMinutesHeadingQueryKey({ path: { meetingId } });

/** Marks the meeting list stale; a meeting's own detail, its proposals and the transcribers stay as they are. */
export function invalidateMeetingList(cache: QueryClient) {
  return cache.invalidateQueries({ queryKey: listMeetingsQueryKey() });
}

/**
 * Removes a meeting that was created for content which then never arrived. A removal that fails leaves the empty
 * meeting in the list, where its owner can delete it.
 */
export async function discardMeeting(cache: QueryClient, meetingId: string) {
  await deleteMeeting({ path: { meetingId } }).catch(() => undefined);
  await invalidateMeetingList(cache);
}

/**
 * Folds the part of a meeting a small change answered with into the cached meeting. The transcript is never sent
 * back for a tick or a rename; a meeting that is not cached is read whole the next time it is shown.
 */
export function patchMeeting(
  cache: QueryClient,
  meetingId: string,
  patch: (meeting: MeetingDetail) => MeetingDetail,
) {
  cache.setQueryData<MeetingDetail>(meetingQueryKey(meetingId), (current) =>
    current ? patch(current) : current,
  );
}

/** The meeting with one speaker as the server now has it. */
export function withSpeaker(meeting: MeetingDetail, speaker: MeetingSpeaker): MeetingDetail {
  return {
    ...meeting,
    speakers: meeting.speakers.map((current) =>
      current.track === speaker.track && current.label === speaker.label ? speaker : current,
    ),
  };
}

/** The meeting with one decision or piece of work as the server now has it; a topic is never changed. */
export function withMinutesItem(meeting: MeetingDetail, item: MeetingMinutesItem): MeetingDetail {
  const replace = (items: MeetingMinutesItem[]) =>
    items.map((current) => (current.id === item.id ? item : current));
  const { minutes } = meeting;
  return {
    ...meeting,
    minutes: {
      ...minutes,
      decisions: replace(minutes.decisions),
      actions: replace(minutes.actions),
      // Only the owner's own words are marked edited, and writing them marks the minutes edited too.
      edited: minutes.edited || item.edited,
    },
  };
}

/** The meeting with an item the owner wrote in, after the others of its kind as the server placed it. */
export function withAddedMinutesItem(
  meeting: MeetingDetail,
  kind: "ACTION" | "DECISION",
  item: MeetingMinutesItem,
): MeetingDetail {
  const { minutes } = meeting;
  const list = kind === "ACTION" ? "actions" : "decisions";
  return {
    ...meeting,
    minutes: { ...minutes, [list]: [...minutes[list], item], edited: true },
  };
}

/** The meeting without an item the owner took out; taking one out makes the minutes the owner's. */
export function withoutMinutesItem(meeting: MeetingDetail, itemId: string): MeetingDetail {
  const keep = (items: MeetingMinutesItem[]) => items.filter((item) => item.id !== itemId);
  const { minutes } = meeting;
  return {
    ...meeting,
    minutes: {
      ...minutes,
      decisions: keep(minutes.decisions),
      actions: keep(minutes.actions),
      edited: true,
    },
  };
}

/** The meeting with one line as the server now has it. */
function withUtterance(meeting: MeetingDetail, utterance: MeetingUtterance): MeetingDetail {
  return {
    ...meeting,
    utterances: meeting.utterances.map((current) =>
      current.id === utterance.id ? utterance : current,
    ),
  };
}

/** The proposals with one as the server now has it; a correction written by hand is new and comes last. */
function withCorrection(
  corrections: MeetingCorrection[],
  correction: MeetingCorrection,
): MeetingCorrection[] {
  return corrections.some((current) => current.id === correction.id)
    ? corrections.map((current) => (current.id === correction.id ? correction : current))
    : [...corrections, correction];
}

/**
 * The other proposals on a line one decision rewrote, as the server now holds them: one still undecided over the
 * rewritten words is declined, and whatever stands after the change moves with it.
 */
function realigned(corrections: MeetingCorrection[], decided: MeetingCorrection) {
  // An accepted change replaced what it pointed at; a reverted one put those words back over what it had written.
  const reverted = decided.status === "REVERTED";
  const end = reverted ? decided.start + decided.after.length : decided.end;
  const delta = (reverted ? decided.before.length : decided.after.length) - (end - decided.start);
  return corrections.map((current): MeetingCorrection => {
    if (current.id === decided.id || current.utteranceId !== decided.utteranceId) return current;
    if (current.status === "PENDING" && current.start < end && current.end > decided.start)
      return { ...current, status: "KEPT" };
    if ((current.status === "PENDING" || current.status === "ACCEPTED") && current.start >= end)
      return { ...current, start: current.start + delta, end: current.end + delta };
    return current;
  });
}

/**
 * Folds what deciding one stretch answered into the cached meeting and its proposals: the line it rewrote, when it
 * rewrote one, the proposal as it now stands and the others on that line. Neither is read again.
 */
export function foldCorrection(
  cache: QueryClient,
  meetingId: string,
  answer: MeetingCorrectionApplied | MeetingCorrection,
) {
  const correction = "utterance" in answer ? answer.correction : answer;
  if ("utterance" in answer)
    patchMeeting(cache, meetingId, (meeting) => withUtterance(meeting, answer.utterance));
  cache.setQueryData<MeetingCorrection[]>(correctionsQueryKey(meetingId), (current) => {
    if (!current) return current;
    const folded = withCorrection(current, correction);
    return "utterance" in answer ? realigned(folded, correction) : folded;
  });
}

/** A one-use ticket that opens one track's audio socket; the recorder asks for one per connection. */
export async function issueMeetingTicket(meetingId: string, track: MeetingTrack): Promise<string> {
  const { data } = await createMeetingTicket({ path: { meetingId }, body: { track } });
  return data.ticket;
}

/** Ends the meeting once its last words are stored; the recording session calls it outside any page. */
export async function finishMeeting(meetingId: string) {
  const { data } = await endMeeting({ path: { meetingId } });
  return data;
}

/**
 * Sends a recording straight to object storage, as a library upload does, and then tells the meeting to transcribe
 * it. The bytes never pass through the API.
 */
export async function uploadRecording({
  meetingId,
  file,
  provider,
  signal,
  onProgress,
}: {
  meetingId: string;
  file: File;
  provider: MeetingTranscriber["provider"] | undefined;
  signal: AbortSignal;
  onProgress: (percent: number) => void;
}): Promise<MeetingDetail> {
  const { data: reserved } = await reserveMeetingRecording({
    path: { meetingId },
    body: {
      filename: file.name,
      mediaType: file.type || "audio/mpeg",
      sizeBytes: file.size,
      sha256: await sha256(file, signal),
      provider,
    },
    signal,
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
  const { data } = await finalizeMeetingRecording({ path: { meetingId } });
  return data;
}

/** Who a meeting reaches beside its owner. Names typed for people outside MemoryOS are kept separately. */
export type MeetingAudience = { people: Person[]; groups: NamedRef[] };

/** The readers a meeting already has, as the picker shows them. */
export function audienceOf(meeting: MeetingDetail): MeetingAudience {
  return {
    people: meeting.readers
      .filter((reader) => reader.kind === "MEMBER")
      .map((reader) => ({ actorId: reader.id, name: reader.name, email: null })),
    groups: meeting.readers
      .filter((reader) => reader.kind === "GROUP")
      .map((reader) => ({ id: reader.id, name: reader.name })),
  };
}

/** The share request for an audience: the members and Groups by id. */
export function shareBody(audience: MeetingAudience) {
  return {
    members: audience.people.map((person) => person.actorId),
    groups: audience.groups.map((group) => group.id),
  };
}

/** Splits "Anh Thanh, Chị Lan" into names; the server trims and de-duplicates again. */
export function splitNames(value: string) {
  return value
    .split(/[,;\n]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

/** Saves a downloaded document through a temporary object URL, as the file preview does. */
export function saveDocument(document: Blob, name: string) {
  const url = URL.createObjectURL(document);
  const link = Object.assign(window.document.createElement("a"), { href: url, download: name });
  window.document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
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

/** "09 giờ 00 ngày 21 tháng 9 năm 2026", the form a biên bản is written in. */
export function vietnameseMoment(iso: string) {
  const at = new Date(iso);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${pad(at.getHours())} giờ ${pad(at.getMinutes())} ngày ${at.getDate()} tháng ${at.getMonth() + 1} năm ${at.getFullYear()}`;
}

export function formatClock(ms: number) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value: number) => String(value).padStart(2, "0");
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`;
}

/** A place in the transcript worth returning to: a subject of the minutes, or a moment the reader marked. */
export type TimelineEntry = {
  id: string;
  kind: "topic" | "bookmark";
  text: string;
  /** When it happened on the meeting's clock. */
  atMs: number;
  /** The line of the transcript it opens on. */
  line: MeetingDetail["utterances"][number];
};

/**
 * The subjects that still rest on a line of the transcript and the moments the reader marked, in the order the
 * meeting reached them. A moment is marked while somebody is still speaking, so it opens on the last line that had
 * begun by then.
 */
export function timelineOf(meeting: MeetingDetail): TimelineEntry[] {
  const { utterances } = meeting;
  const lines = new Map(utterances.map((utterance) => [utterance.id, utterance]));
  const topics = meeting.minutes.topics.flatMap((topic): TimelineEntry[] => {
    const line = topic.sourceUtteranceId ? lines.get(topic.sourceUtteranceId) : undefined;
    return line
      ? [{ id: topic.id, kind: "topic", text: topic.text, atMs: line.startMs, line }]
      : [];
  });
  const bookmarks = meeting.bookmarks.flatMap((bookmark): TimelineEntry[] => {
    const line =
      utterances.findLast((utterance) => utterance.startMs <= bookmark.atMs) ?? utterances[0];
    return line
      ? [{ id: bookmark.id, kind: "bookmark", text: bookmark.label, atMs: bookmark.atMs, line }]
      : [];
  });
  return [...topics, ...bookmarks].sort((left, right) => left.atMs - right.atMs);
}
