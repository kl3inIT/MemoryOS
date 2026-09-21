import { appText } from "@/i18n/app-text";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { ArrowLeft, FileText, TriangleAlert, Upload, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Progress } from "@/components/ui/progress";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
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
import { SourceAccessChoice } from "./source-access-choice";
import { SourceGroupPicker } from "./source-group-picker";

export function CreateFileSourcePage() {
  const ui = useAppTranslation();

  const session = useApplicationSession();
  const authority = useCapabilityAuthority("SOURCES_MANAGE");
  const scoped = authority === "scoped";
  const [access, setAccess] = useState<"PUBLIC" | "PRIVATE">(scoped ? "PRIVATE" : "PUBLIC");
  const queryClient = useQueryClient();
  const navigate = useNavigate({ from: "/admin/sources/new/file" });
  const notify = useActionNotifications();
  const createSource = useMutation(createFileSourceMutation());
  const initiateUpload = useMutation(initiateSourceUploadMutation());
  const finalizeUpload = useMutation(finalizeSourceUploadMutation());
  const { pendingFinalize, setPendingFinalize } = useSourceUploadRecovery();
  const [sourceName, setSourceName] = useState("");
  const [groupIds, setGroupIds] = useState<Set<string>>(() => new Set());
  // Scoped managers can only create Private Sources, which need groups they manage.
  const showGroups = scoped || access === "PRIVATE";
  const [sourceId, setSourceId] = useState<string | null>(null);
  const [uploadAccepted, setUploadAccepted] = useState(false);
  const [files, setFiles] = useState<File[]>([]);
  const [error, setError] = useState<AppCopy | null>(null);
  const [phase, setPhase] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [dragging, setDragging] = useState(false);
  const [completedCount, setCompletedCount] = useState(0);
  const [currentIndex, setCurrentIndex] = useState(0);
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
    setAccess(scoped ? "PRIVATE" : "PUBLIC");
  }

  useEffect(() => {
    controllerRef.current?.abort();
  }, [authorityKey]);

  function selectFiles(selected: FileList | null) {
    if (busy || pendingFinalize || uploadAccepted || sourceId || !selected?.length) return;
    setDragging(false);
    const batch = [...selected];
    if (batch.some((file) => !/\.(pdf|docx|pptx|xlsx|csv|txt|md)$/i.test(file.name))) {
      setError("Choose only PDF, DOCX, PPTX, XLSX, CSV, TXT, or Markdown files.");
      return;
    }
    if (batch.some((file) => file.size === 0 || file.size > 100 * 1024 * 1024)) {
      setError("Choose files between 1 byte and 100 MiB each.");
      return;
    }
    setError(null);
    setFiles(batch);
    setCompletedCount(0);
    setCurrentIndex(0);
    if (batch.length === 1 && !sourceName.trim() && !sourceId) {
      setSourceName(batch[0]!.name.replace(/\.[^.]+$/, "").slice(0, 120));
    }
  }

  async function submit() {
    if (
      authority === "none" ||
      controllerRef.current ||
      blocked ||
      files.length === 0 ||
      !sourceName.trim()
    )
      return;
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
        const created = await createSource.mutateAsync({
          body: {
            name: sourceName.trim(),
            groupIds: showGroups && groupIds.size > 0 ? [...groupIds] : undefined,
            access: scoped ? "PRIVATE" : access,
          },
          headers: sameOriginMutationHeaders,
          signal: controller.signal,
        });
        targetId = created.id;
        setSourceId(targetId);
        void queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() });
      }
      stage = "upload";
      let receipt = ownPending;
      let completed = completedCount;
      for (let index = completed; index < files.length; index++) {
        current = files[index]!;
        setCurrentIndex(index);
        if (!receipt) {
          setPhase("Preparing file…");
          const checksum = await sha256(current, controller.signal);
          setPhase("Preparing upload…");
          const authorization = await initiateUpload.mutateAsync({
            path: { sourceId: targetId },
            headers: sameOriginMutationHeaders,
            body: {
              filename: current.name,
              mediaType: current.type || "application/octet-stream",
              sizeBytes: current.size,
              sha256: checksum,
            },
            signal: controller.signal,
          });
          setProgress(0);
          setPhase("Uploading file…");
          await putAuthorizedObject(authorization, current, controller.signal, setProgress);
          receipt = {
            sourceId: targetId,
            uploadId: authorization.uploadId,
            filename: current.name,
          };
          setPendingFinalize(receipt);
        }
        setPhase("Finishing upload…");
        await finalizeUpload.mutateAsync({
          path: { sourceId: receipt.sourceId, uploadId: receipt.uploadId },
          headers: sameOriginMutationHeaders,
          signal: controller.signal,
        });
        controller.signal.throwIfAborted();
        receipt = null;
        setPendingFinalize(null);
        completed = index + 1;
        setCompletedCount(completed);
      }
      accepted = true;
      setUploadAccepted(true);
      await queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() });
      controller.signal.throwIfAborted();
      notify({
        title: "Source created; upload accepted",
        description:
          files.length === 1
            ? appText(
                "{{v1}} was created. {{v2}} was accepted for indexing; indexing is not complete yet.",
                { v1: sourceName.trim(), v2: files[0]!.name },
              )
            : appText(
                "{{v1}} was created. {{v2}} files were accepted for indexing; indexing is not complete yet.",
                { v1: sourceName.trim(), v2: files.length },
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
      <PageHeader
        icon={<FileText />}
        title={ui("Add file source")}
        description={ui("Upload a document to start indexing.")}
        actions={
          <Button asChild prominence="secondary" disabled={busy}>
            <Link to="/admin/sources/new">
              <ArrowLeft />
              {ui("Exit setup")}
            </Link>
          </Button>
        }
      />
      <form
        className="min-w-0 space-y-6"
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <div>
          <label htmlFor="file-source-name" className="text-sm font-medium text-content-primary">
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
          <span id="file-source-access-label" className="text-sm font-medium text-content-primary">
            {ui("Visibility")}
          </span>
          {scoped ? (
            <p className="text-sm text-content-muted">
              {ui("Private · only members of the selected groups can search and read these files.")}
            </p>
          ) : (
            <SourceAccessChoice
              id="file-source-access"
              labelledBy="file-source-access-label"
              modes={["PUBLIC", "PRIVATE"]}
              value={access}
              disabled={busy || Boolean(sourceId)}
              onValueChange={(next) => setAccess(next === "PRIVATE" ? "PRIVATE" : "PUBLIC")}
            />
          )}
        </div>
        {showGroups ? (
          <SourceGroupPicker
            label={ui("Access groups")}
            placeholder={scoped ? ui("Select at least one group you manage.") : ui("Select groups")}
            selected={groupIds}
            disabled={busy || Boolean(sourceId)}
            onChange={setGroupIds}
          />
        ) : null}
        <div>
          <span className="text-sm font-medium text-content-primary">{ui("Files")}</span>
          <div
            className={`relative mt-2 rounded-lg border border-dashed px-4 py-10 text-center transition-colors ${dragging ? "border-content-primary bg-surface-subtle" : "border-border-default bg-surface-sunken"}`}
            onDragOver={(event) => {
              event.preventDefault();
              if (!busy && !pendingFinalize && !uploadAccepted && !sourceId) setDragging(true);
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
              {ui("Drag and drop your files here")}
            </p>
            <Button
              type="button"
              prominence="secondary"
              className="mt-3"
              disabled={busy || Boolean(pendingFinalize) || uploadAccepted || Boolean(sourceId)}
              onClick={() => picker.current?.click()}
            >
              {ui("Choose files")}
            </Button>
            <input
              ref={picker}
              type="file"
              multiple
              className="sr-only"
              tabIndex={-1}
              aria-label={ui("Choose PDF, DOCX, PPTX, XLSX, CSV, TXT, or Markdown files")}
              accept=".pdf,.docx,.pptx,.xlsx,.csv,.txt,.md"
              disabled={busy || Boolean(pendingFinalize) || uploadAccepted || Boolean(sourceId)}
              onChange={(event) => {
                selectFiles(event.target.files);
                event.target.value = "";
              }}
            />
            <p className="mt-3 text-sm text-content-muted">
              {ui("PDF, DOCX, PPTX, XLSX, CSV, TXT, Markdown · Up to 100 MiB each")}
            </p>
          </div>
          {files.length > 0 ? (
            <ul className="mt-3 space-y-2">
              {files.map((selected, index) => (
                <li
                  key={`${selected.name}:${index}`}
                  className="flex items-center gap-3 rounded-lg border border-border-subtle px-4 py-3"
                >
                  <FileText className="size-5 shrink-0 text-content-muted" aria-hidden="true" />
                  <div className="min-w-0 flex-1">
                    <p className="break-all text-sm font-medium text-content-primary">
                      {selected.name}
                    </p>
                    <p className="text-sm text-content-muted">
                      {selected.size < 1024
                        ? ui("{{v1}} B", { v1: selected.size })
                        : ui("{{v1}} KiB", { v1: (selected.size / 1024).toFixed(1) })}
                    </p>
                  </div>
                  {index < completedCount ? (
                    <span className="shrink-0 text-sm text-content-muted">{ui("Accepted")}</span>
                  ) : (
                    <Button
                      type="button"
                      prominence="tertiary"
                      size="sm"
                      aria-label={ui("Remove {{v1}}", { v1: selected.name })}
                      disabled={
                        busy || Boolean(pendingFinalize) || uploadAccepted || Boolean(sourceId)
                      }
                      onClick={() => {
                        setFiles(files.filter((_, position) => position !== index));
                        setError(null);
                      }}
                    >
                      <X />
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          ) : null}
        </div>
        {error ? (
          <Alert variant="destructive">
            <TriangleAlert aria-hidden="true" />
            <AlertDescription>{ui(error)}</AlertDescription>
          </Alert>
        ) : null}
        {ownPending && !busy ? (
          <p role="status" className="font-secondary-body text-content-secondary">
            {ui("The file reached object storage; retry finalization without uploading it again.")}
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
            className="space-y-2 font-secondary-body text-content-secondary"
          >
            <p>
              {phase ? ui(phase) : null}
              {files.length > 1
                ? ui(" · {{v1}}", {
                    v1: ui("File {{v1}} of {{v2}}", { v1: currentIndex + 1, v2: files.length }),
                  })
                : null}
              {phase === "Uploading file…" ? ui(" {{v1}}%", { v1: progress }) : ""}
            </p>
            {phase === "Uploading file…" ? (
              <Progress value={progress} aria-label={ui("Upload progress")} />
            ) : null}
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
              authority === "none" || busy || blocked || files.length === 0 || !sourceName.trim()
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
    </SettingsLayout>
  );
}
