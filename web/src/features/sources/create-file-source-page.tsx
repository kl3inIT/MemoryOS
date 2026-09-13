import { appText } from "@/i18n/app-text";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { ArrowLeft, FileText, Upload, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Select } from "@/components/ui/select";
import {
  useApplicationSession,
  useCapabilityAuthority,
} from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import { captureWorkflowFailure } from "@/lib/sentry";
import {
  createFileSourceMutation,
  finalizeSourceUploadMutation,
  initiateSourceUploadMutation,
  listSourcesQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { DirectUploadError, putAuthorizedObject, sha256 } from "./direct-upload";
import { sourceMutationError } from "./source-errors";
import { useSourceUploadRecovery } from "./source-upload-recovery-context";
import { SourceSetupSteps } from "./source-setup-steps";
import { SourceGroupPicker } from "./source-group-picker";

export function CreateFileSourcePage() {
  const ui = useAppTranslation();

  const session = useApplicationSession();
  const authority = useCapabilityAuthority("SOURCES_MANAGE");
  const scoped = authority === "scoped";
  const [access, setAccess] = useState<"PUBLIC" | "RESTRICTED">(scoped ? "RESTRICTED" : "PUBLIC");
  const queryClient = useQueryClient();
  const navigate = useNavigate({ from: "/admin/sources/new/file" });
  const notify = useActionNotifications();
  const createSource = useMutation(createFileSourceMutation());
  const initiateUpload = useMutation(initiateSourceUploadMutation());
  const finalizeUpload = useMutation(finalizeSourceUploadMutation());
  const { pendingFinalize, setPendingFinalize } = useSourceUploadRecovery();
  const [sourceName, setSourceName] = useState("");
  const [groupIds, setGroupIds] = useState<Set<string>>(() => new Set());
  const [groupPickerOpen, setGroupPickerOpen] = useState(scoped);
  const [sourceId, setSourceId] = useState<string | null>(null);
  const [uploadAccepted, setUploadAccepted] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<AppCopy | null>(null);
  const [phase, setPhase] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [dragging, setDragging] = useState(false);
  const controllerRef = useRef<AbortController | null>(null);
  const picker = useRef<HTMLInputElement | null>(null);
  const busy = phase !== null;
  const ownPending = pendingFinalize?.sourceId === sourceId ? pendingFinalize : null;
  const blocked = Boolean(pendingFinalize && !ownPending);

  useEffect(() => () => controllerRef.current?.abort(), []);

  const authorityKey = `${session.actorId}:${session.authorizationVersion}:${authority}`;
  const [previousAuthorityKey, setPreviousAuthorityKey] = useState(authorityKey);
  if (previousAuthorityKey !== authorityKey) {
    setPreviousAuthorityKey(authorityKey);
    setGroupIds(new Set());
    setAccess(scoped ? "RESTRICTED" : "PUBLIC");
    setGroupPickerOpen(scoped);
  }

  useEffect(() => {
    controllerRef.current?.abort();
  }, [authorityKey]);

  function selectFiles(files: FileList | null) {
    if (busy || pendingFinalize || uploadAccepted || !files?.length) return;
    setDragging(false);
    setFile(null);
    const selected = files[0]!;
    if (files.length !== 1) {
      setError("Choose one file at a time. You can add more files after creating the source.");
      return;
    }
    if (!/\.(pdf|docx|pptx|xlsx|csv|txt|md)$/i.test(selected.name)) {
      setError("Choose a PDF, DOCX, PPTX, XLSX, CSV, TXT, or Markdown file.");
      return;
    }
    if (selected.size === 0 || selected.size > 100 * 1024 * 1024) {
      setError("Choose a file between 1 byte and 100 MiB.");
      return;
    }
    setError(null);
    setFile(selected);
    if (!sourceName.trim() && !sourceId) {
      setSourceName(selected.name.replace(/\.[^.]+$/, "").slice(0, 120));
    }
  }

  async function submit() {
    if (
      authority === "none" ||
      (!sourceId && scoped && groupIds.size === 0) ||
      controllerRef.current ||
      blocked ||
      !file ||
      !sourceName.trim()
    )
      return;
    const controller = new AbortController();
    controllerRef.current = controller;
    setError(null);
    let targetId = sourceId;
    let stage: "create" | "upload" = targetId ? "upload" : "create";
    let accepted = uploadAccepted;
    try {
      if (accepted && targetId) {
        setPhase("Opening source…");
        await navigate({ to: "/admin/sources/$sourceId", params: { sourceId: targetId } });
        return;
      }
      let receipt = ownPending;
      if (!receipt) {
        setPhase("Preparing file…");
        const checksum = await sha256(file, controller.signal);
        if (!targetId) {
          setPhase("Creating source…");
          const created = await createSource.mutateAsync({
            body: {
              name: sourceName.trim(),
              groupIds: groupIds.size > 0 ? [...groupIds] : undefined,
              access: scoped ? "RESTRICTED" : access,
            },
            headers: sameOriginMutationHeaders,
            signal: controller.signal,
          });
          targetId = created.id;
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
      controller.signal.throwIfAborted();
      accepted = true;
      setUploadAccepted(true);
      setPendingFinalize(null);
      await queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() });
      controller.signal.throwIfAborted();
      notify({
        title: "Source created; upload accepted",
        description: appText(
          "{{v1}} was created. {{v2}} was accepted for indexing; indexing is not complete yet.",
          { v1: sourceName.trim(), v2: receipt.filename },
        ),
        tone: "info",
        surviveNavigation: true,
      });
      await navigate({ to: "/admin/sources/$sourceId", params: { sourceId: receipt.sourceId } });
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
            ? "The file could not be uploaded. Check your connection and retry; your source is already created."
            : sourceMutationError(cause, stage);
        setError(message);
        notify({
          title: accepted
            ? "Source created; unable to open"
            : targetId
              ? "Source created; upload needs attention"
              : "Source creation failed",
          description: appText("{{v1}}: {{v2}}", { v1: sourceName.trim(), v2: appText(message) }),
          tone: "error",
        });
      }
    } finally {
      controllerRef.current = null;
      setPhase(null);
    }
  }

  return (
    <SettingsLayout>
      <Button asChild prominence="tertiary" disabled={busy}>
        <Link to="/admin/sources/new">
          <ArrowLeft />
          {ui("Exit setup")}
        </Link>
      </Button>
      <PageHeader
        icon={<FileText />}
        title={ui("Add file source")}
        description={ui("Upload a document to start indexing.")}
      />
      <div className="flex min-w-0 flex-col gap-8 md:flex-row">
        <SourceSetupSteps current={0} steps={[{ label: "Upload file" }]} />
        <form
          className="min-w-0 flex-1 space-y-6"
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <div>
            <label
              htmlFor="file-source-name"
              className="font-secondary-action text-content-primary"
            >
              {ui("Source name")}
            </label>
            <Input
              id="file-source-name"
              value={sourceName}
              maxLength={120}
              disabled={busy || Boolean(sourceId)}
              onChange={(event) => setSourceName(event.target.value)}
              placeholder={ui("e.g. Product documentation")}
              className="mt-2"
            />
          </div>
          <div className="space-y-2">
            <label htmlFor="file-source-access" className="font-secondary-action">
              {ui("Visibility")}
            </label>
            {scoped ? (
              <p className="font-secondary-body text-content-muted">
                {ui(
                  "Private · only members of the selected groups can search and read these files.",
                )}
              </p>
            ) : (
              <Select
                id="file-source-access"
                value={access}
                disabled={busy || Boolean(sourceId)}
                onChange={(event) => setAccess(event.target.value as "PUBLIC" | "RESTRICTED")}
              >
                <option value="PUBLIC">{ui("Public · everyone in this Tenant")}</option>
                <option value="RESTRICTED">{ui("Private · selected group members")}</option>
              </Select>
            )}
          </div>
          <details
            open={groupPickerOpen}
            onToggle={(event) => setGroupPickerOpen(event.currentTarget.open)}
            className="rounded-xl border border-border-subtle bg-surface-raised"
          >
            <summary className="cursor-pointer list-none rounded-xl px-4 py-3 outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/30 sm:px-5">
              <span className="flex items-center justify-between gap-3">
                <span>
                  <span className="block font-secondary-action text-content-primary">
                    {ui("Access groups")}
                  </span>
                  <span className="mt-0.5 block font-secondary-body text-content-muted">
                    {scoped
                      ? ui("Required · select groups you manage")
                      : ui("Optional · associate ordinary groups")}
                  </span>
                </span>
                <span className="font-secondary-body tabular-nums text-content-muted">
                  {groupIds.size > 0
                    ? ui("{{v1}} selected", { v1: groupIds.size })
                    : scoped
                      ? ui("Required")
                      : ui("None")}
                </span>
              </span>
            </summary>
            {groupPickerOpen ? (
              <div className="border-t border-border-subtle p-4 sm:p-5">
                <SourceGroupPicker
                  selected={groupIds}
                  required={scoped}
                  disabled={busy || Boolean(sourceId)}
                  onChange={setGroupIds}
                />
                <p className="mt-3 font-secondary-body text-content-muted">
                  {scoped
                    ? ui("Select at least one managed group. New Sources are private.")
                    : ui(
                        "Leave the selection empty for no group associations. Global Source management does not require an association.",
                      )}
                </p>
              </div>
            ) : null}
          </details>
          <div>
            <span className="font-secondary-action text-content-primary">{ui("File")}</span>
            <div
              className={`relative mt-2 rounded-lg border border-dashed px-4 py-10 text-center transition-colors ${dragging ? "border-content-primary bg-surface-subtle" : "border-border-default bg-surface-sunken"}`}
              onDragOver={(event) => {
                event.preventDefault();
                if (!busy && !pendingFinalize && !uploadAccepted) setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={(event) => {
                event.preventDefault();
                setDragging(false);
                selectFiles(event.dataTransfer.files);
              }}
            >
              <Upload className="mx-auto mb-3 size-6 text-content-muted" aria-hidden="true" />
              <p className="font-main-ui-body text-content-primary">
                {ui("Drag and drop your file here")}
              </p>
              <Button
                type="button"
                prominence="secondary"
                className="mt-3"
                disabled={busy || Boolean(pendingFinalize) || uploadAccepted}
                onClick={() => picker.current?.click()}
              >
                {ui("Choose file")}
              </Button>
              <input
                ref={picker}
                type="file"
                className="sr-only"
                tabIndex={-1}
                aria-label={ui("Choose PDF, DOCX, PPTX, XLSX, CSV, TXT, or Markdown file")}
                accept=".pdf,.docx,.pptx,.xlsx,.csv,.txt,.md"
                disabled={busy || Boolean(pendingFinalize) || uploadAccepted}
                onChange={(event) => {
                  selectFiles(event.target.files);
                  event.target.value = "";
                }}
              />
              <p className="mt-3 font-secondary-body text-content-muted">
                {ui("PDF, DOCX, PPTX, XLSX, CSV, TXT, Markdown · Up to 100 MiB")}
              </p>
            </div>
            {file ? (
              <div className="mt-3 flex items-center gap-3 rounded-lg border border-border-subtle px-4 py-3">
                <FileText className="size-5 shrink-0 text-content-muted" aria-hidden="true" />
                <div className="min-w-0 flex-1">
                  <p className="break-all font-secondary-action text-content-primary">
                    {file.name}
                  </p>
                  <p className="font-secondary-body text-content-muted">
                    {file.size < 1024
                      ? ui("{{v1}} B", { v1: file.size })
                      : ui("{{v1}} KiB", { v1: (file.size / 1024).toFixed(1) })}
                  </p>
                </div>
                <Button
                  type="button"
                  prominence="tertiary"
                  size="sm"
                  aria-label={ui("Remove selected file")}
                  disabled={busy || Boolean(pendingFinalize) || uploadAccepted}
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
            {ui("Source management visibility follows the selected group associations.")}
          </p>
          {error ? (
            <p
              role="alert"
              className="rounded-lg bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
            >
              {ui(error)}
            </p>
          ) : null}
          {ownPending && !busy ? (
            <p role="status" className="font-secondary-body text-content-secondary">
              {ui(
                "The file reached object storage; retry finalization without uploading it again.",
              )}
            </p>
          ) : null}
          {blocked && pendingFinalize ? (
            <p className="font-secondary-body text-content-secondary">
              {ui("Finish your pending upload first.")}{" "}
              <Link
                to="/admin/sources/$sourceId"
                params={{ sourceId: pendingFinalize.sourceId }}
                className="underline"
              >
                {ui("Return to pending upload")}
              </Link>
            </p>
          ) : null}
          {busy ? (
            <div
              role="status"
              aria-live="polite"
              className="font-secondary-body text-content-secondary"
            >
              {phase ? ui(phase) : null}
              {phase === "Uploading file…" ? ui(" {{v1}}%", { v1: progress }) : ""}
            </div>
          ) : null}
          <footer className="flex flex-wrap items-center justify-end gap-3 border-t border-border-subtle pt-5">
            {sourceId ? (
              <Button asChild prominence="secondary" disabled={busy}>
                <Link to="/admin/sources/$sourceId" params={{ sourceId }}>
                  {ui("View source")}
                </Link>
              </Button>
            ) : null}
            <Button
              type="submit"
              pending={busy}
              disabled={
                authority === "none" ||
                (!sourceId && scoped && groupIds.size === 0) ||
                busy ||
                blocked ||
                !file ||
                !sourceName.trim()
              }
            >
              <Upload />
              {uploadAccepted
                ? ui("Open created Source")
                : ownPending
                  ? ui("Retry finalization")
                  : sourceId
                    ? ui("Retry upload")
                    : ui("Upload and create")}
            </Button>
          </footer>
        </form>
      </div>
    </SettingsLayout>
  );
}
