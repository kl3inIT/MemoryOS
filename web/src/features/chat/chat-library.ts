import { ApiError, sameOriginMutationHeaders } from "@/lib/api";
import { i18n } from "@/i18n";
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
  getChatProject,
  listChatLibrary,
  searchChatLibraryContent,
  updateChatProject,
} from "@/lib/hey-api/sdk.gen";
import type {
  ChatLibraryContentMatch,
  ChatLibraryFile,
  ChatLibraryPage,
} from "@/lib/hey-api/types.gen";
import type { PreviewTarget } from "./chat-file-preview";
import { chatFileSchema, waitForChatFile, type ChatFile } from "./chat-files";
import { projectSchema } from "./chat-workspace-api";

export type LibraryFile = ChatLibraryFile;
export type LibrarySource = ChatLibraryFile["source"];
export type LibraryCategory = ChatLibraryFile["category"];
export type LibrarySort = "NEWEST" | "OLDEST" | "LARGEST" | "SMALLEST" | "NAME" | "DELETED";
export type LibraryStatus = "READY" | "PENDING" | "TRASH";
export type ContentMatch = ChatLibraryContentMatch;

export const chatLibraryKey = ["chat-library"] as const;
export const LIBRARY_PAGE_SIZE = 50;

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
    headers: sameOriginMutationHeaders,
    signal,
    throwOnError: true,
  });
  const copy = chatFileSchema.parse(data);
  return copy.status === "READY" ? copy : waitForChatFile(copy.id, signal);
}

/** A Project admits at most this many files, as a message does. */
export const PROJECT_FILE_LIMIT = 20;

export class ProjectFull extends Error {
  constructor() {
    super("PROJECT_FULL");
  }
}

/**
 * Adds library files to a Project the caller owns through the Project's own update, so the server admits them
 * exactly as files attached in the Project editor. Generated files are copied into uploads first.
 */
export async function addToProject(
  projectId: string,
  files: readonly LibraryFile[],
  signal: AbortSignal,
): Promise<void> {
  const uploads: ChatFile[] = [];
  for (const file of files) uploads.push(await libraryUpload(file, signal));
  await changeProjectFiles(projectId, signal, (current) => [
    ...new Set([...current, ...uploads.map((upload) => upload.id)]),
  ]);
}

/** Removes only the link: the file stays in the library. */
export async function removeFromProject(projectId: string, fileId: string, signal: AbortSignal) {
  await changeProjectFiles(projectId, signal, (current) => current.filter((id) => id !== fileId));
}

async function changeProjectFiles(
  projectId: string,
  signal: AbortSignal,
  change: (current: string[]) => string[],
) {
  const project = projectSchema.parse(
    (await getChatProject({ path: { projectId }, signal, throwOnError: true })).data,
  );
  const fileIds = change(project.fileIds);
  if (fileIds.length > PROJECT_FILE_LIMIT) throw new ProjectFull();
  await updateChatProject({
    path: { projectId },
    query: { revision: project.revision },
    body: {
      name: project.name,
      description: project.description,
      instructions: project.instructions,
      fileIds,
    },
    headers: sameOriginMutationHeaders,
    signal,
    throwOnError: true,
  });
}

export type BranchStep = { messageId: string; expectedChildId: string | null };

/**
 * The version selections that put `target` on the conversation's selected path, from the root down. Each step
 * selects one message among its siblings, so its parent's current choice is the expected child the server checks.
 * Empty when the target is already shown or is not in this conversation.
 */
export function branchSteps(
  branches: readonly {
    id: string;
    parentMessageId: string | null;
    latestChildMessageId: string | null;
  }[],
  target: string,
): BranchStep[] {
  const byId = new Map(branches.map((branch) => [branch.id, branch]));
  const steps: BranchStep[] = [];
  let node = byId.get(target);
  const seen = new Set<string>();
  while (node?.parentMessageId && !seen.has(node.id)) {
    seen.add(node.id);
    const parent = byId.get(node.parentMessageId);
    if (!parent) return [];
    if (parent.latestChildMessageId !== node.id)
      steps.unshift({ messageId: node.id, expectedChildId: parent.latestChildMessageId });
    node = parent;
  }
  return node ? steps : [];
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
    headers: sameOriginMutationHeaders,
    signal,
    throwOnError: true,
  });
  return data;
}

/** Finds the caller's own indexed uploads by what they contain, with the passages that matched. */
export async function searchLibraryContent(
  query: string,
  signal: AbortSignal,
): Promise<ContentMatch[]> {
  const { data } = await searchChatLibraryContent({ query: { query }, signal, throwOnError: true });
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
  const { data } = await getChatLibraryUsage({ signal, throwOnError: true });
  return data;
}

/** How many days a deleted file stays restorable in this deployment. */
export async function loadTrashWindow(signal: AbortSignal) {
  const { data } = await getChatLibraryTrashWindow({ signal, throwOnError: true });
  return data.days;
}

export async function restoreLibraryFile(file: LibraryFile, signal: AbortSignal): Promise<void> {
  await restoreChatLibraryFile({
    path: { source: file.source, id: file.id },
    headers: sameOriginMutationHeaders,
    signal,
    throwOnError: true,
  });
}

/** Ends one file's trash window, so its bytes are released by the usual routes. */
export async function purgeLibraryFile(file: LibraryFile, signal: AbortSignal): Promise<void> {
  await purgeChatLibraryFile({
    path: { source: file.source, id: file.id },
    headers: sameOriginMutationHeaders,
    signal,
    throwOnError: true,
  });
}

export async function emptyLibraryTrash(signal: AbortSignal): Promise<number> {
  const { data } = await emptyChatLibraryTrash({
    headers: sameOriginMutationHeaders,
    signal,
    throwOnError: true,
  });
  return data.purged;
}
