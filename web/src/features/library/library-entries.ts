import { documentOriginalReader } from "@/features/documents/document-original-reader";
import { appText, type AppText } from "@/i18n/app-text";
import { i18n } from "@/i18n/index";
import { slug } from "@/lib/meeting-file-name";
import {
  exportMeetingMinutes,
  exportMeetingTranscript,
  getMeetingMinutesHeading,
  publishMeetingMinutes,
  recordChatLibraryEntryOpened,
} from "@/lib/hey-api/sdk.gen";
import {
  listChatLibraryDocumentsInfiniteOptions,
  listChatLibrarySharedOptions,
  listChatLibraryStarredOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatLibraryEntry, ChatLibrarySourceOption } from "@/lib/hey-api/types.gen";
import { imageArtifactUrl } from "./content-urls";
import { downloadUrl, type PreviewTarget } from "./file-preview";
import type { ChatFile } from "./files";
import { libraryCopy, type LibraryCategory } from "./library";

/**
 * One row of a view other than the owned listing: an owned file, or a read-only reference to a meeting, an
 * assistant's file or a Source document the viewer may read now. The server re-authorizes every row on each read,
 * so the page never decides what is visible.
 */
export type LibraryEntry = ChatLibraryEntry;
export type LibraryEntryKind = ChatLibraryEntry["kind"];
export type EntrySort = "NEWEST" | "OLDEST" | "NAME";

/** What an entry view narrows by; each route reads only the fields it knows. */
export type EntryFilter = {
  query: string;
  kinds: LibraryEntryKind[];
  categories: LibraryCategory[];
  sort: EntrySort;
  sourceIds: string[];
};

/** The server keeps the viewer's last opens and returns at most this many. */
export const RECENT_LIMIT = 100;

/** One page of one of the offset-paged views, as its generated query reads it. */
export function entryPageOptions(
  view: "shared" | "starred",
  filter: EntryFilter,
  offset: number,
  limit: number,
) {
  const { query, kinds, categories, sort } = filter;
  if (view === "shared")
    return listChatLibrarySharedOptions({
      query: { query, kinds, categories, sort, offset, limit },
    });
  return listChatLibraryStarredOptions({ query: { query, kinds, offset, limit } });
}

/** The keyset pages of the Source documents the viewer may read; each page continues the previous one. */
export function documentPagesOptions(filter: EntryFilter, limit: number) {
  return listChatLibraryDocumentsInfiniteOptions({
    query: {
      query: filter.query,
      sourceIds: filter.sourceIds,
      categories: filter.categories,
      sort: filter.sort === "NAME" ? "NAME" : "NEWEST",
      limit,
    },
  });
}

/**
 * Tells the server the viewer opened something, which is what Gần đây lists; an owned file is opened under its
 * source, which names its kind. It never stands in the way of what was opened: a failure, or a row that stopped
 * being reachable, is ignored.
 */
export function recordEntryOpened(kind: LibraryEntryKind, id: string): void {
  recordChatLibraryEntryOpened({ path: { kind, id } }).catch(() => undefined);
}

/** Why the viewer sees a row, in the words of the rule that admitted it. An owned row needs no reason. */
export function entryReason(entry: LibraryEntry): AppText | undefined {
  const names = entry.reason.names.join(", ");
  const source = entry.document?.sourceName ?? "";
  switch (entry.reason.kind) {
    case "OWNER":
      return undefined;
    case "MEMBER_SHARE":
      return entry.ownerName
        ? appText("Chia sẻ bởi {{name}}", { name: entry.ownerName })
        : appText("Được chia sẻ với bạn");
    case "GROUP_SHARE":
      return appText("Qua nhóm {{names}}", { names });
    case "AGENT":
      return appText("Qua trợ lý {{names}}", {
        names: names || entry.agents.map((agent) => agent.name).join(", "),
      });
    case "PUBLIC_SOURCE":
      return appText("{{source}} · Công khai trong tổ chức", { source });
    case "GROUP_SOURCE":
      return appText("{{source}} · Qua nhóm {{names}}", { source, names });
    case "PROVIDER_SOURCE":
      return appText("{{source}} · Quyền từ {{provider}}", {
        source,
        provider: PROVIDERS[entry.document?.sourceType ?? "FILE"] ?? source,
      });
  }
}

/** The provider whose own sharing grants a synced document; an uploaded Source has none. */
const PROVIDERS: Partial<Record<ChatLibrarySourceOption["type"], string>> = {
  GOOGLE_DRIVE: "Google Drive",
  SHAREPOINT: "SharePoint",
};

/**
 * The file the preview reads for an entry, through the same routes owned rows use. An assistant's file is an
 * upload whose routes also admit the assistant's readers. A meeting and a Source document are read elsewhere.
 */
