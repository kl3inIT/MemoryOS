import { appText, type AppCopy } from "@/i18n/app-text";
import { useMutation } from "@tanstack/react-query";
import { useLayoutEffect, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import {
  reindexSourceItemMutation,
  removeSourceItemMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceItem, SourceOperation } from "@/lib/hey-api/types.gen";
import { captureWorkflowFailure } from "@/lib/sentry";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { sourceOperationNotice } from "@/features/sources/shared/source-operation-notice";
import { waitForSourceOperation } from "@/features/sources/shared/source-operations";

/**
 * Reindexing and removing single files of a Source. Each request is followed until its
 * operation settles; leaving the Source stops following it, not the work.
 */
export function useSourceItemActions({
  sourceId,
  canReindex,
  canRemove,
  setError,
  refresh,
}: {
  sourceId: string;
  canReindex: boolean;
  canRemove: boolean;
  setError: (error: AppCopy | null) => void;
  refresh: () => Promise<void>;
}) {
  const notify = useActionNotifications();
  const reindexItem = useMutation(reindexSourceItemMutation());
  const removeItem = useMutation(removeSourceItemMutation());
  const [reindexControllers] = useState(() => new Map<string, AbortController>());
  const [removalControllers] = useState(() => new Map<string, AbortController>());
  const [reindexingItems, setReindexingItems] = useState<string[]>([]);
  const [removingItems, setRemovingItems] = useState<string[]>([]);

  useLayoutEffect(
    () => () => {
      for (const controller of reindexControllers.values()) controller.abort();
      reindexControllers.clear();
      for (const controller of removalControllers.values()) controller.abort();
      removalControllers.clear();
    },
    [reindexControllers, removalControllers],
  );

  async function reindex(item: SourceItem) {
    if (
      !canReindex ||
      !sourceId ||
      !item.id ||
      reindexControllers.has(item.id) ||
      removalControllers.has(item.id)
    )
      return;
    const controller = new AbortController();
    reindexControllers.set(item.id, controller);
    setReindexingItems((current) => [...current, item.id]);
    setError(null);
    const filename = item.filename ?? "Uploaded file";
    let accepted = false;
    try {
      let operation = await reindexItem.mutateAsync({
        path: { sourceId, itemId: item.id },
        signal: controller.signal,
      });
      controller.signal.throwIfAborted();
      accepted = true;
      notify({ tone: "info", title: "Reindex requested", description: filename });
      void refresh();
      operation = await waitForSourceOperation(operation, controller.signal);
      if (operation.status === "FAILED") {
        const failureKind = operation.errorCode ?? "SOURCE_INDEX_FAILED";
        if (isSystemIndexFailure(failureKind))
          captureWorkflowFailure(new Error("Source indexing operation failed"), {
            workflow: "indexing",
            stage: "operation-complete",
            failureKind,
          });
      }
      notify(
        sourceOperationNotice(operation, {
          subject: filename,
          titles: {
            succeeded: "Reindex complete",
            superseded: "Reindex superseded",
            cancelled: "Reindex cancelled",
            failed: "Reindex failed",
          },
          failureCode: "SOURCE_INDEX_FAILED",
        }),
      );
      await refresh();
    } catch (cause) {
      if (controller.signal.aborted) return;
      captureWorkflowFailure(cause, {
        workflow: "indexing",
        stage: accepted ? "operation-status" : "request",
        failureKind: accepted ? "status-unavailable" : "api-or-network",
      });
      const message = accepted
        ? appText(
            "{{filename}}: processing may still be running. Refresh the source to check its status.",
            { filename },
          )
        : appText(sourceMutationError(cause, "reindex"));
      if (!accepted) setError(message);
      notify({
        tone: "error",
        title: accepted ? "Reindex status unavailable" : "Reindex could not start",
        description: message,
      });
    } finally {
      reindexControllers.delete(item.id);
      if (!controller.signal.aborted)
        setReindexingItems((current) => current.filter((id) => id !== item.id));
    }
  }

  async function remove(item: SourceItem) {
    if (!canRemove || !sourceId || !item.id) throw new Error("Source item is unavailable");
    if (removalControllers.has(item.id) || reindexControllers.has(item.id)) return;
    const controller = new AbortController();
    removalControllers.set(item.id, controller);
    setRemovingItems((current) => [...current, item.id]);
    setError(null);
    try {
      const operation = await removeItem.mutateAsync({
        path: { sourceId, itemId: item.id },
        signal: controller.signal,
      });
      controller.signal.throwIfAborted();
      notify({
        tone: "info",
        title: "Removal requested",
        description: appText("{{v1}}: cleanup is pending.", {
          v1: item.filename ?? appText("Uploaded file"),
        }),
      });
      void observeRemoval(item, operation, controller);
      void refresh();
    } catch (cause) {
      removalControllers.delete(item.id);
      if (controller.signal.aborted) return;
      setRemovingItems((current) => current.filter((id) => id !== item.id));
      notify({
        tone: "error",
        title: "Removal could not start",
        description: appText("{{v1}}: {{v2}}", {
          v1: item.filename ?? appText("Uploaded file"),
          v2: appText(sourceMutationError(cause, "remove-item")),
        }),
      });
      throw cause;
    }
  }

  async function observeRemoval(
    item: SourceItem,
    operation: SourceOperation,
    controller: AbortController,
  ) {
    const filename = item.filename ?? "Uploaded file";
    try {
      const completed = await waitForSourceOperation(operation, controller.signal);
      notify(
        sourceOperationNotice(completed, {
          subject: filename,
          titles: {
            succeeded: "File removed",
            superseded: "Removal superseded",
            cancelled: "Removal cancelled",
            failed: "Removal failed",
          },
          failureCode: "SOURCE_CLEANUP_INTERNAL",
        }),
      );
      void refresh();
    } catch {
      if (!controller.signal.aborted)
        notify({
          tone: "error",
          title: "Removal status unavailable",
          description: appText(
            "{{v1}}: cleanup may still be running. Refresh the source to check its status.",
            { v1: filename },
          ),
        });
    } finally {
      removalControllers.delete(item.id);
      if (!controller.signal.aborted)
        setRemovingItems((current) => current.filter((id) => id !== item.id));
    }
  }

  return {
    reindex,
    remove,
    /** Whether a request for this file is still being followed. */
    working: (itemId: string) => reindexingItems.includes(itemId) || removingItems.includes(itemId),
    busy: reindexItem.isPending || removeItem.isPending,
  };
}

function isSystemIndexFailure(errorCode: string) {
  return (
    errorCode.startsWith("SOURCE_INDEX_") ||
    errorCode.startsWith("SOURCE_STORAGE_") ||
    errorCode === "SOURCE_ACQUISITION_INTERNAL"
  );
}
