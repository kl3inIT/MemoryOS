import { problemOf } from "@/lib/api";
import { i18n } from "@/i18n/index";
import {
  changeChatLibraryFile,
  copyChatLibraryFile,
  emptyChatLibraryTrash,
  getChatLibraryTrashWindow,
  getChatLibraryUsage,
  purgeChatLibraryFile,
  restoreChatLibraryFile,
  deleteChatFile,
  deleteChatFileArtifact,
  deleteChatImageArtifact,
  listChatLibrary,
  searchChatLibraryContent,
} from "@/lib/hey-api/sdk.gen";
import type {
  ChatLibraryContentMatch,
  ChatLibraryFile,
  ChatLibraryPage,
} from "@/lib/hey-api/types.gen";
import type { PreviewTarget } from "./file-preview";
import { chatFileSchema, waitForChatFile, type ChatFile } from "./files";
import { imageArtifactUrl } from "./content-urls";

export type LibraryFile = ChatLibraryFile;
export type LibrarySource = ChatLibraryFile["source"];
export type LibraryCategory = ChatLibraryFile["category"];
export type LibrarySort = "NEWEST" | "OLDEST" | "LARGEST" | "SMALLEST" | "NAME" | "DELETED";
export type LibraryStatus = "READY" | "PENDING" | "TRASH";
export type ContentMatch = ChatLibraryContentMatch;

export const chatLibraryKey = ["chat-library"] as const;
/** Every category a file can fall into, in the order the filters and the storage page show them. */
export const LIBRARY_CATEGORIES = [
  "DOCUMENT",
  "SPREADSHEET",
  "IMAGE",
  "PRESENTATION",
  "OTHER",
] as const satisfies readonly LibraryCategory[];
export const LIBRARY_PAGE_SIZE = 50;
/** How many files a page may hold; the server admits at most 100 rows in one listing. */
export const LIBRARY_PAGE_SIZES = [12, 24, 50, 100] as const;

export type LibraryFilter = {
  query: string;
  sources: LibrarySource[];
  categories: LibraryCategory[];
  sort: LibrarySort;
  /** Only this conversation's own files (MEM-144); absent lists the whole library. */
  sessionId?: string;
  /** Only starred files (MEM-152). */
  favorite?: boolean;
  /**
   * PENDING lists the owner's uploads still uploading, processing or failed; TRASH lists what they deleted and
   * may still restore.
   */
  status?: LibraryStatus;
};

export async function loadLibrary(
  filter: LibraryFilter,
  offset: number,
  signal: AbortSignal,
  limit: number = LIBRARY_PAGE_SIZE,
): Promise<ChatLibraryPage> {
  const { data } = await listChatLibrary({
    query: {
      query: filter.query,
      sources: filter.sources,
      categories: filter.categories,
      sessionId: filter.sessionId,
      favorite: filter.favorite,
      status: filter.status,
      sort: filter.sort,
      offset,
      limit,
    },
    signal,
  });
  return data;
}

/** Each source owns its own delete route; an upload keeps the lifecycle the composer already uses. */
export async function deleteLibraryFile(file: LibraryFile, signal: AbortSignal): Promise<void> {
  if (file.source === "GENERATED")
    await deleteChatFileArtifact({ path: { artifactId: file.id }, signal });
  else if (file.source === "IMAGE")
    await deleteChatImageArtifact({ path: { artifactId: file.id }, signal });
  else await deleteChatFile({ path: { fileId: file.id }, signal });
}

export function libraryPreviewTarget(file: LibraryFile): PreviewTarget {
  return {
    source:
      file.source === "GENERATED" ? "generated" : file.source === "IMAGE" ? "image" : "attachment",
    id: file.id,
    filename: file.filename,
    mediaType: file.mediaType,
  };
}

/**
 * The small rendering a list shows for a file, or nothing where the file is not a picture. A generated image
 * and an uploaded one are each served by their own route; both are owner-private and authorized per read.
 */
export function libraryThumbnailUrl(file: LibraryFile): string | undefined {
  if (file.source === "IMAGE") return imageArtifactUrl(file.id, "thumbnail");
  if (file.source === "UPLOAD" && file.mediaType?.startsWith("image/"))
    return `/api/chat/files/${file.id}/thumbnail`;
  return undefined;
}

/**
 * One bucket per calendar day the files were made on, in the order the server returned them. The two days a
 * person names rather than dates — today and yesterday — keep their names; every other day is its own group
 * carrying that day, so the page can write the date in the reader's own locale.
 */
export type LibraryDayGroup = {
  /** The local calendar day as `YYYY-MM-DD`, which is also what keeps the group stable across renders. */
  day: string;
  when: "today" | "yesterday" | "date";
  items: LibraryFile[];
};

