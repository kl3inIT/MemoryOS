import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { DatabaseZap, FileText, LoaderCircle, Plus, RefreshCw, Trash2, Upload } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { ApiError, sameOriginMutationHeaders } from "@/lib/api";
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
import type { SourceItemResponse, SourceSummaryResponse } from "@/lib/hey-api/types.gen";

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
      setError(sourceError(cause));
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
      setError(sourceError(cause));
    }
  }

  async function reindex(item: SourceItemResponse) {
    if (!selectedId || !item.id) return;
    setError(null);
    try {
      await reindexItem.mutateAsync({
        path: { sourceId: selectedId, itemId: item.id },
        headers: sameOriginMutationHeaders,
      });
      await refresh(selectedId);
    } catch (cause) {
      setError(sourceError(cause));
    }
  }

  async function remove(item: SourceItemResponse) {
    if (!selectedId || !item.id) return;
    setError(null);
    try {
      await removeItem.mutateAsync({
        path: { sourceId: selectedId, itemId: item.id },
        headers: sameOriginMutationHeaders,
      });
      await refresh(selectedId);
    } catch (cause) {
      setError(sourceError(cause));
    }
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
    throw new Error("Source cleanup did not finish within two minutes");
  }

  async function removeSource() {
    if (!selectedId) return;
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
      if (!operation.id) throw new Error("Source cleanup operation is missing an identifier");
      await refresh(selectedId);
      const completed = await waitForCleanup(operation.id, controller.signal);
      if (completed.status === "FAILED") {
        setError("Source cleanup failed. Try the operation again.");
        return;
      }
      setSelectedSourceId(null);
      await refresh();
    } catch (cause) {
      if (!controller.signal.aborted) {
        setError(sourceError(cause));
      }
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
            Upload durable Organization knowledge. Extraction runs asynchronously in the worker.
          </p>
        </div>
        <form
          className="flex w-full max-w-md gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            void submitSource();
          }}
        >
          <input
            aria-label="Source name"
            value={sourceName}
            maxLength={120}
            onChange={(event) => setSourceName(event.target.value)}
            placeholder="e.g. Product documentation"
            className="h-10 min-w-0 flex-1 rounded-lg border border-border-default bg-surface-raised px-3 font-main-ui-body text-content-primary outline-none focus:border-focus-ring focus:ring-3 focus:ring-ring/30"
          />
          <Button type="submit" disabled={!sourceName.trim() || createSource.isPending}>
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
              detail="Refresh to retry the Organization source list."
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
                    <Status status={detail.source.status} />
                  </div>
                  <p className="mt-2 font-secondary-body text-content-muted">
                    FILE · PUBLIC · {detail.source.documentCount ?? 0} indexed documents
                  </p>
                  {detail.source.errorCode ? (
                    <p className="mt-2 text-sm text-status-danger-content">
                      {detail.source.errorCode}
                    </p>
                  ) : null}
                </div>
                <Button
                  variant="outline"
                  className="text-status-danger-content"
                  disabled={busy || cleanupPending || detail.source.status === "DELETING"}
                  onClick={() => void removeSource()}
                >
                  <Trash2 />
                  Delete source
                </Button>
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
                  <input
                    type="file"
                    accept=".pdf,.docx,.txt,.md,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    onChange={(event) => setFile(event.target.files?.[0] ?? null)}
                    className="block w-full rounded-lg border border-border-default bg-surface-raised px-3 py-2 text-sm text-content-secondary file:mr-3 file:rounded-md file:border-0 file:bg-surface-accent file:px-3 file:py-1.5 file:font-medium file:text-content-primary"
                  />
                </label>
                <Button
                  type="submit"
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
                              {item.errorCode}
                            </p>
                          ) : null}
                        </div>
                        <div className="flex gap-2">
                          <Button
                            variant="outline"
                            size="sm"
                            disabled={busy || item.status === "DELETING"}
                            onClick={() => void reindex(item)}
                          >
                            <RefreshCw /> Reindex
                          </Button>
                          <Button
                            variant="outline"
                            size="sm"
                            className="text-status-danger-content"
                            disabled={busy || item.status === "DELETING"}
                            onClick={() => void remove(item)}
                          >
                            <Trash2 /> Remove
                          </Button>
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
  source: SourceSummaryResponse;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`w-full rounded-xl border px-4 py-3 text-left transition-colors ${
        selected
          ? "border-focus-ring bg-surface-accent"
          : "border-border-subtle bg-surface-raised hover:border-border-default"
      }`}
    >
      <span className="flex items-center justify-between gap-3">
        <span className="truncate text-sm font-medium text-content-primary">
          {source.name ?? "FILE source"}
        </span>
        <Status status={source.status} />
      </span>
      <span className="mt-2 block font-secondary-body text-content-muted">
        {source.documentCount ?? 0} documents
      </span>
    </button>
  );
}

function Status({ status }: { status?: string }) {
  const active = status === "ACTIVE";
  const failed = status === "FAILED";
  return (
    <span
      className={`rounded-full px-2 py-0.5 text-[0.6875rem] font-semibold tracking-wide uppercase ${
        active
          ? "bg-status-success-surface text-status-success-content"
          : failed
            ? "bg-status-danger-surface text-status-danger-content"
            : "bg-status-info-surface text-status-info-content"
      }`}
    >
      {status ?? "NOT_STARTED"}
    </span>
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

function sourceError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 403) return "Only an active Organization owner can manage sources.";
    if (error.status === 404) return "The source or item is no longer available.";
    if (error.status === 409) return "The source cannot accept that operation right now.";
    if (error.status === 400 || error.status === 413)
      return "Check the source name or uploaded file and try again.";
  }
  return "The source operation could not be completed. Try again.";
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}
