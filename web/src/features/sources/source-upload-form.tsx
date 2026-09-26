import { useAppTranslation } from "@/i18n/use-app-translation";
import { Upload, X } from "lucide-react";
import type { RefObject } from "react";
import { Button } from "@/components/ui/button";
import { Field, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Progress } from "@/components/ui/progress";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";
import type { SourceUpload } from "./use-source-upload";

/** Adds one file to a File Source, or finishes the one already stored. */
export function SourceUploadForm({
  upload,
  fileInput,
  disabled,
}: {
  upload: SourceUpload;
  fileInput: RefObject<HTMLInputElement | null>;
  disabled: boolean;
}) {
  const ui = useAppTranslation();
  const { phase, progress, activePendingFinalize, pendingFinalize } = upload;

  return (
    <form
      className="flex flex-col gap-4 border-b border-border-subtle py-6"
      onSubmit={(event) => {
        event.preventDefault();
        void (activePendingFinalize ? upload.retryFinalize() : upload.submit());
      }}
    >
      <div>
        <div className="flex items-center gap-3">
          <SourceSectionIcon icon={Upload} />
          <h2 className="font-heading-h3 text-content-primary">{ui("Upload content")}</h2>
        </div>
        <p className="mt-2 text-sm text-content-muted">
          {ui("PDF, DOCX, PPTX, XLSX, CSV, TXT or Markdown · Up to 100 MiB per file")}
        </p>
      </div>
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <Field className="min-w-0 flex-1">
          <FieldLabel htmlFor="source-upload-file" className="sr-only">
            {ui("Choose PDF, DOCX, PPTX, XLSX, CSV, TXT, or Markdown file")}
          </FieldLabel>
          <Input
            id="source-upload-file"
            ref={fileInput}
            type="file"
            accept=".pdf,.docx,.pptx,.xlsx,.csv,.txt,.md,text/csv,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.presentationml.presentation"
            disabled={phase !== "idle" || Boolean(pendingFinalize)}
            onChange={(event) => upload.select(event.target.files?.[0] ?? null)}
          />
        </Field>
        <Button
          type="submit"
          pending={upload.busy}
          disabled={
            (!upload.file && !activePendingFinalize) ||
            Boolean(pendingFinalize && !activePendingFinalize) ||
            disabled
          }
        >
          <Upload data-icon="inline-start" />
          {activePendingFinalize ? ui("Retry finalization") : ui("Upload file")}
        </Button>
        {phase !== "idle" || activePendingFinalize ? (
          <Button type="button" prominence="secondary" onClick={upload.cancel}>
            <X data-icon="inline-start" />
            {ui("Cancel")}
          </Button>
        ) : null}
      </div>
      {phase !== "idle" || activePendingFinalize ? (
        <div className="mt-3" aria-live="polite">
          <div className="flex items-center justify-between gap-3 font-secondary-body text-content-secondary">
            <span>
              {phase === "preparing"
                ? ui("Calculating SHA-256 before authorization")
                : phase === "uploading"
                  ? ui("Uploading directly to object storage")
                  : phase === "finalizing"
                    ? ui("Verifying and registering the stored file")
                    : ui("{{v1}} is stored but not finalized", {
                        v1: activePendingFinalize?.filename ?? ui("File"),
                      })}
            </span>
            {phase === "uploading" ? <span>{progress}%</span> : null}
          </div>
          {phase === "uploading" ? (
            <Progress value={progress} aria-label={ui("Direct upload progress")} className="mt-2" />
          ) : null}
        </div>
      ) : null}
    </form>
  );
}
