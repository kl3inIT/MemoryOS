import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { ArrowLeft, FileText, Upload, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  createFileSourceMutation,
  finalizeSourceUploadMutation,
  initiateSourceUploadMutation,
  listSourcesQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { DirectUploadError, putAuthorizedObject, sha256 } from "./direct-upload";
import { sourceMutationError } from "./source-errors";
import { useSourceUploadRecovery } from "./source-upload-recovery-context";

export function CreateFileSourcePage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate({ from: "/admin/sources/new/file" });
  const createSource = useMutation(createFileSourceMutation());
  const initiateUpload = useMutation(initiateSourceUploadMutation());
  const finalizeUpload = useMutation(finalizeSourceUploadMutation());
  const { pendingFinalize, setPendingFinalize } = useSourceUploadRecovery();
  const [sourceName, setSourceName] = useState("");
  const [sourceId, setSourceId] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [phase, setPhase] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [dragging, setDragging] = useState(false);
  const controllerRef = useRef<AbortController | null>(null);
  const picker = useRef<HTMLInputElement | null>(null);
  const busy = phase !== null;
  const ownPending = pendingFinalize?.sourceId === sourceId ? pendingFinalize : null;
  const blocked = Boolean(pendingFinalize && !ownPending);

  useEffect(() => () => controllerRef.current?.abort(), []);

  function selectFiles(files: FileList | null) {
    if (busy || pendingFinalize || !files?.length) return;
    setDragging(false);
    setFile(null);
    const selected = files[0]!;
    if (files.length !== 1) {
      setError("Choose one file at a time. You can add more files after creating the source.");
      return;
    }
    if (!/\.(pdf|docx|pptx|txt|md)$/i.test(selected.name)) {
      setError("Choose a PDF, DOCX, PPTX, TXT, or Markdown file.");
      return;
    }
    if (selected.size === 0 || selected.size > 10 * 1024 * 1024) {
      setError("Choose a file between 1 byte and 10 MiB.");
      return;
    }
    setError(null);
    setFile(selected);
    if (!sourceName.trim() && !sourceId) {
      setSourceName(selected.name.replace(/\.[^.]+$/, "").slice(0, 120));
    }
  }

  async function submit() {
    if (controllerRef.current || blocked || !file || !sourceName.trim()) return;
    const controller = new AbortController();
    controllerRef.current = controller;
    setError(null);
    let targetId = sourceId;
    let stage: "create" | "upload" = targetId ? "upload" : "create";
    try {
      let receipt = ownPending;
      if (!receipt) {
        setPhase("Preparing file…");
        const checksum = await sha256(file, controller.signal);
        if (!targetId) {
          setPhase("Creating source…");
          const created = await createSource.mutateAsync({
            body: { name: sourceName.trim() },
            headers: sameOriginMutationHeaders,
            signal: controller.signal,
          });
          targetId = created.source.id;
          setSourceId(targetId);
          void queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() });
        }
        stage = "upload";
        setPhase("Preparing upload…");
        const authorization = await initiateUpload.mutateAsync({
          path: { sourceId: targetId },
          headers: sameOriginMutationHeaders,
          body: {
            filename: file.name,
            mediaType: file.type || "application/octet-stream",
            sizeBytes: file.size,
            sha256: checksum,
          },
          signal: controller.signal,
        });
        setProgress(0);
        setPhase("Uploading file…");
        await putAuthorizedObject(authorization, file, controller.signal, setProgress);
        receipt = { sourceId: targetId, uploadId: authorization.uploadId, filename: file.name };
        setPendingFinalize(receipt);
      }
      setPhase("Finishing upload…");
      await finalizeUpload.mutateAsync({
        path: { sourceId: receipt.sourceId, uploadId: receipt.uploadId },
        headers: sameOriginMutationHeaders,
        signal: controller.signal,
      });
      setPendingFinalize(null);
      await queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() });
      await navigate({ to: "/admin/sources/$sourceId", params: { sourceId: receipt.sourceId } });
    } catch (cause) {
      if (!controller.signal.aborted) {
        setError(
          cause instanceof DirectUploadError
            ? "The file could not be uploaded. Check your connection and retry; your source is already created."
            : sourceMutationError(cause, stage),
        );
      }
    } finally {
      controllerRef.current = null;
      setPhase(null);
    }
  }

  return (
    <section className="mx-auto w-full max-w-3xl px-5 py-6 sm:px-8 sm:py-8">
      <Button asChild prominence="tertiary" disabled={busy}>
        <Link to="/admin">
          <ArrowLeft />
          Sources
        </Link>
      </Button>
      <header className="mt-5">
        <div className="flex items-center gap-3">
          <span className="grid size-10 place-items-center rounded-xl border border-border-subtle bg-surface-raised">
            <FileText className="size-5 text-content-primary" aria-hidden="true" />
          </span>
          <h1 className="font-heading-h2 text-content-primary">Add file source</h1>
        </div>
        <p className="mt-2 font-main-ui-body text-content-secondary">
          Upload a document to start indexing.
        </p>
      </header>
      <form
        className="mt-6 space-y-5"
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <div>
          <label htmlFor="file-source-name" className="font-secondary-action text-content-primary">
            Source name
          </label>
          <Input
            id="file-source-name"
            value={sourceName}
            maxLength={120}
            disabled={busy || Boolean(sourceId)}
            onChange={(event) => setSourceName(event.target.value)}
            placeholder="e.g. Product documentation"
            className="mt-2"
          />
        </div>
        <div>
          <span className="font-secondary-action text-content-primary">File</span>
          <div
            className={`relative mt-2 rounded-xl border border-dashed p-6 text-center transition-colors ${dragging ? "border-content-primary bg-surface-subtle" : "border-border-default bg-surface-raised"}`}
            onDragOver={(event) => {
              event.preventDefault();
              if (!busy && !pendingFinalize) setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={(event) => {
              event.preventDefault();
              setDragging(false);
              selectFiles(event.dataTransfer.files);
            }}
          >
            <Upload className="mx-auto mb-3 size-6 text-content-muted" aria-hidden="true" />
            <p className="font-main-ui-body text-content-primary">Drag and drop your file here</p>
            <Button
              type="button"
              prominence="secondary"
              className="mt-3"
              disabled={busy || Boolean(pendingFinalize)}
              onClick={() => picker.current?.click()}
            >
              Choose file
            </Button>
            <input
              ref={picker}
              type="file"
              className="sr-only"
              tabIndex={-1}
              aria-label="Choose PDF, DOCX, PPTX, TXT, or Markdown file"
              accept=".pdf,.docx,.pptx,.txt,.md"
              disabled={busy || Boolean(pendingFinalize)}
              onChange={(event) => {
                selectFiles(event.target.files);
                event.target.value = "";
              }}
            />
            <p className="mt-3 font-secondary-body text-content-muted">
              PDF, DOCX, PPTX, TXT, Markdown · Up to 10 MiB
            </p>
          </div>
          {file ? (
            <div className="mt-3 flex items-center gap-3 rounded-lg border border-border-subtle px-4 py-3">
              <FileText className="size-5 shrink-0 text-content-muted" aria-hidden="true" />
              <div className="min-w-0 flex-1">
                <p className="break-all font-secondary-action text-content-primary">{file.name}</p>
                <p className="font-secondary-body text-content-muted">
                  {file.size < 1024 ? `${file.size} B` : `${(file.size / 1024).toFixed(1)} KiB`}
                </p>
              </div>
              <Button
                type="button"
                prominence="tertiary"
                size="sm"
                aria-label="Remove selected file"
                disabled={busy || Boolean(pendingFinalize)}
                onClick={() => {
                  setFile(null);
                  setError(null);
                }}
              >
                <X />
              </Button>
            </div>
          ) : null}
        </div>
        <p className="font-secondary-body text-content-muted">
          Available to members of your organization.
        </p>
        {error ? (
          <p
            role="alert"
            className="rounded-lg bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
          >
            {error}
          </p>
        ) : null}
        {ownPending && !busy ? (
          <p role="status" className="font-secondary-body text-content-secondary">
            The file reached object storage; retry finalization without uploading it again.
          </p>
        ) : null}
        {blocked && pendingFinalize ? (
          <p className="font-secondary-body text-content-secondary">
            Finish your pending upload first.{" "}
            <Link
              to="/admin/sources/$sourceId"
              params={{ sourceId: pendingFinalize.sourceId }}
              className="underline"
            >
              Return to pending upload
            </Link>
          </p>
        ) : null}
        {busy ? (
          <div
            role="status"
            aria-live="polite"
            className="font-secondary-body text-content-secondary"
          >
            {phase}
            {phase === "Uploading file…" ? ` ${progress}%` : ""}
          </div>
        ) : null}
        <footer className="flex flex-wrap items-center justify-end gap-3 border-t border-border-subtle pt-5">
          {sourceId ? (
            <Button asChild prominence="secondary" disabled={busy}>
              <Link to="/admin/sources/$sourceId" params={{ sourceId }}>
                View source
              </Link>
            </Button>
          ) : null}
          <Button
            type="submit"
            pending={busy}
            disabled={busy || blocked || !file || !sourceName.trim()}
          >
            <Upload />
            {ownPending ? "Retry finalization" : sourceId ? "Retry upload" : "Upload and create"}
          </Button>
        </footer>
      </form>
    </section>
  );
}
