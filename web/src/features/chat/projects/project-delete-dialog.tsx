import { useAppTranslation } from "@/i18n/use-app-translation";
import type { RefObject } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { actionErrorText } from "@/lib/action-errors";
import { withRequestTimeout } from "@/lib/api";
import { deleteChatProjectMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";
import { invalidateProjects, type Project } from "./chat-projects-api";

/**
 * Confirms and deletes a Project, from its own page and from the Projects list. The API keeps the conversations and
 * moves them out of the Project, so the conversation lists are refreshed along with the Projects.
 */
export function ProjectDeleteDialog({
  project,
  open,
  onOpenChange,
  restoreFocusRef,
  onDeleted,
}: {
  project: Project;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  restoreFocusRef?: RefObject<HTMLElement | null>;
  /** Runs after the Project is gone and the lists are refreshed. */
  onDeleted?: () => Promise<void> | void;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const refreshSessions = useRefreshChatSessions();
  const remove = useMutation(withRequestTimeout(deleteChatProjectMutation()));
  return (
    <ConfirmDialog
      open={open}
      onOpenChange={onOpenChange}
      restoreFocusRef={restoreFocusRef}
      title={ui("Xóa dự án “{{name}}”?", { name: project.name })}
      description={ui("Các hội thoại được chuyển ra ngoài dự án và vẫn giữ nguyên lịch sử.")}
      confirmLabel={ui("Xóa dự án")}
      pendingLabel={ui("Đang xóa…")}
      errorMessage={actionErrorText}
      onConfirm={async () => {
        await remove.mutateAsync({
          path: { projectId: project.id },
          query: { revision: project.revision },
        });
        await Promise.all([invalidateProjects(cache, project.id), refreshSessions()]);
        await onDeleted?.();
      }}
    />
  );
}
