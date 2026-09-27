import { appText, type AppCopy } from "@/i18n/app-text";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import {
  createFileSourceMutation,
  listSourcesQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { DirectUploadError } from "@/lib/direct-upload";
import { captureWorkflowFailure } from "@/lib/sentry";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import {
  useSourceFileUpload,
  type SourceUploadPhase,
} from "@/features/sources/shared/use-source-file-upload";
import { useSourceUploadRecovery } from "./source-upload-recovery-context";

const uploadPhaseCopy: Record<SourceUploadPhase, string> = {
  hashing: "Preparing file…",
  authorizing: "Preparing upload…",
  uploading: "Uploading file…",
};

export type FileSourceValues = {
  sourceName: string;
  access: "PUBLIC" | "PRIVATE";
  groupIds: ReadonlySet<string>;
};

/**
 * Creating a File Source from a batch of files: the Source is created once, then every file is
 * stored and finalized in turn. A file that reached object storage but was not finalized is
 * remembered, so a retry finishes it without uploading it again and never creates a second Source.
 */
export function useFileSourceCreation({
  scoped,
  canCreate,
  resetKey,
}: {
  scoped: boolean;
  canCreate: boolean;
  /** Changes with the person and their authority; a change stops the work in flight. */
  resetKey: string;
}) {
  const queryClient = useQueryClient();
  const navigate = useNavigate({ from: "/admin/sources/new/file" });
  const notify = useActionNotifications();
  const createSource = useMutation(createFileSourceMutation());
  const upload = useSourceFileUpload();
  const { pendingFinalize, setPendingFinalize } = useSourceUploadRecovery();
  const [sourceId, setSourceId] = useState<string | null>(null);
  const [uploadAccepted, setUploadAccepted] = useState(false);
  const [files, setFiles] = useState<File[]>([]);
  const [error, setError] = useState<AppCopy | null>(null);
  const [phase, setPhase] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [completedCount, setCompletedCount] = useState(0);
  const [currentIndex, setCurrentIndex] = useState(0);
  const controllerRef = useRef<AbortController | null>(null);
  const busy = phase !== null;
  const ownPending = pendingFinalize?.sourceId === sourceId ? pendingFinalize : null;
  const blocked = Boolean(pendingFinalize && !ownPending);
  /** Files can change only before the Source exists and while nothing is pending. */
  const filesLocked = busy || Boolean(pendingFinalize) || uploadAccepted || Boolean(sourceId);

  useEffect(() => () => controllerRef.current?.abort(), []);
  useEffect(() => {
    controllerRef.current?.abort();
  }, [resetKey]);

  /** Takes a new batch; returns it when it is acceptable, so a single file can name the Source. */
  function selectFiles(selected: FileList | null) {
    if (filesLocked || !selected?.length) return null;
    const batch = [...selected];
    if (batch.some((file) => !/\.(pdf|docx|pptx|xlsx|csv|txt|md)$/i.test(file.name))) {
      setError("Choose only PDF, DOCX, PPTX, XLSX, CSV, TXT, or Markdown files.");
      return null;
    }
    if (batch.some((file) => file.size === 0 || file.size > 100 * 1024 * 1024)) {
      setError("Choose files between 1 byte and 100 MiB each.");
      return null;
    }
    setError(null);
    setFiles(batch);
    setCompletedCount(0);
    setCurrentIndex(0);
    return batch;
  }

  async function submit({ sourceName, access, groupIds }: FileSourceValues) {
    const name = sourceName.trim();
    if (!canCreate || controllerRef.current || blocked || files.length === 0 || !name) return;
    const controller = new AbortController();
    controllerRef.current = controller;
    setError(null);
    let targetId = sourceId;
    let stage: "create" | "upload" = targetId ? "upload" : "create";
    let accepted = uploadAccepted;
    let current: File | null = null;
    try {
      if (accepted && targetId) {
        setPhase("Opening source…");
        await navigate({ to: "/admin/sources/$sourceId", params: { sourceId: targetId } });
        return;
      }
      if (!targetId) {
        setPhase("Creating source…");
        // Only group access reads through groups; scoped managers are held to Private.
        const groupAccess = scoped || access === "PRIVATE";
        const created = await createSource.mutateAsync({
          body: {
            name,
            groupIds: groupAccess && groupIds.size > 0 ? [...groupIds] : undefined,
            access: scoped ? "PRIVATE" : access,
          },
          signal: controller.signal,
        });
        targetId = created.id;
        setSourceId(targetId);
        void queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() });
      }
      stage = "upload";
      let receipt = ownPending;
      for (let index = completedCount; index < files.length; index++) {
        const file = files[index];
        if (!file) break;
        current = file;
        setCurrentIndex(index);
        if (!receipt) {
          receipt = await upload.store(targetId, current, controller.signal, {
            onPhase: (next) => setPhase(uploadPhaseCopy[next]),
            onProgress: setProgress,
          });
          setPendingFinalize(receipt);
        }
        setPhase("Finishing upload…");
        await upload.finalize(receipt, controller.signal);
        controller.signal.throwIfAborted();
        receipt = null;
        setPendingFinalize(null);
        setCompletedCount(index + 1);
      }
      accepted = true;
      setUploadAccepted(true);
      await queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() });
      controller.signal.throwIfAborted();
      const [onlyFile, ...otherFiles] = files;
      notify({
        title: "Source created; upload accepted",
        description:
          onlyFile && otherFiles.length === 0
            ? appText(
                "{{v1}} was created. {{v2}} was accepted for indexing; indexing is not complete yet.",
                { v1: name, v2: onlyFile.name },
              )
            : appText(
                "{{v1}} was created. {{v2}} files were accepted for indexing; indexing is not complete yet.",
                { v1: name, v2: files.length },
              ),
        tone: "info",
        surviveNavigation: true,
      });
      await navigate({ to: "/admin/sources/$sourceId", params: { sourceId: targetId } });
    } catch (cause) {
      if (!controller.signal.aborted) {
        captureWorkflowFailure(cause, {
          workflow: "file-source-upload",
          stage,
          failureKind: cause instanceof DirectUploadError ? "direct-upload" : "api-or-network",
        });
        const message = accepted
          ? "Your upload was accepted, but the Source page could not be opened. Open the Source again; do not upload the file again."
          : cause instanceof DirectUploadError
            ? appText(
                "{{v1}} could not be uploaded. Check your connection and retry; your source is already created.",
                { v1: current?.name ?? "" },
              )
            : sourceMutationError(cause, stage);
        setError(message);
        notify({
          title: accepted
            ? "Source created; unable to open"
            : targetId
              ? "Source created; upload needs attention"
              : "Source creation failed",
          description: appText("{{v1}}: {{v2}}", { v1: name, v2: appText(message) }),
          tone: "error",
        });
      }
    } finally {
      controllerRef.current = null;
      setPhase(null);
    }
  }

  return {
    sourceId,
    uploadAccepted,
    files,
    error,
    phase,
    progress,
    completedCount,
    currentIndex,
    busy,
    ownPending,
    pendingFinalize,
    blocked,
    filesLocked,
    selectFiles,
    removeFile(index: number) {
      setFiles((current) => current.filter((_, position) => position !== index));
      setError(null);
    },
    submit,
  };
}

export type FileSourceCreation = ReturnType<typeof useFileSourceCreation>;
