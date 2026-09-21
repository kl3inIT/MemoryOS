import { ApiError, sameOriginMutationHeaders } from "@/lib/api";
import {
  deleteChatFile,
  deleteChatFileArtifact,
  deleteChatImageArtifact,
  listChatLibrary,
} from "@/lib/hey-api/sdk.gen";
import type { ChatLibraryFile, ChatLibraryPage } from "@/lib/hey-api/types.gen";
import type { PreviewTarget } from "./chat-file-preview";

export type LibraryFile = ChatLibraryFile;
export type LibrarySource = ChatLibraryFile["source"];
export type LibraryCategory = ChatLibraryFile["category"];
export type LibrarySort = "NEWEST" | "OLDEST" | "LARGEST" | "SMALLEST";

export const chatLibraryKey = ["chat-library"] as const;
export const LIBRARY_PAGE_SIZE = 50;

export type LibraryFilter = {
  query: string;
  sources: LibrarySource[];
  categories: LibraryCategory[];
  sort: LibrarySort;
  /** Only this conversation's own files (MEM-144); absent lists the whole library. */
  sessionId?: string;
};

export async function loadLibrary(
  filter: LibraryFilter,
  offset: number,
  signal: AbortSignal,
): Promise<ChatLibraryPage> {
  const { data } = await listChatLibrary({
    query: {
      query: filter.query,
      sources: filter.sources,
      categories: filter.categories,
      sessionId: filter.sessionId,
      sort: filter.sort,
      offset,
      limit: LIBRARY_PAGE_SIZE,
    },
    signal,
    throwOnError: true,
  });
  return data;
}

/** Each source owns its own delete route; an upload keeps the lifecycle the composer already uses. */
export async function deleteLibraryFile(file: LibraryFile, signal: AbortSignal): Promise<void> {
  const request = { headers: sameOriginMutationHeaders, signal, throwOnError: true } as const;
  if (file.source === "GENERATED")
    await deleteChatFileArtifact({ path: { artifactId: file.id }, ...request });
  else if (file.source === "IMAGE")
    await deleteChatImageArtifact({ path: { artifactId: file.id }, ...request });
  else await deleteChatFile({ path: { fileId: file.id }, ...request });
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

export type LibraryGroup = { label: "today" | "yesterday" | "earlier"; items: LibraryFile[] };

/**
 * Day buckets in the order the server returned, unlike `groupThreadTitles`, which re-sorts by time and so
 * cannot group a list sorted by size.
 */
export function groupByDay(items: readonly LibraryFile[], now = new Date()): LibraryGroup[] {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1).getTime();
  const groups: LibraryGroup[] = [];
  for (const item of items) {
    const time = Date.parse(item.createdAt);
    const label = time >= today ? "today" : time >= yesterday ? "yesterday" : "earlier";
    const last = groups.at(-1);
    if (last?.label === label) last.items.push(item);
    else groups.push({ label, items: [item] });
  }
  return groups;
}

/**
 * The Projects and assistants a refused deletion named (`409 CHAT_FILE_IN_USE`), so the page can say why
 * instead of showing the generic conflict message.
 */
export function refusedBy(error: unknown): string[] {
  if (!(error instanceof ApiError) || !error.cause || typeof error.cause !== "object") return [];
  const usedBy = (error.cause as { usedBy?: unknown }).usedBy;
  if (!Array.isArray(usedBy)) return [];
  return usedBy.flatMap((usage) =>
    usage && typeof usage === "object" && typeof (usage as { name?: unknown }).name === "string"
      ? [(usage as { name: string }).name]
      : [],
  );
}

/** What holds an upload, for the label and for the refusal the delete route returns. */
export function usageLabel(file: LibraryFile): string | undefined {
  if (file.usedBy.length === 0) return undefined;
  return file.usedBy.map((usage) => usage.name).join(", ");
}