export function entryPreviewTarget(entry: LibraryEntry): PreviewTarget | undefined {
  const source =
    entry.kind === "GENERATED"
      ? "generated"
      : entry.kind === "IMAGE"
        ? "image"
        : entry.kind === "UPLOAD" || entry.kind === "AGENT_FILE"
          ? "attachment"
          : undefined;
  if (!source) return undefined;
  return { source, id: entry.id, filename: entry.name, mediaType: entry.mediaType ?? undefined };
}

/**
 * Where an entry downloads from, or nothing: a meeting downloads its biên bản or transcript instead, and a Source
 * document has nothing to download until Search serves a generation of it.
 */
export function entryDownloadUrl(entry: LibraryEntry): string | undefined {
  const target = entryPreviewTarget(entry);
  if (target) return downloadUrl(target);
  const generation = entry.document?.generation;
  if (entry.kind === "DOCUMENT" && generation)
    return documentOriginalReader("search", entry.id, generation).url;
  return undefined;
}

/** The small picture a list shows for an entry that is an image, as owned rows show theirs. */
export function entryThumbnailUrl(entry: LibraryEntry): string | undefined {
  if (entry.kind === "IMAGE") return imageArtifactUrl(entry.id, "thumbnail");
  if (
    (entry.kind === "UPLOAD" || entry.kind === "AGENT_FILE") &&
    entry.mediaType?.startsWith("image/")
  )
    return `/api/chat/files/${encodeURIComponent(entry.id)}/thumbnail`;
  return undefined;
}

/**
 * The upload a question about an entry attaches. An upload or an assistant's file is attached as it is, because
 * turn admission honours the assistant's grant; a generated file or image is copied, as owned rows do.
 */
export async function entryUpload(
  entry: LibraryEntry,
  signal: AbortSignal,
): Promise<ChatFile | undefined> {
  if (entry.kind === "GENERATED" || entry.kind === "IMAGE")
    return libraryCopy(entry.kind, entry.id, signal);
  if (entry.kind !== "UPLOAD" && entry.kind !== "AGENT_FILE") return undefined;
  return {
    id: entry.id,
    filename: entry.name,
    mediaType: entry.mediaType ?? "application/octet-stream",
    sizeBytes: entry.sizeBytes ?? 0,
    status: "READY",
  };
}

/** Whole minutes a meeting's transcript covers, at least one once anything was said. */
export function meetingMinutes(durationMs: number): number {
  return durationMs <= 0 ? 0 : Math.max(1, Math.round(durationMs / 60_000));
}

/** Keeps entries whose name contains the query, for the one view the server does not search. */
export function entriesNamed(entries: readonly LibraryEntry[], query: string): LibraryEntry[] {
  const needle = query.trim().toLocaleLowerCase(i18n.language);
  if (!needle) return [...entries];
  return entries.filter((entry) => entry.name.toLocaleLowerCase(i18n.language).includes(needle));
}

/**
 * Downloads a meeting's biên bản as Word, with the heading its owner last saved, named as the meeting page names
 * it. A heading never saved prints its empty fields as blanks to write on, under the meeting's own title.
 */
export async function downloadMeetingMinutes(
  entry: LibraryEntry,
  signal: AbortSignal,
): Promise<void> {
  const { data: heading } = await getMeetingMinutesHeading({
    path: { meetingId: entry.id },
    signal,
  });
  const { saved, ...body } = heading;
  const { data } = await exportMeetingMinutes({
    path: { meetingId: entry.id },
    query: { format: "DOCX" },
    body: { ...body, about: saved ? body.about : body.about || entry.name },
    signal,
  });
  saveBlob(data as Blob, `bien-ban-${slug(entry.name)}.docx`);
}

/** Downloads what was said in a meeting as Word, named as the meeting page names it. */
export async function downloadMeetingTranscript(
  entry: LibraryEntry,
  signal: AbortSignal,
): Promise<void> {
  const { data } = await exportMeetingTranscript({
    path: { meetingId: entry.id },
    query: { format: "DOCX" },
    signal,
  });
  saveBlob(data as Blob, `transcript-${slug(entry.name)}.docx`);
}

/**
 * The viewer's own Markdown copy of a meeting's minutes, which is what Chat is asked about; the transcript is never
 * copied. Asking twice returns the same file.
 */
export async function meetingMinutesUpload(
  entry: LibraryEntry,
  signal: AbortSignal,
): Promise<string> {
  const { data } = await publishMeetingMinutes({ path: { meetingId: entry.id }, signal });
  return data.fileId;
}

/** Saves a returned document through a temporary object URL, as the meeting page does. */
function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const link = Object.assign(window.document.createElement("a"), { href: url, download: name });
  window.document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
