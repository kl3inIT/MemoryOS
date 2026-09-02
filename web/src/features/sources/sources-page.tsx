import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { DatabaseZap, FileText, LoaderCircle, Plus, RefreshCw, Trash2, Upload } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { actionVariants } from "@/components/ui/action-styles";
import { Input } from "@/components/ui/input";
import { StatusBadge, type StatusTone } from "@/components/ui/status-badge";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  createFileSourceMutation,
  deleteSourceMutation,
  getSourceOptions,
  getSourceQueryKey,
  listSourcesOptions,
  listSourcesQueryKey,
  reindexSourceItemMutation,
  removeSourceItemMutation,
  uploadSourceItemMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { getSourceOperation } from "@/lib/hey-api/sdk.gen";
import type { SourceItem, SourceSummary } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { SourceActionError, sourceMutationError, sourceStatusMessage } from "./source-errors";

const terminalOperationStatuses: Record<string, true> = {
  SUCCEEDED: true,
  SUPERSEDED: true,
  FAILED: true,
};

export function SourcesPage() {
  const queryClient = useQueryClient();
  const sourcesQuery = useQuery({
    ...listSourcesOptions(),
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.some((source) => source.pendingWork) ? 1_500 : false,
  });
  const sources = sourcesQuery.data ?? [];
  const [selectedSourceId, setSelectedSourceId] = useState<string | null>(null);
  const [sourceName, setSourceName] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cleanupPending, setCleanupPending] = useState(false);
  const cleanupController = useRef<AbortController | null>(null);

  useEffect(
    () => () => {
      cleanupController.current?.abort();
      cleanupController.current = null;
    },
    [],
  );

  const selectedId = selectedSourceId ?? sources[0]?.id ?? null;
  const sourceQuery = useQuery({
    ...getSourceOptions({ path: { sourceId: selectedId ?? "" } }),
    enabled: Boolean(selectedId),
    retry: false,
    refetchInterval: (query) => (query.state.data?.source?.pendingWork ? 1_500 : false),
  });

  const createSource = useMutation(createFileSourceMutation());
  const uploadItem = useMutation(uploadSourceItemMutation());
  const reindexItem = useMutation(reindexSourceItemMutation());
  const removeItem = useMutation(removeSourceItemMutation());
  const deleteSource = useMutation(deleteSourceMutation());

  async function refresh(sourceId?: string) {
    await queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() });
    if (sourceId) {
      await queryClient.invalidateQueries({ queryKey: getSourceQueryKey({ path: { sourceId } }) });
    }
  }

  async function submitSource() {
    const name = sourceName.trim();
    if (!name) return;
    setError(null);
    try {
      const created = await createSource.mutateAsync({
        body: { name },
        headers: sameOriginMutationHeaders,
      });
      setSourceName("");
      setSelectedSourceId(created.source?.id ?? null);
      await refresh(created.source?.id);
    } catch (cause) {
      setError(sourceMutationError(cause, "create"));
    }
  }

  async function submitFile() {
    if (!selectedId || !file) return;
    setError(null);
    try {
      await uploadItem.mutateAsync({
        path: { sourceId: selectedId },
        headers: sameOriginMutationHeaders,
        body: { file },
      });
      setFile(null);
      await refresh(selectedId);
    } catch (cause) {
      setError(sourceMutationError(cause, "upload"));
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
      setSelectedSourceId(null);
      await refresh();
    } finally {
      if (cleanupController.current === controller) {
        cleanupController.current = null;
        setCleanupPending(false);
      }
    }
  }

  const detail = sourceQuery.data;
  const busy =
    createSource.isPending ||
    uploadItem.isPending ||
    reindexItem.isPending ||
    removeItem.isPending ||
    deleteSource.isPending;

  return (
    <section className="mx-auto w-full max-w-6xl px-5 py-8 sm:px-8 sm:py-12">
      <header className="flex flex-col gap-5 border-b border-border-subtle pb-7 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="font-secondary-action text-content-muted">Knowledge operations</p>
          <h1 className="mt-1 font-heading-h2 text-content-primary">Sources</h1>
          <p className="mt-2 max-w-2xl font-main-ui-body text-content-secondary">
            Upload durable Tenant knowledge. Extraction runs asynchronously in the worker.
          </p>
        </div>
        <form
          className="flex w-full max-w-md gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            void submitSource();
          }}
        >
          <Input
            aria-label="Source name"
            value={sourceName}
            maxLength={120}
            onChange={(event) => setSourceName(event.target.value)}
            placeholder="e.g. Product documentation"
            className="min-w-0 flex-1"
          />
          <Button
            type="submit"
            pending={createSource.isPending}
            disabled={!sourceName.trim() || createSource.isPending}
          >
            <Plus />
            Add FILE source
          </Button>
        </form>
      </header>

      {error ? (
        <p
          role="alert"
          className="mt-5 rounded-lg bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
        >
          {error}
        </p>
      ) : null}

      <div className="mt-7 grid gap-6 lg:grid-cols-[18rem_minmax(0,1fr)]">
        <aside aria-label="Sources" className="space-y-2">
          {sourcesQuery.isPending ? (
            <LoadingLabel label="Loading sources" />
          ) : sourcesQuery.isError ? (
            <EmptyState
              title="Sources unavailable"
              detail="Refresh to retry the Tenant source list."
            />
          ) : sources.length === 0 ? (
            <EmptyState
              title="No sources connected"
              detail="Create a FILE source, then upload its first document."
            />
          ) : (
            sources.map((source) => (
              <SourceCard
                key={source.id}
                source={source}
                selected={source.id === selectedId}
                onSelect={() => setSelectedSourceId(source.id ?? null)}
              />
            ))
          )}
        </aside>

        <main className="min-w-0 rounded-2xl border border-border-subtle bg-surface-raised">
          {!selectedId ? (
            <div className="px-6 py-16">
              <EmptyState
                title="Select a source"
                detail="Source status, files, and operations appear here."
              />
            </div>
          ) : sourceQuery.isPending && !detail ? (
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
                    <h2 className="font-heading-h3 text-content-primary">{detail.source.name}</h2>
                    <SourceStatusBadge status={detail.source.status} />
                  </div>
                  <p className="mt-2 font-secondary-body text-content-muted">
                    FILE · PUBLIC · {detail.source.documentCount ?? 0} indexed documents
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
                className="flex flex-col gap-3 border-b border-border-subtle bg-surface-subtle p-5 sm:flex-row sm:items-center sm:p-6"
                onSubmit={(event) => {
                  event.preventDefault();
                  void submitFile();
                }}
              >
                <label className="min-w-0 flex-1">
                  <span className="sr-only">Choose PDF, DOCX, TXT, or Markdown file</span>
                  <Input
                    type="file"
                    accept=".pdf,.docx,.txt,.md,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    onChange={(event) => setFile(event.target.files?.[0] ?? null)}
                    className="bg-surface-raised"
                  />
                </label>
                <Button
                  type="submit"
                  pending={uploadItem.isPending}
                  disabled={!file || busy || detail.source.status === "DELETING"}
                >
                  <Upload />
                  Upload file
                </Button>
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
      </div>
    </section>
  );
}

function SourceCard({
  source,
  selected,
  onSelect,
}: {
  source: SourceSummary;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onSelect}
      className={cn(
        actionVariants({ tone: "default", prominence: "secondary" }),
        "w-full rounded-xl px-4 py-3 text-left",
        selected && "border-focus-ring bg-surface-sunken",
      )}
    >
      <span className="flex items-center justify-between gap-3">
        <span className="truncate text-sm font-medium text-content-primary">
          {source.name ?? "FILE source"}
        </span>
        <SourceStatusBadge status={source.status} />
      </span>
      <span className="mt-2 block font-secondary-body text-content-muted">
        {source.documentCount ?? 0} documents
      </span>
    </button>
  );
}

const sourceStatusTones: Partial<Record<string, StatusTone>> = {
  ACTIVE: "success",
  FAILED: "danger",
};

function SourceStatusBadge({ status }: { status?: string }) {
  return (
    <StatusBadge tone={(status && sourceStatusTones[status]) || "info"}>
      {status ?? "NOT_STARTED"}
    </StatusBadge>
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
      <h3 className="font-heading-h3 text-content-primary">{title}</h3>
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
