import { useAppTranslation } from "@/i18n/use-app-translation";
import { FileText, Upload, X } from "lucide-react";
import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Field, FieldTitle } from "@/components/ui/field";
import { cn } from "@/lib/utils";
import type { FileSourceCreation } from "./use-file-source-creation";

/** The files of a new File Source: dropped or chosen, listed, and removable until they upload. */
export function FileSourceDropzone({
  creation,
  onFilesChosen,
}: {
  creation: FileSourceCreation;
  /** A new acceptable batch was chosen. */
  onFilesChosen: (files: File[]) => void;
}) {
  const ui = useAppTranslation();
  const picker = useRef<HTMLInputElement | null>(null);
  const [dragging, setDragging] = useState(false);
  const { files, completedCount, filesLocked } = creation;

  function choose(selected: FileList | null) {
    setDragging(false);
    const batch = creation.selectFiles(selected);
    if (batch) onFilesChosen(batch);
  }

  return (
    <Field>
      <FieldTitle>{ui("Files")}</FieldTitle>
      {/* Dropping is a pointer shortcut; the button and the file input remain the keyboard path. */}
      <div
        role="presentation"
        className={cn(
          "relative rounded-lg border border-dashed px-4 py-10 text-center transition-colors",
          dragging
            ? "border-content-primary bg-surface-subtle"
            : "border-border-default bg-surface-sunken",
        )}
        onDragOver={(event) => {
          event.preventDefault();
          if (!filesLocked) setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          choose(event.dataTransfer.files);
        }}
      >
        <Upload className="mx-auto mb-3 size-6 text-content-muted" aria-hidden="true" />
        <p className="font-main-ui-body text-content-primary">
          {ui("Drag and drop your files here")}
        </p>
        <Button
          prominence="secondary"
          className="mt-3"
          disabled={filesLocked}
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
          disabled={filesLocked}
          onChange={(event) => {
            choose(event.target.files);
            event.target.value = "";
          }}
        />
        <p className="mt-3 text-sm text-content-muted">
          {ui("PDF, DOCX, PPTX, XLSX, CSV, TXT, Markdown · Up to 100 MiB each")}
        </p>
      </div>
      {files.length > 0 ? (
        <ul className="flex flex-col gap-2">
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
                <IconButton
                  prominence="tertiary"
                  size="sm"
                  aria-label={ui("Remove {{v1}}", { v1: selected.name })}
                  disabled={filesLocked}
                  onClick={() => creation.removeFile(index)}
                >
                  <X />
                </IconButton>
              )}
            </li>
          ))}
        </ul>
      ) : null}
    </Field>
  );
}
