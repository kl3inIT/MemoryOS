import type { QueryClient } from "@tanstack/react-query";
import { problemOf } from "@/lib/api";
import { i18n } from "@/i18n/index";
import {
  getChatLibraryTrashWindowQueryKey,
  getChatLibraryUsageQueryKey,
  listChatLibraryOptions,
  listChatLibraryQueryKey,
  searchChatLibraryContentQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import {
  copyChatLibraryFile,
  deleteChatFile,
  deleteChatFileArtifact,
  deleteChatImageArtifact,
  listChatLibrary,
} from "@/lib/hey-api/sdk.gen";
import type {
  ChatLibraryContentMatch,
  ChatLibraryFile,
  ChatLibraryPage,
  SearchChatLibraryContentData,
} from "@/lib/hey-api/types.gen";
import type { Options } from "@/lib/hey-api/sdk.gen";
import type { PreviewTarget } from "./file-preview";
import { chatFileSchema, waitForChatFile, type ChatFile } from "./files";
import { imageArtifactUrl } from "./content-urls";

export type LibraryFile = ChatLibraryFile;
export type LibrarySource = ChatLibraryFile["source"];
export type LibraryCategory = ChatLibraryFile["category"];
export type LibrarySort = "NEWEST" | "OLDEST" | "LARGEST" | "SMALLEST" | "NAME" | "DELETED";
type LibraryStatus = "READY" | "PENDING" | "TRASH";
export type ContentMatch = ChatLibraryContentMatch;

/**
 * The prefix of the library queries Chat still builds by hand (a conversation's own files). The library's own
 * reads use the generated keys; {@link invalidateLibrary} refreshes both.
 */
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

/** The listing's parameters for one page of a filter. */
function libraryQuery(filter: LibraryFilter, offset: number, limit: number) {
  return {
    query: filter.query,
    sources: filter.sources,
    categories: filter.categories,
    sessionId: filter.sessionId,
    favorite: filter.favorite,
    status: filter.status,
    sort: filter.sort,
    offset,
    limit,
  };
}

/** One page of the library as the generated query reads it. */
export function libraryOptions(
  filter: LibraryFilter,
  offset: number,
  limit: number = LIBRARY_PAGE_SIZE,
) {
  return listChatLibraryOptions({ query: libraryQuery(filter, offset, limit) });
}

/** One page of the library, for a caller that builds its own query. */
export async function loadLibrary(
  filter: LibraryFilter,
  offset: number,
  signal: AbortSignal,
  limit: number = LIBRARY_PAGE_SIZE,
): Promise<ChatLibraryPage> {
  const { data } = await listChatLibrary({ query: libraryQuery(filter, offset, limit), signal });
  return data;
}

/**
 * Every content search, whatever it asked: the generated key without a query is the prefix of all of them.
 */
const everyContentSearch = searchChatLibraryContentQueryKey(
  {} as Options<SearchChatLibraryContentData>,
);

/**
 * Refreshes every read of the library after a change: the listings, the usage, the trash window, the content
 * searches, and the queries Chat keys under {@link chatLibraryKey}.
 */
export function invalidateLibrary(cache: QueryClient) {
  return Promise.all([
    cache.invalidateQueries({ queryKey: listChatLibraryQueryKey() }),
    cache.invalidateQueries({ queryKey: getChatLibraryUsageQueryKey() }),
    cache.invalidateQueries({ queryKey: getChatLibraryTrashWindowQueryKey() }),
    cache.invalidateQueries({ queryKey: everyContentSearch }),
    cache.invalidateQueries({ queryKey: chatLibraryKey }),
  ]);
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
