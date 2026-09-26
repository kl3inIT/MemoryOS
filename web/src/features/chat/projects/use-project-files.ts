import { useMutation, useQueries, useQueryClient } from "@tanstack/react-query";
import { isNotFound } from "@/lib/api";
import {
  getChatFileOptions,
  updateChatProjectMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatFileResponse } from "@/lib/hey-api/types.gen";
import { invalidateProjects, type Project } from "./chat-projects-api";

/** A Project file as the list shows it; the published contract marks its fields optional. */
function projectFileOf({ id, filename = "", mediaType }: ChatFileResponse) {
  return id === undefined
    ? null
    : { id, filename, mediaType: mediaType ?? "application/octet-stream" };
}

/**
 * The files of a Project, each read on its own so a file that is gone shows as unavailable instead of failing the
 * list, and the change that replaces the Project's file list.
 */
export function useProjectFiles(project: Project) {
  const cache = useQueryClient();
  const files = useQueries({
    queries: project.fileIds.map((fileId) => ({
      ...getChatFileOptions({ path: { fileId } }),
      select: projectFileOf,
      retry: (failures: number, error: unknown) => !isNotFound(error) && failures < 3,
    })),
    combine: (results) => ({
      data: results.every((result) => result.isSuccess || isNotFound(result.error))
        ? project.fileIds.map((fileId, index) => ({ fileId, file: results[index]?.data ?? null }))
        : undefined,
      isError: results.some((result) => result.isError && !isNotFound(result.error)),
    }),
  });
  const update = useMutation({
    ...updateChatProjectMutation(),
    onSuccess: () => invalidateProjects(cache, project.id),
  });
  const setFiles = (fileIds: string[]) =>
    update.mutate({
      path: { projectId: project.id },
      query: { revision: project.revision },
      body: {
        name: project.name,
        description: project.description,
        instructions: project.instructions,
        fileIds,
      },
    });
  return { files, setFiles, saving: update.isPending, saveError: update.error };
}