export function groupByDate(items: readonly LibraryFile[], now = new Date()): LibraryDayGroup[] {
  const startOfDay = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate());
  const key = (date: Date) =>
    `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
  const today = key(startOfDay(now));
  const yesterday = key(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1));
  const groups: LibraryDayGroup[] = [];
  for (const item of items) {
    const day = key(startOfDay(new Date(item.createdAt)));
    const when = day === today ? "today" : day === yesterday ? "yesterday" : "date";
    const last = groups.at(-1);
    if (last?.day === day) last.items.push(item);
    else groups.push({ day, when, items: [item] });
  }
  return groups;
}

/**
 * The Projects and assistants a refused deletion named (`409 CHAT_FILE_IN_USE`), so the page can say why
 * instead of showing the generic conflict message.
 */
export function refusedBy(error: unknown): string[] {
  const usedBy = problemOf(error)?.usedBy;
  if (!Array.isArray(usedBy)) return [];
  return usedBy.flatMap((usage) => (typeof usage?.name === "string" ? [usage.name] : []));
}

/** What holds an upload, for the label and for the refusal the delete route returns. */
export function usageLabel(file: LibraryFile): string | undefined {
  if (file.usedBy.length === 0) return undefined;
  return file.usedBy.map((usage) => usage.name).join(", ");
}

/**
 * The upload a library file is attached as. An upload is one already; a generated file or image is copied on the
 * server into an upload of its own (MEM-152), which is usable once the file worker has extracted it.
 */
export async function libraryUpload(file: LibraryFile, signal: AbortSignal): Promise<ChatFile> {
  if (file.source === "UPLOAD")
    return {
      id: file.id,
      filename: file.filename,
      mediaType: file.mediaType,
      sizeBytes: file.sizeBytes,
      status: "READY",
    };
  const { data } = await copyChatLibraryFile({
    path: { source: file.source, id: file.id },
    signal,
  });
  const copy = chatFileSchema.parse(data);
  return copy.status === "READY" ? copy : waitForChatFile(copy.id, signal);
}

/** Renames a file or stars it; the library shows the result at once, as does every surface reading its name. */
export async function changeLibraryFile(
  file: LibraryFile,
  change: { filename?: string; favorite?: boolean },
  signal: AbortSignal,
): Promise<LibraryFile> {
  const { data } = await changeChatLibraryFile({
    path: { source: file.source, id: file.id },
    body: change,
    signal,
  });
  return data;
}

/** Finds the caller's own indexed uploads by what they contain, with the passages that matched. */
export async function searchLibraryContent(
  query: string,
  signal: AbortSignal,
): Promise<ContentMatch[]> {
  const { data } = await searchChatLibraryContent({ query: { query }, signal });
  return data;
}

/** The query's occurrences inside a passage, so a match can be seen without opening the file. */
export function highlightParts(text: string, query: string): { text: string; match: boolean }[] {
  const needle = query.trim().toLocaleLowerCase(i18n.language);
  if (!needle) return [{ text, match: false }];
  const parts: { text: string; match: boolean }[] = [];
  const haystack = text.toLocaleLowerCase(i18n.language);
  let from = 0;
  for (let at = haystack.indexOf(needle, from); at >= 0; at = haystack.indexOf(needle, from)) {
    if (at > from) parts.push({ text: text.slice(from, at), match: false });
    parts.push({ text: text.slice(at, at + needle.length), match: true });
    from = at + needle.length;
  }
  if (from < text.length) parts.push({ text: text.slice(from), match: false });
  return parts.length > 0 ? parts : [{ text, match: false }];
}

/** What the caller's library holds and the limit that applies to them (MEM-152). */
export async function loadLibraryUsage(signal: AbortSignal) {
  const { data } = await getChatLibraryUsage({ signal });
  return data;
}

/** How many days a deleted file stays restorable in this deployment. */
export async function loadTrashWindow(signal: AbortSignal) {
  const { data } = await getChatLibraryTrashWindow({ signal });
  return data.days;
}

export async function restoreLibraryFile(file: LibraryFile, signal: AbortSignal): Promise<void> {
  await restoreChatLibraryFile({
    path: { source: file.source, id: file.id },
    signal,
  });
}

/** Ends one file's trash window, so its bytes are released by the usual routes. */
export async function purgeLibraryFile(file: LibraryFile, signal: AbortSignal): Promise<void> {
  await purgeChatLibraryFile({
    path: { source: file.source, id: file.id },
    signal,
  });
}

export async function emptyLibraryTrash(signal: AbortSignal): Promise<number> {
  const { data } = await emptyChatLibraryTrash({
    signal,
  });
  return data.purged;
}
