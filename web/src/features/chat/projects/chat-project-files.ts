import { getChatProject, updateChatProject } from "@/lib/hey-api/sdk.gen";
import { libraryUpload, type LibraryFile } from "@/features/library/library";
import type { ChatFile } from "@/features/library/files";
import { projectSchema } from "@/features/chat/chat-workspace-api";

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
  const project = projectSchema.parse((await getChatProject({ path: { projectId }, signal })).data);
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
    signal,
  });
}
