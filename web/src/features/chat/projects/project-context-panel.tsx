import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { MoreHorizontal, Pencil, Trash2 } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import type { Project } from "./chat-projects-api";
import { ProjectDeleteDialog } from "./project-delete-dialog";
import { ProjectEditor } from "./project-editor";
import { ProjectIcon } from "./project-icon";

/** The Project page above its composer: name, actions and description. */
export function ProjectContextPanel({ project }: { project: Project }) {
  const ui = useAppTranslation();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const menuTrigger = useRef<HTMLButtonElement>(null);
  const navigate = useNavigate();
  return (
    <div className="flex flex-col gap-5 pt-4 text-left">
      <div className="flex items-center gap-3">
        <ProjectIcon iconName={project.iconName} className="size-7 shrink-0 text-content-muted" />
        <h1 className="min-w-0 flex-1 font-heading-h2 break-words text-content-primary">
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
              <DropdownMenuItem className="cursor-pointer" onSelect={() => setEditing(true)}>
                <Pencil /> {ui("Chỉnh sửa dự án")}
              </DropdownMenuItem>
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuItem
                className="cursor-pointer"
                variant="destructive"
                onSelect={() => setDeleting(true)}
              >
                <Trash2 /> {ui("Xóa dự án")}
              </DropdownMenuItem>
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>
        <ProjectDeleteDialog
          project={project}
          open={deleting}
          onOpenChange={setDeleting}
          restoreFocusRef={menuTrigger}
          onDeleted={() => navigate({ to: "/" })}
        />
      </div>
      {project.description && (
        <p className="text-sm whitespace-pre-wrap text-content-secondary">{project.description}</p>
      )}
      {editing && <ProjectEditor project={project} onClose={() => setEditing(false)} />}
    </div>
  );
}
