import { useAuiState } from "@assistant-ui/react";
import { Download } from "lucide-react";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { DocumentSourceIcon } from "@/features/search/document-source-icon";
import { fileArtifactUrl, fileSize, type GeneratedFile } from "./chat-code";

const emptyFiles: GeneratedFile[] = [];

/** Download cards for the files run_python produced, below the answer like generated images. */
export function ChatGeneratedFiles() {
  const ui = useAppTranslation();
  const files = useAuiState(
    (state) =>
      (state.message.metadata.custom.generatedFiles as GeneratedFile[] | undefined) ?? emptyFiles,
  );
  if (!files.length) return null;
  return (
    <div className="mt-3 flex flex-wrap gap-2" data-slot="generated-files">
      {files.map((file) => (
        <a
          key={file.id}
          href={fileArtifactUrl(file.id)}
          download={file.filename}
          className="flex max-w-full items-center gap-2 rounded-xl border border-border-subtle bg-surface-subtle px-3 py-2 text-sm transition-colors hover:bg-surface-sunken"
          aria-label={ui("Tải {{file}}", { file: file.filename })}
        >
          <DocumentSourceIcon size="xs" mediaType={file.mediaType} />
          <span className="min-w-0 flex-1 truncate" title={file.filename}>
            {file.filename}
          </span>
          <span className="shrink-0 text-xs text-content-muted">
            {fileSize(file.sizeBytes, uiLocale())}
          </span>
          <Download className="size-4 shrink-0 text-content-muted" aria-hidden />
        </a>
      ))}
    </div>
  );
}
