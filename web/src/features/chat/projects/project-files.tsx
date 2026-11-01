import { useAppTranslation } from "@/i18n/use-app-translation";
import { Plus, X } from "lucide-react";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { actionErrorText } from "@/lib/action-errors";
import { ChatFilePicker } from "@/features/library/file-picker";
import { fileReference } from "@/features/library/files";
import { ChatFilePart } from "@/features/chat/thread/chat-attachments";
import type { Project } from "./chat-projects-api";
import { useProjectFiles } from "./use-project-files";

/** The files every conversation of the project reads; adding and removing them happens here, not in the composer. */
export function ProjectFiles({ project }: { project: Project }) {
  const ui = useAppTranslation();
  const { files, setFiles, saving, saveError } = useProjectFiles(project);
  const ids = project.fileIds;
  return (
    <section aria-label={ui("Tệp dự án")} className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-content-muted">
          {ids.length === 0
            ? ui("Thêm tài liệu dùng chung cho các hội thoại trong dự án.")
            : ui("Tệp ({{count}})", { count: ids.length })}
        </p>
        <ChatFilePicker
          selected={ids}
          disabled={saving}
          onSelect={setFiles}
          trigger={
            <Button type="button" size="sm" prominence="secondary" disabled={saving}>
              <Plus data-icon="inline-start" />
              {ui("Thêm tệp")}
            </Button>
          }
        />
      </div>
      {(saveError || files.isError) && (
        <Alert variant="destructive">
          <AlertTitle>
            {saveError ? ui(actionErrorText(saveError)) : ui("Không tải được tệp dự án.")}
          </AlertTitle>
        </Alert>
      )}
      <ul className="flex flex-col gap-2">
        {files.data?.map(({ fileId, file }) => (
          <li key={fileId} className="flex min-w-0 items-center justify-between gap-2">
            {file ? (
              <ChatFilePart
                filename={file.filename}
                mimeType={file.mediaType}
                data={fileReference(file.id)}
              />
            ) : (
              <span className="text-sm text-content-muted">{ui("Tệp không còn khả dụng")}</span>
            )}
            <IconButton
              size="sm"
              prominence="internal"
              disabled={saving}
              aria-label={ui("Gỡ {{v1}} khỏi dự án", {
                v1: file?.filename ?? ui("tệp không còn khả dụng"),
              })}
              onClick={() => setFiles(ids.filter((id) => id !== fileId))}
            >
              <X />
            </IconButton>
          </li>
        ))}
      </ul>
    </section>
  );
}
