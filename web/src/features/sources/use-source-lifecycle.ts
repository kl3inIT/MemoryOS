import { appText, type AppCopy } from "@/i18n/app-text";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import {
  deleteSourceMutation,
  pauseSourceMutation,
  resumeSourceMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceOperation } from "@/lib/hey-api/types.gen";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { sourceOperationNotice } from "@/features/sources/shared/source-operation-notice";
import { waitForSourceOperation } from "@/features/sources/shared/source-operations";

/**
 * Pausing, resuming and deleting a Source. Deletion is followed until its cleanup settles, and a
 * deleted Source returns the person to the Source list.
 */
export function useSourceLifecycle({
  sourceId,
  sourceName: name,
  canDelete,
  setError,
  refresh,
}: {
  sourceId: string;
  sourceName: string | undefined;
  canDelete: boolean;
  setError: (error: AppCopy | null) => void;
  refresh: () => Promise<void>;
}) {
  const navigate = useNavigate();
  const notify = useActionNotifications();
  const deleteSource = useMutation(deleteSourceMutation());
  const pauseSource = useMutation(pauseSourceMutation());
  const resumeSource = useMutation(resumeSourceMutation());
  const [cleanupPending, setCleanupPending] = useState(false);
  const cleanupController = useRef<AbortController | null>(null);
  const sourceName = name ?? "Source";

  useLayoutEffect(
    () => () => {
      cleanupController.current?.abort();
      cleanupController.current = null;
    },
    [],
  );

  async function remove() {
    if (!canDelete || !sourceId) throw new Error("Source is unavailable");
    if (cleanupController.current) return;
    setError(null);
    const controller = new AbortController();
    cleanupController.current = controller;
    setCleanupPending(true);
    try {
      const operation = await deleteSource.mutateAsync({
        path: { sourceId },
        signal: controller.signal,
      });
      controller.signal.throwIfAborted();
      notify({
        tone: "info",
        title: "Source deletion requested",
        description: appText("{{v1}}: cleanup is pending.", { v1: sourceName }),
      });
      void observeDeletion(operation, controller);
      void refresh();
    } catch (cause) {
      if (cleanupController.current === controller) cleanupController.current = null;
      if (controller.signal.aborted) return;
      setCleanupPending(false);
      notify({
        tone: "error",
        title: "Deletion could not start",
        description: appText("{{v1}}: {{v2}}", {
          v1: sourceName,
          v2: appText(sourceMutationError(cause, "delete-source")),
        }),
      });
      throw cause;
    }
  }

  async function observeDeletion(operation: SourceOperation, controller: AbortController) {
    try {
      if (!operation.id) throw new Error("Deletion operation is unavailable");
      const completed = await waitForSourceOperation(operation, controller.signal);
      if (completed.status === "SUCCEEDED") {
        notify({
          tone: "success",
          title: "Source deleted",
          description: sourceName,
          surviveNavigation: true,
        });
        await navigate({ to: "/admin", replace: true });
        return;
      }
      notify(
        sourceOperationNotice(completed, {
          subject: sourceName,
          titles: {
            succeeded: "Source deleted",
            superseded: "Source deletion superseded",
            cancelled: "Source deletion cancelled",
            failed: "Source deletion failed",
          },
          superseded: appText(
            "{{v1}}: this request was replaced by newer work. Refresh before trying again.",
            { v1: sourceName },
          ),
          failureCode: "SOURCE_CLEANUP_INTERNAL",
        }),
      );
      void refresh();
    } catch {
      if (!controller.signal.aborted)
        notify({
          tone: "error",
          title: "Deletion status unavailable",
          description: appText(
            "{{v1}}: cleanup may still be running. Refresh the source to check its status.",
            { v1: sourceName },
          ),
        });
    } finally {
      if (cleanupController.current === controller) {
        cleanupController.current = null;
        setCleanupPending(false);
      }
    }
  }

  /** Resumes a paused Source, or pauses a running one. */
  async function togglePause(paused: boolean) {
    setError(null);
    try {
      await (paused ? resumeSource : pauseSource).mutateAsync({
        path: { sourceId },
      });
      notify({
        tone: "success",
        title: paused ? "Source resumed" : "Source paused",
        description: appText("{{v1}}: {{v2}}", {
          v1: sourceName,
          v2: paused
            ? "Synchronization and indexing continue from the retained state."
            : "New synchronization and indexing work is blocked; in-flight work is draining.",
        }),
      });
      void refresh();
    } catch (cause) {
      notify({
        tone: "error",
        title: paused ? "Resume failed" : "Pause failed",
        description: appText("{{v1}}: {{v2}}", {
          v1: sourceName,
          v2: appText(sourceMutationError(cause, "reindex")),
        }),
      });
    }
  }

  return {
    remove,
    togglePause,
    deleting: deleteSource.isPending || cleanupPending,
    pausing: pauseSource.isPending || resumeSource.isPending,
  };
}
