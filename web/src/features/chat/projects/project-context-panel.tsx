import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { ChevronDown, MoreHorizontal, Pencil, Plus, Trash2, X } from "lucide-react";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { actionErrorText } from "@/lib/action-errors";
import { deleteChatProjectMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { ChatFilePicker } from "@/features/library/file-picker";
import { fileReference } from "@/features/library/files";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";
import { ChatFilePart } from "@/features/chat/thread/chat-attachments";
import { invalidateProjects, type Project } from "./chat-projects-api";
import { ProjectEditor } from "./project-editor";
import { ProjectIcon } from "./project-icon";
import { useProjectFiles } from "./use-project-files";

/** The Project page above its composer: name, actions, description and shared files. */
export function ProjectContextPanel({ project }: { project: Project }) {
  const ui = useAppTranslation();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const menuTrigger = useRef<HTMLButtonElement>(null);
  const cache = useQueryClient();
  const refreshSessions = useRefreshChatSessions();
  const navigate = useNavigate();
  const remove = useMutation(deleteChatProjectMutation());
  return (
    <div className="flex flex-col gap-5 pt-4 text-left">
      <div className="flex items-center gap-3">
        <ProjectIcon iconName={project.iconName} className="size-7 shrink-0 text-content-muted" />
        <h1 className="min-w-0 flex-1 break-words font-heading-h2 text-content-primary">
          {project.name}
        </h1>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <IconButton
              ref={menuTrigger}
              size="sm"
              prominence="internal"
              aria-label={ui("Thao tác dự án")}
            >
              <MoreHorizontal />
            </IconButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent
            align="end"
            className="w-auto min-w-48"
            onCloseAutoFocus={(event) => {
              if (editing || deleting) event.preventDefault();
            }}
          >
            <DropdownMenuGroup>
              <DropdownMenuItem onSelect={() => setEditing(true)}>
                <Pencil /> {ui("Chỉnh sửa dự án")}
              </DropdownMenuItem>
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuItem variant="destructive" onSelect={() => setDeleting(true)}>
                <Trash2 /> {ui("Xóa dự án")}
              </DropdownMenuItem>
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>
        <ConfirmDialog
          open={deleting}
          onOpenChange={setDeleting}
          restoreFocusRef={menuTrigger}
          title={ui("Xóa dự án?")}
          description={ui("Các hội thoại được chuyển ra ngoài dự án và vẫn giữ nguyên lịch sử.")}
          confirmLabel={ui("Xóa dự án")}
          pendingLabel={ui("Đang xóa…")}
          errorMessage={actionErrorText}
          onConfirm={async () => {
            await remove.mutateAsync({
              path: { projectId: project.id },
              query: { revision: project.revision },
            });
            // Its conversations move out of the Project, so the conversation lists change too.
            await Promise.all([invalidateProjects(cache, project.id), refreshSessions()]);
            await navigate({ to: "/" });
          }}
        />
      </div>
      {project.description && (
        <p className="whitespace-pre-wrap text-sm text-content-secondary">{project.description}</p>
      )}
      <ProjectFiles project={project} />
      {editing && <ProjectEditor project={project} onClose={() => setEditing(false)} />}
    </div>
  );
}

function ProjectFiles({ project }: { project: Project }) {
  const ui = useAppTranslation();
  const { files, setFiles, saving, saveError } = useProjectFiles(project);
  const ids = project.fileIds;
  return (
    <section aria-label={ui("Tệp dự án")} className="flex flex-col gap-3">
      <Collapsible>
        <div className="flex items-center justify-between gap-3">
          <CollapsibleTrigger asChild>
            <button
              type="button"
              className="group flex min-w-0 items-center gap-2 rounded-lg px-1 py-1 text-left outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <ChevronDown className="size-4 shrink-0 text-content-muted transition-transform group-data-[state=closed]:-rotate-90" />
              <h2 className="font-medium">
                {ids.length > 0 ? ui("Tệp ({{count}})", { count: ids.length }) : ui("Tệp")}
              </h2>
            </button>
          </CollapsibleTrigger>
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
        {ids.length === 0 && (
          <p className="text-sm text-content-muted">
            {ui("Thêm tài liệu dùng chung cho các hội thoại trong dự án.")}
          </p>
        )}
        {(saveError || files.isError) && (
          <Alert variant="destructive">
            <AlertTitle>
              {saveError ? ui(actionErrorText(saveError)) : ui("Không tải được tệp dự án.")}
            </AlertTitle>
          </Alert>
        )}
        <CollapsibleContent>
          <ul className="flex flex-col gap-2 pt-1">
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
        </CollapsibleContent>
      </Collapsible>
    </section>
  );
}
