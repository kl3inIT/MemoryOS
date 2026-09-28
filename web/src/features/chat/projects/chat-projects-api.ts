import { queryOptions, type QueryClient } from "@tanstack/react-query";
import { allPages } from "@/lib/all-pages";
import {
  getChatProjectQueryKey,
  listChatProjectsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { listChatProjects } from "@/lib/hey-api/sdk.gen";
import type { ProjectView } from "@/lib/hey-api/types.gen";

/**
 * A Project as the API sends it. The published contract marks every field of `ProjectView` optional,
 * although the API always sends them; the view is narrowed once here.
 */
export function projectOf({
  id,
  revision,
  updatedAt,
  name = "",
  description = "",
  instructions = "",
  iconName,
  fileIds = [],
}: ProjectView) {
  if (id === undefined || revision === undefined || updatedAt === undefined) {
    throw new TypeError("A Project view carries its id, revision and update time.");
  }
  return { id, revision, updatedAt, name, description, instructions, iconName, fileIds };
}
export type Project = ReturnType<typeof projectOf>;

/** Every Project of the actor, for the sidebar, pickers and the Projects page. */
export function projectsOptions() {
  return queryOptions({
    queryKey: [...listChatProjectsQueryKey(), "all"] as const,
    queryFn: ({ signal }) =>
      allPages(async (offset) =>
        (await listChatProjects({ query: { offset, limit: 100 }, signal })).data.map(projectOf),
      ),
  });
}

/** Refreshes every read of Projects after a change: the lists and, when given, the changed Project. */
export function invalidateProjects(cache: QueryClient, projectId?: string) {
  return Promise.all([
    cache.invalidateQueries({ queryKey: listChatProjectsQueryKey() }),
    projectId === undefined
      ? undefined
      : cache.invalidateQueries({ queryKey: getChatProjectQueryKey({ path: { projectId } }) }),
  ]);
}
