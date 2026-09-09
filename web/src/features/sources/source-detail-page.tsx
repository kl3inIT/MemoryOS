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
import { useLayoutEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { TablePagination } from "@/components/ui/table-pagination";
import { HelpPopover } from "@/components/ui/help-popover";
import { Select } from "@/components/ui/select";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  deleteSourceMutation,
  finalizeSourceUploadMutation,
  getSourceOptions,
  getSourceQueryKey,
  initiateSourceUploadMutation,
  listSourceItemsOptions,
  listSourceItemsQueryKey,
  listSourcesQueryKey,
  reindexSourceItemMutation,
  removeSourceItemMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceItem, SourceOperation } from "@/lib/hey-api/types.gen";
import { sourceMutationError, sourceStatusMessage } from "./source-errors";
import { DirectUploadError, putAuthorizedObject, sha256 } from "./direct-upload";
import { SourceSummaryCard } from "./source-summary-card";
import { findSourceProvider } from "./source-provider-catalog";
import { useSourceUploadRecovery } from "./source-upload-recovery-context";
import { GoogleDrivePanel } from "./google-drive-panel";
import { waitForSourceOperation } from "./source-operations";
import { SourceItemHistory } from "./source-item-history";
import { SourceRunHistory } from "./source-run-history";
import { HistoryTime } from "./source-history-presentation";
import { SourceGroupsSection } from "./source-groups-section";
import { SourceSectionIcon } from "./source-section-icon";

type UploadPhase = "idle" | "preparing" | "uploading" | "finalizing" | "finalize-retry";

export function SourceDetailPage() {
  const { sourceId } = useParams({
    from: "/_authenticated/admin/sources/$sourceId",
  });
  return <SourceDetailContent key={sourceId} selectedId={sourceId} />;
}

