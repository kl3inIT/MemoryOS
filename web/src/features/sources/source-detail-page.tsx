import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "@tanstack/react-router";
import {
  ArrowLeft,
  DatabaseZap,
  FileText,
  LoaderCircle,
  RefreshCw,
  Trash2,
  Upload,
  X,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  deleteSourceMutation,
  finalizeSourceUploadMutation,
  getSourceOptions,
  getSourceQueryKey,
  initiateSourceUploadMutation,
  listSourcesQueryKey,
  reindexSourceItemMutation,
  removeSourceItemMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { getSourceOperation } from "@/lib/hey-api/sdk.gen";
import type { SourceItem } from "@/lib/hey-api/types.gen";
import { SourceActionError, sourceMutationError, sourceStatusMessage } from "./source-errors";
import { DirectUploadError, putAuthorizedObject, sha256 } from "./direct-upload";
import { SourceStatusBadge } from "./source-status-badge";
import { findSourceProvider } from "./source-provider-catalog";
import { useSourceUploadRecovery } from "./source-upload-recovery-context";

const terminalOperationStatuses: Record<string, true> = {
  SUCCEEDED: true,
  SUPERSEDED: true,
  FAILED: true,
};
type UploadPhase = "idle" | "preparing" | "uploading" | "finalizing" | "finalize-retry";

export function SourceDetailPage() {
  const { sourceId: selectedId } = useParams({
    from: "/_authenticated/admin/sources/$sourceId",
  });
  const navigate = useNavigate({ from: "/admin/sources/$sourceId" });
  const queryClient = useQueryClient();
  const { pendingFinalize, setPendingFinalize } = useSourceUploadRecovery();
  const activePendingFinalize = pendingFinalize?.sourceId === selectedId ? pendingFinalize : null;
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploadPhase, setUploadPhase] = useState<UploadPhase>("idle");
  const [uploadProgress, setUploadProgress] = useState(0);
  const [cleanupPending, setCleanupPending] = useState(false);
  const uploadController = useRef<AbortController | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);
  const cleanupController = useRef<AbortController | null>(null);

  useEffect(
    () => () => {
      uploadController.current?.abort();
      uploadController.current = null;
      cleanupController.current?.abort();
      cleanupController.current = null;
    },
    [],
  );

  const sourceQuery = useQuery({
    ...getSourceOptions({ path: { sourceId: selectedId } }),
    retry: false,
    refetchInterval: (query) => (query.state.data?.source?.pendingWork ? 1_500 : false),
  });

  const initiateUpload = useMutation(initiateSourceUploadMutation());
  const finalizeUpload = useMutation(finalizeSourceUploadMutation());
  const reindexItem = useMutation(reindexSourceItemMutation());
  const removeItem = useMutation(removeSourceItemMutation());
  const deleteSource = useMutation(deleteSourceMutation());

  async function refresh(sourceId?: string) {
    await queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() });
    if (sourceId) {
      await queryClient.invalidateQueries({ queryKey: getSourceQueryKey({ path: { sourceId } }) });
    }
  }

  async function submitFile() {
    if (!selectedId || !file || pendingFinalize) return;
    setError(null);
    uploadController.current?.abort();
    const controller = new AbortController();
    uploadController.current = controller;
    setUploadPhase("preparing");
    setUploadProgress(0);

    try {
      const checksum = await sha256(file, controller.signal);
      const authorization = await initiateUpload.mutateAsync({
        path: { sourceId: selectedId },
        headers: sameOriginMutationHeaders,
        body: {
          filename: file.name,
          mediaType: file.type || "application/octet-stream",
          sizeBytes: file.size,
          sha256: checksum,
        },
        signal: controller.signal,
      });

      setUploadPhase("uploading");
      await putAuthorizedObject(authorization, file, controller.signal, setUploadProgress);
      setUploadPhase("finalizing");
      try {
        await finalizeUpload.mutateAsync({
          path: { sourceId: selectedId, uploadId: authorization.uploadId },
          headers: sameOriginMutationHeaders,
          signal: controller.signal,
        });
      } catch (cause) {
        if (!controller.signal.aborted) {
          setPendingFinalize({
            sourceId: selectedId,
            uploadId: authorization.uploadId,
            filename: file.name,
          });
          setUploadPhase("finalize-retry");
          setError(
            `${sourceMutationError(cause, "upload")} The file reached object storage; retry finalization without uploading it again.`,
          );
          return;
        }
      }
      controller.signal.throwIfAborted();

      setFile(null);
      if (fileInput.current) fileInput.current.value = "";
      setUploadPhase("idle");
      await refresh(selectedId);
    } catch (cause) {
      setUploadPhase("idle");
      if (controller.signal.aborted) {
        setError("Upload cancelled. The unfinished object will expire automatically.");
      } else if (cause instanceof DirectUploadError) {
        setError(
          cause.status === 403
            ? "Object storage rejected the upload. Its authorization may have expired; start the upload again."
            : "Object storage could not accept the file. Check the connection and try again.",
        );
      } else {
        setError(sourceMutationError(cause, "upload"));
      }
    } finally {
      if (uploadController.current === controller) uploadController.current = null;
    }
  }

  async function retryFinalize() {
    if (!activePendingFinalize) return;
    const pending = activePendingFinalize;
    setError(null);
    const controller = new AbortController();
    uploadController.current = controller;
    setUploadPhase("finalizing");
    try {
      await finalizeUpload.mutateAsync({
        path: { sourceId: pending.sourceId, uploadId: pending.uploadId },
        headers: sameOriginMutationHeaders,
        signal: controller.signal,
      });
      setPendingFinalize(null);
      setFile(null);
      if (fileInput.current) fileInput.current.value = "";
      setUploadPhase("idle");
      await refresh(pending.sourceId);
    } catch (cause) {
      if (controller.signal.aborted) {
        setUploadPhase("finalize-retry");
        setError("Finalization cancelled. Retry to finish without uploading the file again.");
      } else {
        setUploadPhase("finalize-retry");
        setError(
          `${sourceMutationError(cause, "upload")} The file remains in object storage; retry finalization without uploading it again.`,
        );
      }
    } finally {
      if (uploadController.current === controller) uploadController.current = null;
    }
  }

  async function reindex(item: SourceItem) {
    if (!selectedId || !item.id) return;
    setError(null);
    try {
      await reindexItem.mutateAsync({
        path: { sourceId: selectedId, itemId: item.id },
        headers: sameOriginMutationHeaders,
      });
      await refresh(selectedId);
    } catch (cause) {
      setError(sourceMutationError(cause, "reindex"));
    }
  }

  async function removeSelectedItem(item: SourceItem) {
    if (!selectedId || !item.id) throw new Error("Source item is unavailable");
    setError(null);
    await removeItem.mutateAsync({
      path: { sourceId: selectedId, itemId: item.id },
      headers: sameOriginMutationHeaders,
    });
    await refresh(selectedId);
  }

  async function waitForCleanup(operationId: string, signal: AbortSignal) {
    for (let attempt = 0; attempt < 120; attempt += 1) {
      const { data: operation } = await getSourceOperation({
        path: { operationId },
        signal,
        throwOnError: true,
      });
      if (Object.hasOwn(terminalOperationStatuses, operation.status)) return operation;
      await abortableDelay(1_000, signal);
    }
    throw new SourceActionError("cleanup-timeout");
  }

  async function deleteSelectedSource() {
    if (!selectedId) throw new Error("Source is unavailable");
    setError(null);
    cleanupController.current?.abort();
    const controller = new AbortController();
    cleanupController.current = controller;
    setCleanupPending(true);
    try {
      const operation = await deleteSource.mutateAsync({
        path: { sourceId: selectedId },
        headers: sameOriginMutationHeaders,
      });
      if (!operation.id) throw new SourceActionError("invalid-cleanup-response");
      await refresh(selectedId);
      const completed = await waitForCleanup(operation.id, controller.signal);
      if (completed.status === "FAILED") throw new SourceActionError("cleanup-failed");
      await navigate({ to: "/admin", replace: true });
    } finally {
      if (cleanupController.current === controller) {
        cleanupController.current = null;
        setCleanupPending(false);
      }
    }
  }

  const detail = sourceQuery.data;
  const uploadBusy = uploadPhase !== "idle" && uploadPhase !== "finalize-retry";
  const busy =
    uploadBusy || reindexItem.isPending || removeItem.isPending || deleteSource.isPending;

  return (
    <section className="mx-auto w-full max-w-5xl px-5 py-8 sm:px-8 sm:py-12">
      <Link
        to="/admin"
        className="inline-flex items-center gap-2 font-secondary-action text-content-secondary transition-colors hover:text-content-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        Sources
      </Link>

      {error ? (
        <p
          role="alert"
          className="mt-5 rounded-lg bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
        >
          {error}
        </p>
      ) : null}

      {pendingFinalize && !activePendingFinalize ? (
        <div className="mt-5 flex flex-col gap-3 rounded-xl border border-border-subtle bg-surface-raised px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-sm text-content-secondary">
            {pendingFinalize.filename} is stored and still needs finalization.
          </p>
          <Button asChild size="sm" prominence="secondary">
            <Link to="/admin/sources/$sourceId" params={{ sourceId: pendingFinalize.sourceId }}>
              Return to pending upload
            </Link>
          </Button>
        </div>
      ) : null}

      <main className="mt-6 min-w-0 overflow-hidden rounded-2xl border border-border-subtle bg-surface-raised">
        {sourceQuery.isPending && !detail ? (
          <div className="px-6 py-16">
            <LoadingLabel label="Loading source" />
          </div>
        ) : sourceQuery.isError || !detail?.source ? (
          <div className="px-6 py-16">
            <EmptyState title="Source unavailable" detail="It may have completed deletion." />
          </div>
        ) : (
          <div>
            <div className="flex flex-col gap-5 border-b border-border-subtle p-5 sm:flex-row sm:items-start sm:justify-between sm:p-6">
              <div>
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className="font-heading-h3 text-content-primary">{detail.source.name}</h1>
                  <SourceStatusBadge status={detail.source.status} />
                </div>
                <p className="mt-2 font-secondary-body text-content-muted">
                  {findSourceProvider(detail.source.type)?.name ?? detail.source.type} ·{" "}
                  {detail.source.access} · {detail.source.documentCount ?? 0} indexed documents
                </p>
                {detail.source.errorCode ? (
                  <p className="mt-2 text-sm text-status-danger-content">
                    {sourceStatusMessage(detail.source.errorCode)}
                  </p>
                ) : null}
              </div>
              <ConfirmDialog
                trigger={
                  <Button
                    tone="danger"
                    prominence="secondary"
                    disabled={busy || cleanupPending || detail.source.status === "DELETING"}
                  >
                    <Trash2 />
                    Delete source
                  </Button>
                }
                title={`Delete ${detail.source.name}?`}
                description={`Deleting “${detail.source.name}” makes every indexed document from this source unavailable. Cleanup continues asynchronously and cannot be undone.`}
                confirmLabel="Delete source"
                pendingLabel="Deleting source"
                onConfirm={deleteSelectedSource}
                errorMessage={(cause) => sourceMutationError(cause, "delete-source")}
              />
            </div>

            <form
              className="border-b border-border-subtle bg-surface-subtle p-5 sm:p-6"
              onSubmit={(event) => {
                event.preventDefault();
                void (activePendingFinalize ? retryFinalize() : submitFile());
              }}
            >
              <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
                <label className="min-w-0 flex-1">
                  <span className="sr-only">Choose PDF, DOCX, TXT, or Markdown file</span>
                  <Input
                    ref={fileInput}
                    type="file"
                    accept=".pdf,.docx,.txt,.md,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    disabled={uploadPhase !== "idle" || Boolean(pendingFinalize)}
                    onChange={(event) => setFile(event.target.files?.[0] ?? null)}
                    className="bg-surface-raised pl-0 file:h-full file:border-r file:border-border-default file:bg-surface-subtle file:px-3"
                  />
                </label>
                <Button
                  type="submit"
                  pending={uploadBusy}
                  disabled={
                    (!file && !activePendingFinalize) ||
                    Boolean(pendingFinalize && !activePendingFinalize) ||
                    busy ||
                    detail.source.status === "DELETING"
                  }
                >
                  <Upload />
                  {activePendingFinalize ? "Retry finalization" : "Upload file"}
                </Button>
                {uploadPhase !== "idle" || activePendingFinalize ? (
                  <Button
                    type="button"
                    prominence="secondary"
                    onClick={() => {
                      if (activePendingFinalize) {
                        setPendingFinalize(null);
                        setUploadPhase("idle");
                        setFile(null);
                        if (fileInput.current) fileInput.current.value = "";
                        setError(
                          "Finalization cancelled. The unfinished object will expire automatically.",
                        );
                      } else {
                        uploadController.current?.abort(
                          new DOMException("Upload cancelled", "AbortError"),
                        );
                      }
                    }}
                  >
                    <X />
                    Cancel
                  </Button>
                ) : null}
              </div>
              {uploadPhase !== "idle" || activePendingFinalize ? (
                <div className="mt-3" aria-live="polite">
                  <div className="flex items-center justify-between gap-3 font-secondary-body text-content-secondary">
                    <span>
                      {uploadPhase === "preparing"
                        ? "Calculating SHA-256 before authorization"
                        : uploadPhase === "uploading"
                          ? "Uploading directly to object storage"
                          : uploadPhase === "finalizing"
                            ? "Verifying and registering the stored file"
                            : `${activePendingFinalize?.filename ?? "File"} is stored but not finalized`}
                    </span>
                    {uploadPhase === "uploading" ? <span>{uploadProgress}%</span> : null}
                  </div>
                  {uploadPhase === "uploading" ? (
                    <div
                      role="progressbar"
                      aria-label="Direct upload progress"
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-valuenow={uploadProgress}
                      className="mt-2 h-1 overflow-hidden rounded-full bg-border-default"
                    >
                      <div
                        className="h-full rounded-full bg-content-primary transition-[width] duration-150"
                        style={{ width: `${uploadProgress}%` }}
                      />
                    </div>
                  ) : null}
                </div>
              ) : null}
            </form>

            <div className="p-5 sm:p-6">
              <div className="mb-4 flex items-center justify-between">
                <h3 className="font-secondary-action text-content-primary">Files</h3>
                {detail.source.pendingWork ? <LoadingLabel label="Processing" /> : null}
              </div>
              {(detail.items ?? []).length === 0 ? (
                <EmptyState
                  title="No files yet"
                  detail="Upload one supported file to start indexing."
                />
              ) : (
                <div className="divide-y divide-border-subtle overflow-hidden rounded-xl border border-border-subtle">
                  {(detail.items ?? []).map((item) => (
                    <div
                      key={item.id}
                      className="flex flex-col gap-3 bg-surface-raised p-4 sm:flex-row sm:items-center"
                    >
                      <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-subtle text-content-secondary">
                        <FileText className="size-4" aria-hidden="true" />
                      </span>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium text-content-primary">
                          {item.filename ?? "Uploaded file"}
                        </p>
                        <p className="mt-1 font-secondary-body text-content-muted">
                          {formatBytes(item.sizeBytes ?? 0)} · {item.status ?? "PENDING"}
                        </p>
                        {item.errorCode ? (
                          <p className="mt-1 text-xs text-status-danger-content">
                            {sourceStatusMessage(item.errorCode)}
                          </p>
                        ) : null}
                      </div>
                      <div className="flex gap-2">
                        <Button
                          prominence="secondary"
                          size="sm"
                          disabled={busy || item.status === "DELETING"}
                          onClick={() => void reindex(item)}
                        >
                          <RefreshCw /> Reindex
                        </Button>
                        <ConfirmDialog
                          trigger={
                            <Button
                              tone="danger"
                              prominence="secondary"
                              size="sm"
                              disabled={busy || item.status === "DELETING"}
                            >
                              <Trash2 /> Remove
                            </Button>
                          }
                          title={`Remove ${item.filename ?? "uploaded file"}?`}
                          description={`Removing “${item.filename ?? "this file"}” makes its indexed document unavailable. Cleanup continues asynchronously.`}
                          confirmLabel="Remove file"
                          pendingLabel="Removing file"
                          onConfirm={() => removeSelectedItem(item)}
                          errorMessage={(cause) => sourceMutationError(cause, "remove-item")}
                        />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      </main>
    </section>
  );
}

function LoadingLabel({ label }: { label: string }) {
  return (
    <span className="inline-flex items-center gap-2 text-sm text-content-muted">
      <LoaderCircle className="size-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />
      {label}
    </span>
  );
}

function EmptyState({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="py-8 text-center">
      <span className="mx-auto mb-4 grid size-10 place-items-center rounded-xl border border-border-subtle bg-surface-subtle text-content-secondary">
        <DatabaseZap className="size-5" aria-hidden="true" />
      </span>
      <h2 className="font-heading-h3 text-content-primary">{title}</h2>
      <p className="mx-auto mt-2 max-w-md font-main-ui-body text-content-muted">{detail}</p>
    </div>
  );
}

function abortableDelay(milliseconds: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) {
      reject(signal.reason);
      return;
    }
    const timeout = window.setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve();
    }, milliseconds);
    function abort() {
      window.clearTimeout(timeout);
      reject(signal.reason);
    }
    signal.addEventListener("abort", abort, { once: true });
  });
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}
