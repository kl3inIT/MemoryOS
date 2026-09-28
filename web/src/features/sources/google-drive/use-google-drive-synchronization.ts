import { appText } from "@/i18n/app-text";
import { useMutation } from "@tanstack/react-query";
import { useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { synchronizeGoogleDriveSourceMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceOperation, SourceSummary } from "@/lib/hey-api/types.gen";
import { captureWorkflowFailure } from "@/lib/sentry";
import { sourceOperationNotice } from "@/features/sources/shared/source-operation-notice";
import { waitForSourceOperation } from "@/features/sources/shared/source-operations";

/**
 * Synchronize now for a Drive Source: the request, then its operation followed until it settles.
 * Following stops, not the work, when the Source, the credential or the person's authority changes.
 */
export function useGoogleDriveSynchronization({
  source,
  resetKey,
  refresh,
}: {
  source: SourceSummary;
  resetKey: string;
  refresh: () => Promise<void>;
}) {
  const notify = useActionNotifications();
  const synchronize = useMutation(synchronizeGoogleDriveSourceMutation());
  const synchronizationController = useRef<AbortController | null>(null);
  const [observing, setObserving] = useState(false);

  useLayoutEffect(() => {
    synchronizationController.current?.abort();
    synchronizationController.current = null;
    const restore = () => {
      if (synchronizationController.current?.signal.aborted) {
        synchronizationController.current = null;
        setObserving(false);
      }
    };
    const clear = () => synchronizationController.current?.abort();
    window.addEventListener("pageshow", restore);
    window.addEventListener("pagehide", clear);
    return () => {
      clear();
      window.removeEventListener("pageshow", restore);
      window.removeEventListener("pagehide", clear);
    };
  }, [resetKey]);

  /** Requests a run unless one is already followed; the caller checks whether it may. */
  async function sync() {
    if (synchronizationController.current) return;
    const controller = new AbortController();
    synchronizationController.current = controller;
    setObserving(true);
    let operation: SourceOperation;
    try {
      operation = await synchronize.mutateAsync({
        path: { sourceId: source.id },
        signal: controller.signal,
      });
      controller.signal.throwIfAborted();
    } catch (cause) {
      if (synchronizationController.current === controller)
        synchronizationController.current = null;
      if (!synchronizationController.current) setObserving(false);
      if (controller.signal.aborted) return;
      throw cause;
    }
    notify({ tone: "info", title: "Synchronization requested", description: source.name });
    void observe(operation, controller);
    await refresh();
  }

  async function observe(operation: SourceOperation, controller: AbortController) {
    try {
      const completed = await waitForSourceOperation(operation, controller.signal);
      const failureKind = completed.errorCode ?? "SOURCE_SYNC_FAILED";
      if (completed.status === "FAILED" && isSystemSynchronizationFailure(failureKind))
        captureWorkflowFailure(new Error("Google Drive synchronization failed"), {
          workflow: "google-drive-sync",
          stage: "operation-complete",
          failureKind,
        });
      notify(
        sourceOperationNotice(completed, {
          subject: source.name,
          titles: {
            succeeded: "Synchronization complete",
            superseded: "Synchronization superseded",
            cancelled: "Synchronization cancelled",
            failed: "Synchronization failed",
          },
          succeeded: appText(
            "{{v1}}: selected content is synchronized. Indexing may still be running.",
            { v1: source.name },
          ),
          failureCode: "SOURCE_SYNC_FAILED",
          failureSubject: false,
        }),
      );
      await refresh();
    } catch (cause) {
      if (!controller.signal.aborted) {
        captureWorkflowFailure(cause, {
          workflow: "google-drive-sync",
          stage: "operation-status",
          failureKind: "status-unavailable",
        });
        notify({
          tone: "error",
          title: "Synchronization status unavailable",
          description: appText(
            "Synchronization may still be running. Refresh the source to check its status.",
          ),
        });
      }
    } finally {
      if (synchronizationController.current === controller)
        synchronizationController.current = null;
      if (!synchronizationController.current) setObserving(false);
    }
  }

  return { sync, observing };
}

function isSystemSynchronizationFailure(errorCode: string) {
  return (
    errorCode.startsWith("SOURCE_STORAGE_") ||
    errorCode === "SOURCE_ACQUISITION_INTERNAL" ||
    errorCode === "SOURCE_GOOGLE_INTERNAL" ||
    errorCode === "SOURCE_GOOGLE_INCOMPLETE" ||
    errorCode === "SOURCE_SYNC_ITEM_FAILURES_EXCEEDED"
  );
}