function SourceDetailContent({ selectedId }: { selectedId: string }) {
  const navigate = useNavigate({ from: "/admin/sources/$sourceId" });
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const [reindexControllers] = useState(() => new Map<string, AbortController>());
  const [reindexingItems, setReindexingItems] = useState<string[]>([]);
  const [removalControllers] = useState(() => new Map<string, AbortController>());
  const [removingItems, setRemovingItems] = useState<string[]>([]);
  const active = useRef(true);
  const { pendingFinalize, setPendingFinalize } = useSourceUploadRecovery();
  const activePendingFinalize = pendingFinalize?.sourceId === selectedId ? pendingFinalize : null;
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploadPhase, setUploadPhase] = useState<UploadPhase>("idle");
  const [uploadProgress, setUploadProgress] = useState(0);
  const [cleanupPending, setCleanupPending] = useState(false);
  const [driveBusy, setDriveBusy] = useState(false);
  const [filesSize, setFilesSize] = useState(25);
  const [cursor, setCursor] = useState<string>();
  const [previous, setPrevious] = useState<Array<string | undefined>>([]);
  const filesHeading = useRef<HTMLHeadingElement | null>(null);
  const uploadController = useRef<AbortController | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);
  const backLinkRef = useRef<HTMLAnchorElement>(null);
  const cleanupController = useRef<AbortController | null>(null);

  useLayoutEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
      uploadController.current?.abort();
      uploadController.current = null;
      cleanupController.current?.abort();
      cleanupController.current = null;
      for (const controller of reindexControllers.values()) controller.abort();
      reindexControllers.clear();
      for (const controller of removalControllers.values()) controller.abort();
      removalControllers.clear();
    };
  }, [reindexControllers, removalControllers]);

  const sourceQuery = useQuery({
    ...getSourceOptions({ path: { sourceId: selectedId } }),
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.pendingWork
        ? 1_500
        : query.state.data?.type === "GOOGLE_DRIVE"
          ? 5_000
          : false,
  });
  const itemsQuery = useQuery({
    ...listSourceItemsOptions({
      path: { sourceId: selectedId },
      query: { size: filesSize, cursor },
    }),
    enabled: Boolean(sourceQuery.data),
    retry: false,
    staleTime: 0,
    refetchInterval: (query) =>
      sourceQuery.data?.pendingWork ||
      query.state.data?.items.some(
        (item) =>
          item.status === "PENDING" ||
          item.status === "DELETING" ||
          item.searchStatus === "INDEXING",
      )
        ? 1_500
        : sourceQuery.data?.type === "GOOGLE_DRIVE"
          ? 5_000
          : false,
  });
  const filesTotalPages = itemsQuery.data
    ? Math.ceil(itemsQuery.data.totalItems / filesSize)
    : undefined;
  if (filesTotalPages !== undefined && previous.length >= Math.max(filesTotalPages, 1)) {
    setCursor(undefined);
    setPrevious([]);
  }

  const initiateUpload = useMutation(initiateSourceUploadMutation());
  const finalizeUpload = useMutation(finalizeSourceUploadMutation());
  const reindexItem = useMutation(reindexSourceItemMutation());
  const removeItem = useMutation(removeSourceItemMutation());
  const deleteSource = useMutation(deleteSourceMutation());

  async function refresh(sourceId?: string, resetFiles = false) {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }),
      ...(sourceId
        ? [
            queryClient.invalidateQueries({ queryKey: getSourceQueryKey({ path: { sourceId } }) }),
            queryClient.invalidateQueries({
              queryKey: listSourceItemsQueryKey({ path: { sourceId } }),
              refetchType: resetFiles ? "none" : "active",
            }),
          ]
        : []),
    ]);
    if (resetFiles && sourceId && active.current) {
      setCursor(undefined);
      setPrevious([]);
      await queryClient.refetchQueries({
        queryKey: listSourceItemsQueryKey({ path: { sourceId } }),
        type: "active",
      });
    }
  }

  async function refreshSource() {
    try {
      await sourceQuery.refetch({ throwOnError: true });
      if (active.current)
        notify({ tone: "success", title: "Source refreshed", description: detail?.name });
    } catch (cause) {
      if (active.current)
        notify({
          tone: "error",
          title: "Source refresh failed",
          description: sourceMutationError(cause, "reindex"),
        });
    }
  }

  async function submitFile() {
    if (!canUpload || !selectedId || !file || pendingFinalize || uploadController.current) return;
    setError(null);
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
          notify({
            tone: "error",
            title: "Finalization failed",
            description: `${file.name} is stored, but finalization could not be confirmed. Retry without uploading again.`,
          });
          return;
        }
      }
      controller.signal.throwIfAborted();

      setFile(null);
      if (fileInput.current) fileInput.current.value = "";
      setUploadPhase("idle");
      notify({
        tone: "info",
        title: "Upload accepted",
        description: `${file.name} is registered for processing. Indexing is not yet confirmed.`,
      });
      await refresh(selectedId, true);
    } catch (cause) {
      if (!active.current) return;
      setUploadPhase("idle");
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
        description: `${file.name}: ${message}`,
      });
    } finally {
      if (uploadController.current === controller) uploadController.current = null;
    }
  }

  async function retryFinalize() {
    if (!canUpload || !activePendingFinalize || uploadController.current) return;
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
      controller.signal.throwIfAborted();
      setPendingFinalize(null);
      setFile(null);
      if (fileInput.current) fileInput.current.value = "";
      setUploadPhase("idle");
      notify({
        tone: "info",
        title: "Upload accepted",
        description: `${pending.filename} is registered for processing. Indexing is not yet confirmed.`,
      });
      await refresh(pending.sourceId, true);
    } catch (cause) {
      if (!active.current) return;
      setUploadPhase("finalize-retry");
      const message = controller.signal.aborted
        ? "Finalization stopped waiting. It may already be accepted; refresh the source before retrying."
        : `${sourceMutationError(cause, "upload")} The file remains in object storage; retry finalization without uploading it again.`;
      setError(message);
      notify({
        tone: controller.signal.aborted ? "info" : "error",
        title: controller.signal.aborted ? "Finalization cancelled" : "Finalization failed",
        description: `${pending.filename}: ${message}`,
      });
    } finally {
      if (uploadController.current === controller) uploadController.current = null;
    }
  }

  async function reindex(item: SourceItem) {
    if (
      !canReindex ||
      !selectedId ||
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
        path: { sourceId: selectedId, itemId: item.id },
        headers: sameOriginMutationHeaders,
        signal: controller.signal,
      });
      controller.signal.throwIfAborted();
      accepted = true;
      notify({ tone: "info", title: "Reindex requested", description: filename });
      void refresh(selectedId);
      operation = await waitForSourceOperation(operation, controller.signal);
      if (operation.status === "SUCCEEDED") {
        notify({ tone: "success", title: "Reindex complete", description: filename });
      } else if (operation.status === "SUPERSEDED") {
        notify({
          tone: "info",
          title: "Reindex superseded",
          description: `${filename}: this request was replaced by newer work.`,
        });
      } else {
        notify({
          tone: "error",
          title: "Reindex failed",
          description: `${filename}: ${sourceStatusMessage(operation.errorCode ?? "SOURCE_INDEX_FAILED")}`,
        });
      }
      await refresh(selectedId);
    } catch (cause) {
      if (controller.signal.aborted) return;
      const message = accepted
        ? `${filename}: processing may still be running. Refresh the source to check its status.`
        : sourceMutationError(cause, "reindex");
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

  async function removeSelectedItem(item: SourceItem) {
    if (!canRemoveItems || !selectedId || !item.id) throw new Error("Source item is unavailable");
    if (removalControllers.has(item.id) || reindexControllers.has(item.id)) return;
    const controller = new AbortController();
    removalControllers.set(item.id, controller);
    setRemovingItems((current) => [...current, item.id]);
    setError(null);
    try {
      const operation = await removeItem.mutateAsync({
        path: { sourceId: selectedId, itemId: item.id },
        headers: sameOriginMutationHeaders,
        signal: controller.signal,
      });
      controller.signal.throwIfAborted();
      notify({
        tone: "info",
        title: "Removal requested",
        description: `${item.filename ?? "Uploaded file"}: cleanup is pending.`,
      });
      void observeRemoval(item, operation, controller);
      void refresh(selectedId);
    } catch (cause) {
      removalControllers.delete(item.id);
      if (controller.signal.aborted) return;
      setRemovingItems((current) => current.filter((id) => id !== item.id));
      notify({
        tone: "error",
        title: "Removal could not start",
        description: `${item.filename ?? "Uploaded file"}: ${sourceMutationError(cause, "remove-item")}`,
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
      if (completed.status === "SUCCEEDED") {
        notify({ tone: "success", title: "File removed", description: filename });
      } else if (completed.status === "SUPERSEDED") {
        notify({
          tone: "info",
          title: "Removal superseded",
          description: `${filename}: this request was replaced by newer work.`,
        });
      } else {
        notify({
          tone: "error",
          title: "Removal failed",
          description: `${filename}: ${sourceStatusMessage(completed.errorCode ?? "SOURCE_CLEANUP_INTERNAL")}`,
        });
      }
      void refresh(selectedId);
    } catch {
      if (!controller.signal.aborted)
        notify({
          tone: "error",
          title: "Removal status unavailable",
          description: `${filename}: cleanup may still be running. Refresh the source to check its status.`,
        });
    } finally {
      removalControllers.delete(item.id);
      if (!controller.signal.aborted)
        setRemovingItems((current) => current.filter((id) => id !== item.id));
    }
  }

  async function deleteSelectedSource() {
    if (!canDelete || !selectedId) throw new Error("Source is unavailable");
    if (cleanupController.current) return;
    setError(null);
    const controller = new AbortController();
    cleanupController.current = controller;
    setCleanupPending(true);
    const sourceName = detail?.name ?? "Source";
    try {
      const operation = await deleteSource.mutateAsync({
        path: { sourceId: selectedId },
        headers: sameOriginMutationHeaders,
        signal: controller.signal,
      });
      controller.signal.throwIfAborted();
      notify({
        tone: "info",
        title: "Source deletion requested",
        description: `${sourceName}: cleanup is pending.`,
      });
      void observeDeletion(operation, controller, sourceName);
      void refresh(selectedId);
    } catch (cause) {
      if (cleanupController.current === controller) cleanupController.current = null;
      if (controller.signal.aborted) return;
      setCleanupPending(false);
      notify({
        tone: "error",
        title: "Deletion could not start",
        description: `${sourceName}: ${sourceMutationError(cause, "delete-source")}`,
      });
      throw cause;
    }
  }

  async function observeDeletion(
    operation: SourceOperation,
    controller: AbortController,
    sourceName: string,
  ) {
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
      } else if (completed.status === "SUPERSEDED") {
        notify({
          tone: "info",
          title: "Source deletion superseded",
          description: `${sourceName}: this request was replaced by newer work. Refresh before trying again.`,
        });
        void refresh(selectedId);
      } else {
        notify({
          tone: "error",
          title: "Source deletion failed",
          description: `${sourceName}: ${sourceStatusMessage(completed.errorCode ?? "SOURCE_CLEANUP_INTERNAL")}`,
        });
        void refresh(selectedId);
      }
    } catch {
      if (!controller.signal.aborted)
        notify({
          tone: "error",
          title: "Deletion status unavailable",
          description: `${sourceName}: cleanup may still be running. Refresh the source to check its status.`,
        });
    } finally {
      if (cleanupController.current === controller) {
        cleanupController.current = null;
        setCleanupPending(false);
      }
    }
  }

  const detail = sourceQuery.data;
  const sourceActions = detail?.actions ?? [];
  const canUpload = sourceActions.includes("upload");
  const canReindex = sourceActions.includes("reindex");
  const canRemoveItems = sourceActions.includes("remove_items");
  const canDelete = sourceActions.includes("delete");
  const canManageGroups = sourceActions.includes("manage_groups");
  const uploadBusy = uploadPhase !== "idle" && uploadPhase !== "finalize-retry";
  const managementBusy =
    uploadBusy ||
    reindexItem.isPending ||
    removeItem.isPending ||
    deleteSource.isPending ||
    cleanupPending ||
    sourceQuery.isError;
  const busy = managementBusy || driveBusy;
  const itemBusy =
    uploadBusy ||
    deleteSource.isPending ||
    cleanupPending ||
    sourceQuery.isError ||
    itemsQuery.isError ||
    driveBusy;
  const ProviderIcon = findSourceProvider(detail?.type)?.icon ?? FileText;
  async function refreshAuthorityViews() {
    backLinkRef.current?.focus();
    await queryClient.invalidateQueries();
  }

  return (
    <SettingsLayout wide>
      <Link
        ref={backLinkRef}
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

      {sourceQuery.isError && detail ? (
        <div className="mt-5 space-y-3">
          <p role="alert" className="text-sm text-status-danger-content">
            Source status could not be refreshed. Displayed values may be out of date.
          </p>
          <Button
            prominence="secondary"
            pending={sourceQuery.isFetching}
            onClick={() => void refreshSource()}
          >
            Refresh source
          </Button>
        </div>
      ) : null}

      {canUpload && pendingFinalize && !activePendingFinalize ? (
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

      <div className="min-w-0">
        {sourceQuery.isPending && !detail ? (
          <div className="px-6 py-16">
            <LoadingLabel label="Loading source" />
          </div>
        ) : !detail ? (
          <div className="px-6 py-16">
            <EmptyState title="Source unavailable" detail="It may have completed deletion." />
            <Button
              prominence="secondary"
              className="mt-4"
              pending={sourceQuery.isFetching}
              onClick={() => void refreshSource()}
            >
              Try again
            </Button>
          </div>
        ) : (
          <div>
            <PageHeader
              icon={<ProviderIcon />}
              iconSize={detail.type === "GOOGLE_DRIVE" ? "lg" : "sm"}
              title={detail.name}
              description={findSourceProvider(detail.type)?.name ?? detail.type}
              actions={
                canDelete ? (
                  <ConfirmDialog
                    trigger={
                      <Button
                        tone="danger"
                        prominence="tertiary"
                        disabled={busy || cleanupPending || detail.status === "DELETING"}
                      >
                        <Trash2 />
                        Delete source
                      </Button>
                    }
                    title={`Delete ${detail.name}?`}
                    description={`Deleting “${detail.name}” makes every indexed document from this source unavailable. Cleanup continues asynchronously and cannot be undone.`}
                    confirmLabel="Delete source"
                    pendingLabel="Deleting source"
                    onConfirm={deleteSelectedSource}
                    errorMessage={(cause) => sourceMutationError(cause, "delete-source")}
                  />
                ) : null
              }
            />
            {detail.errorCode &&
            !(detail.type === "GOOGLE_DRIVE" && detail.errorCode.startsWith("SOURCE_GOOGLE_")) ? (
              <p role="alert" className="mt-4 text-sm text-status-danger-content">
                {sourceStatusMessage(detail.errorCode)}
              </p>
            ) : null}
            {detail.type !== "GOOGLE_DRIVE" ? <SourceSummaryCard source={detail} /> : null}

            {canUpload && detail.type === "FILE" ? (
              <form
                className="space-y-4 border-b border-border-subtle py-6"
                onSubmit={(event) => {
                  event.preventDefault();
                  void (activePendingFinalize ? retryFinalize() : submitFile());
                }}
              >
                <div>
                  <div className="flex items-center gap-3">
                    <SourceSectionIcon icon={Upload} />
                    <h2 className="font-heading-h3 text-content-primary">Upload content</h2>
                  </div>
                  <p className="mt-2 text-sm text-content-muted">
                    PDF, DOCX, PPTX, XLSX, CSV, TXT or Markdown · Up to 10 MiB per file
                  </p>
                </div>
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
                  <label className="min-w-0 flex-1">
                    <span className="sr-only">
                      Choose PDF, DOCX, PPTX, XLSX, CSV, TXT, or Markdown file
                    </span>
                    <Input
                      ref={fileInput}
                      type="file"
                      accept=".pdf,.docx,.pptx,.xlsx,.csv,.txt,.md,text/csv,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.presentationml.presentation"
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
                      detail.status === "DELETING"
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
                        if (activePendingFinalize && !uploadBusy) {
                          setPendingFinalize(null);
                          setUploadPhase("idle");
                          setFile(null);
                          if (fileInput.current) fileInput.current.value = "";
                          setError(
                            "Finalization cancelled. The unfinished object will expire automatically.",
                          );
                          notify({
                            tone: "info",
                            title: "Finalization cancelled",
                            description: `${activePendingFinalize.filename}: the unfinished object will expire automatically.`,
                          });
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
            ) : detail.type === "GOOGLE_DRIVE" ? (
              <GoogleDrivePanel
                source={detail}
                disabled={managementBusy || detail.status === "DELETING"}
                onBusyChange={setDriveBusy}
              />
            ) : null}

            <section
              aria-labelledby="source-files-heading"
              className="mt-8 border-t border-border-subtle pt-6"
            >
              <div className="mb-4 flex items-center justify-between gap-3">
                <div className="flex items-center gap-3">
                  <SourceSectionIcon icon={FileText} />
                  <h2
                    ref={filesHeading}
                    id="source-files-heading"
                    tabIndex={-1}
                    className="font-heading-h3 text-content-primary focus-visible:outline-2 focus-visible:outline-focus-ring"
                  >
                    Files
                  </h2>
                  <HelpPopover label="Files and indexing times">
                    <p>
                      Current files acquired by this Source, not a log of sync runs. Last indexed is
                      the latest retained successful attempt for the current file version; Unknown
                      means no retained success is known.
                    </p>
                    <p>
                      A previous indexing success does not make a pending or failed current attempt
                      successful.
                    </p>
                  </HelpPopover>
                </div>
                <div className="flex items-center gap-2">
                  {detail.pendingWork ? <LoadingLabel label="Processing" /> : null}
                  <Button
                    prominence="tertiary"
                    pending={itemsQuery.isFetching}
                    onClick={() => void itemsQuery.refetch()}
                  >
                    Refresh files
                  </Button>
                </div>
              </div>
              {itemsQuery.isError ? (
                <p role="alert" className="mb-3 text-sm text-status-danger-content">
                  Files could not be loaded. Displayed files may be out of date. Retry this page or
                  return to a previous page.
                </p>
              ) : null}
              {itemsQuery.isPending ? (
                <div className="py-8">
                  <LoadingLabel label="Loading files" />
                </div>
              ) : itemsQuery.data?.items.length === 0 ? (
                <EmptyState
                  title={previous.length ? "No files on this page" : "No files yet"}
                  detail={
                    previous.length
                      ? "Files may have been removed. Return to the previous page or refresh this page."
                      : detail.type === "GOOGLE_DRIVE"
                        ? "Files appear here after synchronization acquires them from Google Drive."
                        : canUpload
                          ? "Upload one supported file to start indexing."
                          : "No files are indexed in this Source."
                  }
                />
              ) : itemsQuery.data ? (
                <div
                  className="overflow-x-auto rounded-lg border border-border-subtle"
                  tabIndex={0}
                  role="region"
                  aria-label="Source files table"
                >
                  <table className="w-full min-w-[38rem] text-left text-sm">
                    <thead className="border-b border-border-subtle bg-surface-sunken text-content-muted">
                      <tr>
                        <th scope="col" className="px-4 py-3 font-medium">
                          File
                        </th>
                        <th scope="col" className="px-4 py-3 font-medium">
                          Size
                        </th>
                        <th scope="col" className="px-4 py-3 font-medium">
                          Status
                        </th>
                        <th scope="col" className="px-4 py-3 font-medium">
                          Last indexed
                        </th>
                        <th scope="col" className="px-4 py-3 text-right font-medium">
                          Actions
                        </th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border-subtle">
                      {itemsQuery.data.items.map((item) => (
                        <tr key={item.id}>
                          <td className="min-w-64 max-w-xs px-4 py-4">
                            <span className="flex items-center gap-2 font-medium text-content-primary">
                              <FileText
                                className="size-4 shrink-0 text-content-muted"
                                aria-hidden="true"
                              />
                              <span className="break-words">
                                {item.filename ?? "Uploaded file"}
                              </span>
                            </span>
                            {item.errorCode ? (
                              <p className="mt-1 text-xs text-status-danger-content">
                                {sourceStatusMessage(item.errorCode)}
                              </p>
                            ) : null}
                          </td>
                          <td className="whitespace-nowrap px-4 py-4 text-content-muted">
                            {item.sizeBytes == null ? "Unknown" : formatBytes(item.sizeBytes)}
                          </td>
                          <td className="px-4 py-4 text-content-secondary">
                            {item.status ?? "PENDING"}
                            {item.searchStatus && (
                              <p className="mt-1 font-secondary-body text-content-muted">
                                Search:{" "}
                                {item.searchStatus === "READY"
                                  ? "Ready"
                                  : item.searchStatus === "FAILED"
                                    ? "Retry scheduled"
                                    : item.searchStatus === "INDEXING"
                                      ? "Indexing"
                                      : "Waiting for extraction"}
                              </p>
                            )}
                          </td>
                          <td className="whitespace-nowrap px-4 py-4 text-content-secondary">
                            <HistoryTime value={item.lastIndexedAt} />
                          </td>
                          <td className="px-4 py-4">
                            <div className="flex justify-end gap-1">
                              {canReindex ? (
                                <Button
                                  prominence="tertiary"
                                  size="sm"
                                  pending={reindexingItems.includes(item.id)}
                                  disabled={
                                    itemBusy ||
                                    removingItems.includes(item.id) ||
                                    item.status === "DELETING" ||
                                    detail.status === "DELETING"
                                  }
                                  onClick={() => void reindex(item)}
                                >
                                  <RefreshCw /> Reindex
                                </Button>
                              ) : null}
                              {canRemoveItems ? (
                                <ConfirmDialog
                                  trigger={
                                    <Button
                                      tone="danger"
                                      prominence="tertiary"
                                      size="sm"
                                      pending={removingItems.includes(item.id)}
                                      disabled={
                                        itemBusy ||
                                        reindexingItems.includes(item.id) ||
                                        item.status === "DELETING" ||
                                        detail.status === "DELETING"
                                      }
                                    >
                                      <Trash2 /> Remove
                                    </Button>
                                  }
                                  title={`Remove ${item.filename ?? "uploaded file"}?`}
                                  description={`Removing “${item.filename ?? "this file"}” makes its indexed document unavailable. Cleanup continues asynchronously.`}
                                  confirmLabel="Remove file"
                                  pendingLabel="Removing file"
                                  onConfirm={() => removeSelectedItem(item)}
                                  errorMessage={(cause) =>
                                    sourceMutationError(cause, "remove-item")
                                  }
                                />
                              ) : null}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : null}
              <TablePagination
                label="Files pagination"
                className="mt-3"
                page={previous.length}
                totalPages={filesTotalPages}
                previousLabel="Previous files"
                nextLabel="Next files"
                previousDisabled={!previous.length || itemsQuery.isFetching}
                nextDisabled={
                  !itemsQuery.data?.nextCursor || itemsQuery.isFetching || itemsQuery.isError
                }
                onPrevious={() => {
                  filesHeading.current?.focus();
                  setCursor(previous.at(-1));
                  setPrevious((pages) => pages.slice(0, -1));
                }}
                onNext={() => {
                  filesHeading.current?.focus();
                  setPrevious((pages) => [...pages, cursor]);
                  setCursor(itemsQuery.data?.nextCursor ?? undefined);
                }}
              >
                <label className="flex items-center gap-2 font-secondary-body text-content-secondary">
                  Rows
                  <Select
                    aria-label="Files per page"
                    size="sm"
                    className="w-auto px-2"
                    value={filesSize}
                    disabled={itemsQuery.isFetching}
                    onChange={(event) => {
                      setFilesSize(Number(event.target.value));
                      setCursor(undefined);
                      setPrevious([]);
                    }}
                  >
                    {[5, 10, 25, 50, 100].map((size) => (
                      <option key={size} value={size}>
                        {size}
                      </option>
                    ))}
                  </Select>
                </label>
              </TablePagination>
            </section>
            {detail.type === "GOOGLE_DRIVE" ? (
              <SourceRunHistory key={selectedId} sourceId={selectedId} />
            ) : (
              <SourceItemHistory key={selectedId} sourceId={selectedId} />
            )}
            <SourceGroupsSection
              sourceId={selectedId}
              editable={canManageGroups}
              onAuthorityChanged={refreshAuthorityViews}
            />
          </div>
        )}
      </div>
    </SettingsLayout>
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

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}
