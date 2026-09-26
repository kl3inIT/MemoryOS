import { appText, type AppCopy } from "@/i18n/app-text";
import { useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { DirectUploadError } from "@/lib/direct-upload";
import { captureWorkflowFailure } from "@/lib/sentry";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { useSourceFileUpload } from "@/features/sources/shared/use-source-file-upload";
import { useSourceUploadRecovery } from "@/features/sources/upload/source-upload-recovery-context";

export type UploadPhase = "idle" | "preparing" | "uploading" | "finalizing" | "finalize-retry";

/**
 * Adds one file to an existing File Source. A file that reached object storage but was not
 * finalized is remembered, so finalization is retried without uploading it again.
 */
export function useSourceUpload({
  sourceId,
  canUpload,
  setError,
  onAccepted,
}: {
  sourceId: string;
  canUpload: boolean;
  setError: (error: AppCopy | null) => void;
  /** Reads the Source again once a file is accepted. */
  onAccepted: () => Promise<void>;
}) {
  const notify = useActionNotifications();
  const upload = useSourceFileUpload();
  const { pendingFinalize, setPendingFinalize } = useSourceUploadRecovery();
  const activePendingFinalize = pendingFinalize?.sourceId === sourceId ? pendingFinalize : null;
  const [file, setFile] = useState<File | null>(null);
  const [phase, setPhase] = useState<UploadPhase>("idle");
  const [progress, setProgress] = useState(0);
  const controllerRef = useRef<AbortController | null>(null);
  // The file field, emptied once its file is accepted or given up.
  const fileInput = useRef<HTMLInputElement | null>(null);
  const active = useRef(true);
  const busy = phase !== "idle" && phase !== "finalize-retry";

  useLayoutEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
      controllerRef.current?.abort();
      controllerRef.current = null;
    };
  }, []);

  function clearFile() {
    setFile(null);
    if (fileInput.current) fileInput.current.value = "";
  }

  function select(selected: File | null) {
    if (selected && (selected.size === 0 || selected.size > 100 * 1024 * 1024)) {
      setFile(null);
      setError("Choose a file between 1 byte and 100 MiB.");
      if (fileInput.current) fileInput.current.value = "";
      return;
    }
    setError(null);
    setFile(selected);
  }

  async function submit() {
    if (!canUpload || !file || pendingFinalize || controllerRef.current) return;
    setError(null);
    const controller = new AbortController();
    controllerRef.current = controller;
    setPhase("preparing");
    setProgress(0);

    try {
      const stored = await upload.store(sourceId, file, controller.signal, {
        onPhase: (next) => {
          if (next === "uploading") setPhase("uploading");
        },
        onProgress: setProgress,
      });
      setPhase("finalizing");
      try {
        await upload.finalize(stored, controller.signal);
      } catch (cause) {
        if (!controller.signal.aborted) {
          captureWorkflowFailure(cause, {
            workflow: "file-source-upload",
            stage: "finalize",
            failureKind: "api-or-network",
          });
          setPendingFinalize(stored);
          setPhase("finalize-retry");
          setError(
            appText(
              "{{v1}} The file reached object storage; retry finalization without uploading it again.",
              { v1: appText(sourceMutationError(cause, "upload")) },
            ),
          );
          notify({
            tone: "error",
            title: "Finalization failed",
            description: appText(
              "{{v1}} is stored, but finalization could not be confirmed. Retry without uploading again.",
              { v1: file.name },
            ),
          });
          return;
        }
      }
      controller.signal.throwIfAborted();

      clearFile();
      setPhase("idle");
      notify({
        tone: "info",
        title: "Upload accepted",
        description: appText(
          "{{v1}} is registered for processing. Indexing is not yet confirmed.",
          { v1: file.name },
        ),
      });
      await onAccepted();
    } catch (cause) {
      if (!active.current) return;
      setPhase("idle");
      if (!controller.signal.aborted)
        captureWorkflowFailure(cause, {
          workflow: "file-source-upload",
          stage: "upload",
          failureKind: cause instanceof DirectUploadError ? "direct-upload" : "api-or-network",
        });
      const message = controller.signal.aborted
        ? "Upload cancelled. If finalization had started, it may already be accepted; refresh the source to check."
        : cause instanceof DirectUploadError
          ? cause.status === 403
            ? "Object storage rejected the upload. Its authorization may have expired; start the upload again."
            : "Object storage could not accept the file. Check the connection and try again."
          : sourceMutationError(cause, "upload");
      setError(message);
      notify({
        tone: controller.signal.aborted ? "info" : "error",
        title: controller.signal.aborted ? "Upload cancelled" : "Upload failed",
        description: appText("{{v1}}: {{v2}}", { v1: file.name, v2: appText(message) }),
      });
    } finally {
      if (controllerRef.current === controller) controllerRef.current = null;
    }
  }

  async function retryFinalize() {
    if (!canUpload || !activePendingFinalize || controllerRef.current) return;
    const pending = activePendingFinalize;
    setError(null);
    const controller = new AbortController();
    controllerRef.current = controller;
    setPhase("finalizing");
    try {
      await upload.finalize(pending, controller.signal);
      controller.signal.throwIfAborted();
      setPendingFinalize(null);
      clearFile();
      setPhase("idle");
      notify({
        tone: "info",
        title: "Upload accepted",
        description: appText(
          "{{v1}} is registered for processing. Indexing is not yet confirmed.",
          { v1: pending.filename },
        ),
      });
      await onAccepted();
    } catch (cause) {
      if (!active.current) return;
      setPhase("finalize-retry");
      if (!controller.signal.aborted)
        captureWorkflowFailure(cause, {
          workflow: "file-source-upload",
          stage: "finalize-retry",
          failureKind: "api-or-network",
        });
      const message = controller.signal.aborted
        ? "Finalization stopped waiting. It may already be accepted; refresh the source before retrying."
        : appText(
            "{{v1}} The file remains in object storage; retry finalization without uploading it again.",
            { v1: appText(sourceMutationError(cause, "upload")) },
          );
      setError(message);
      notify({
        tone: controller.signal.aborted ? "info" : "error",
        title: controller.signal.aborted ? "Finalization cancelled" : "Finalization failed",
        description: appText("{{v1}}: {{v2}}", { v1: pending.filename, v2: appText(message) }),
      });
    } finally {
      if (controllerRef.current === controller) controllerRef.current = null;
    }
  }

  /** Stops the upload in flight, or gives up a stored file that was never finalized. */
  function cancel() {
    if (activePendingFinalize && !busy) {
      setPendingFinalize(null);
      setPhase("idle");
      clearFile();
      setError("Finalization cancelled. The unfinished object will expire automatically.");
      notify({
        tone: "info",
        title: "Finalization cancelled",
        description: appText("{{v1}}: the unfinished object will expire automatically.", {
          v1: activePendingFinalize.filename,
        }),
      });
    } else {
      controllerRef.current?.abort(new DOMException("Upload cancelled", "AbortError"));
    }
  }

  return {
    upload: {
      file,
      phase,
      progress,
      busy,
      pendingFinalize,
      activePendingFinalize,
      select,
      submit,
      retryFinalize,
      cancel,
    },
    fileInput,
  };
}

export type SourceUpload = ReturnType<typeof useSourceUpload>["upload"];
