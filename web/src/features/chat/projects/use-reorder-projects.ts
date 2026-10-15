import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { actionErrorText } from "@/lib/action-errors";
import { ApiError } from "@/lib/api";
import { reorderChatProjectsMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { invalidateProjects, projectsOptions, type Project } from "./chat-projects-api";

/**
 * Saves the person's order of Projects. The sidebar shows only some of them, so the caller passes the new order of
 * the Projects it shows; they take the places those Projects held in the full list and the others stay where they
 * are, because the API wants every Project exactly once. The new order shows at once; when the write fails, the
 * previous order returns, the list is read again and the person is told.
 */
export function useReorderProjects() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const notify = useActionNotifications();
  const { queryKey } = projectsOptions();
  const reorder = useMutation({
    ...reorderChatProjectsMutation(),
    // Each write carries the whole list, so two quick drags must reach the server in the order they were made.
    scope: { id: "chat-project-order" },
    onMutate: async ({ body }) => {
      await cache.cancelQueries({ queryKey });
      const previous = cache.getQueryData(queryKey);
      const position = new Map(
        (body.projectIds ?? []).map((projectId, index) => [projectId, index]),
      );
      cache.setQueryData(
        queryKey,
        previous?.toSorted((a, b) => (position.get(a.id) ?? 0) - (position.get(b.id) ?? 0)),
      );
      return { previous };
    },
    onError: (error, _variables, context) => {
      cache.setQueryData(queryKey, context?.previous);
      void invalidateProjects(cache);
      notify({
        title:
          error instanceof ApiError && error.status === 409
            ? ui("Danh sách dự án đã thay đổi ở nơi khác. Đã tải lại, hãy sắp xếp lại.")
            : actionErrorText(error),
        tone: "error",
      });
    },
  });

  return (next: Project[]) => {
    const current = cache.getQueryData(queryKey);
    if (!current) return;
    const moved = new Set(next.map((project) => project.id));
    let taken = 0;
    const ordered = current.map((project) =>
      moved.has(project.id) ? (next[taken++] ?? project) : project,
    );
    return reorder.mutateAsync({
      body: { projectIds: ordered.map((project) => project.id) },
      signal: AbortSignal.timeout(30000),
    });
  };
}
