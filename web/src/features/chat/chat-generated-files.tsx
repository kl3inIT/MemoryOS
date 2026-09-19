import { useAuiState } from "@assistant-ui/react";
import { useChatFilePanel } from "./chat-panel-context";
import { DownloadIcon } from "lucide-react";
import { File as FileDisplay } from "@/components/assistant-ui/elements/file";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChatChartCard } from "./chat-chart-card";
import { fileArtifactUrl, fileSize, type GeneratedFile } from "./chat-code";

const emptyFiles: GeneratedFile[] = [];

/**
 * The files run_python produced, below the answer, composed from the assistant-ui File element. Its own download
 * part accepts only absolute URLs, so the authorized same-origin artifact link is rendered here.
 */
export function ChatGeneratedFiles() {
  const ui = useAppTranslation();
  const panel = useChatFilePanel();
  const files = useAuiState(
    (state) =>
      (state.message.metadata.custom.generatedFiles as GeneratedFile[] | undefined) ?? emptyFiles,
  );
  if (!files.length) return null;
  return (
    <>
      {files
        .filter((file) => file.chart)
        .map((file) => (
          <ChatChartCard key={`chart:${file.id}`} file={file} />
        ))}
      <ul className="mt-3 flex flex-wrap gap-2" data-slot="generated-files">
        {files.map((file) => (
          <li key={file.id} className="max-w-full">
            <FileDisplay.Root className="max-w-full">
              <FileDisplay.Icon mimeType={file.mediaType} filename={file.filename} />
              <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                <button
                  type="button"
                  data-slot="file-preview"
                  aria-label={ui("Xem trước {{file}}", { file: file.filename })}
                  aria-haspopup="dialog"
                  onClick={(event) =>
                    panel.previewFile(
                      {
                        source: "generated",
                        id: file.id,
                        filename: file.filename,
                        mediaType: file.mediaType,
                      },
                      event.currentTarget,
                    )
                  }
                  className="min-w-0 rounded-sm text-left hover:underline focus-visible:outline-hidden focus-visible:ring-3 focus-visible:ring-focus-ring/30"
                >
                  <FileDisplay.Name title={file.filename}>{file.filename}</FileDisplay.Name>
                </button>
                <span className="text-xs text-muted-foreground">
                  {fileSize(file.sizeBytes, uiLocale())}
                </span>
              </div>
              <a
                data-slot="file-download"
                href={fileArtifactUrl(file.id)}
                download={file.filename}
                aria-label={ui("Tải {{file}}", { file: file.filename })}
                className="shrink-0 rounded-md p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
              >
                <DownloadIcon className="size-4" aria-hidden />
              </a>
            </FileDisplay.Root>
          </li>
        ))}
      </ul>
    </>
  );
}
