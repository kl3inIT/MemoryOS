import { useAppTranslation } from "@/i18n/use-app-translation";
import { formatUiDay } from "@/i18n/format";
import { memo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Folder, GripVertical, MoreHorizontal, Pencil, Plus, Trash2, WifiOff } from "lucide-react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { BrandLoader } from "@/components/brand-loader";
import { EmptyState } from "@/components/composites/empty-state";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { SortableList, type SortableDrag } from "@/components/composites/sortable-list";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { cn } from "@/lib/utils";
import { projectsOptions, type Project } from "./chat-projects-api";
import { ProjectDeleteDialog } from "./project-delete-dialog";
import { ProjectEditor } from "./project-editor";
import { ProjectIcon } from "./project-icon";
import { useReorderProjects } from "./use-reorder-projects";

/** The actor's Projects, in the order the API lists them; a handle on each row reorders them. */
export function ChatProjectsPage() {
  const ui = useAppTranslation();
  const [creating, setCreating] = useState(false);
  const projects = useQuery(projectsOptions());
  const reorderProjects = useReorderProjects();
  return (
    <>
      <AppShellHeader title={ui("Dự án")} pageHeader />
      <SettingsLayout className="gap-6">
        <PageHeader
          icon={<Folder />}
          title={ui("Dự án")}
          actions={
            <Button onClick={() => setCreating(true)}>
              <Plus data-icon="inline-start" aria-hidden="true" />
              {ui("Tạo dự án")}
            </Button>
          }
        />
        {projects.isPending && (
          <div role="status" className="flex justify-center py-16">
            <BrandLoader label={ui("Đang tải dự án…")} />
          </div>
        )}
        {projects.isError && (
          <EmptyState
            role="alert"
            icon={<WifiOff />}
            title={ui("Không tải được dự án.")}
            action={
              <Button size="sm" prominence="secondary" onClick={() => void projects.refetch()}>
                {ui("Tải lại")}
              </Button>
            }
          />
        )}
        {projects.data?.length === 0 && (
          <EmptyState
            icon={<Folder />}
            title={ui("Một nơi cho công việc của bạn")}
            detail={ui("Gom hội thoại và dùng chung hướng dẫn trong một dự án.")}
            action={
              <Button prominence="secondary" onClick={() => setCreating(true)}>
                {ui("Tạo dự án đầu tiên")}
              </Button>
            }
          />
        )}
        {projects.data && projects.data.length > 0 && (
          <ul className="flex flex-col gap-1 px-2">
            <SortableList
              items={projects.data}
              getId={(project) => project.id}
              onReorder={reorderProjects}
            >
              {(project, handle) => (
                <li
                  ref={handle.setNodeRef}
                  style={handle.style}
                  {...handle.drag.rowProps}
                  className={cn(
                    "group relative flex touch-none items-center gap-1 rounded-xl pr-2 transition-colors hover:bg-surface-sunken active:cursor-grabbing",
                    handle.dragging &&
                      "z-10 bg-surface-sunken shadow-hover ring-1 ring-border-subtle",
                  )}
                >
                  <ProjectHandle name={project.name} drag={handle.drag} />
                  <ProjectRow project={project} />
                </li>
              )}
            </SortableList>
          </ul>
        )}
        {creating && <ProjectEditor onClose={() => setCreating(false)} />}
      </SettingsLayout>
    </>
  );
}

/** The keyboard handle of a row; it holds its own label so a list that is dragged does not translate it again. */
const ProjectHandle = memo(function ProjectHandle({
  name,
  drag: { handleNode, attributes },
}: {
  name: string;
  drag: SortableDrag;
}) {
  const ui = useAppTranslation();
  return (
    <button
      ref={handleNode}
      type="button"
      aria-label={ui("Kéo để sắp xếp {{v1}}", { v1: name })}
      className="sr-only"
      {...attributes}
    >
      <GripVertical aria-hidden="true" className="size-3.5" />
    </button>
  );
});

/** One Project: a link to its page and, beside it, the menu to edit or delete it; the list row holds the handle. */
const ProjectRow = memo(function ProjectRow({ project }: { project: Project }) {
  const ui = useAppTranslation();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const menuTrigger = useRef<HTMLButtonElement>(null);
  return (
    <>
      <Link
        to="/projects/$projectId"
        params={{ projectId: project.id }}
        draggable={false}
        className="flex min-w-0 flex-1 items-center gap-3 rounded-xl p-3 outline-none"
      >
        <ProjectIcon iconName={project.iconName} className="size-5 shrink-0 text-content-muted" />
        <span className="flex min-w-0 flex-col gap-0.5">
          <span className="truncate font-main-content-body text-content-primary">
            {project.name}
          </span>
          {project.description && (
            <span className="truncate font-secondary-body text-content-secondary">
              {project.description}
            </span>
          )}
          <span className="truncate font-secondary-body text-content-muted">
            {ui("{{count}} tệp · Cập nhật {{date}}", {
              count: project.fileIds.length,
              date: formatUiDay(project.updatedAt),
            })}
          </span>
        </span>
      </Link>
      <DropdownMenu>
        <span className={cn("flex", hoverReveal, "has-data-[state=open]:opacity-100")}>
          <DropdownMenuTrigger asChild>
            <IconButton
              ref={menuTrigger}
              size="sm"
              prominence="internal"
              aria-label={ui("Thao tác với {{name}}", { name: project.name })}
            >
              <MoreHorizontal />
            </IconButton>
          </DropdownMenuTrigger>
        </span>
        <DropdownMenuContent
          align="end"
          className="w-auto min-w-40"
          onCloseAutoFocus={(event) => {
            if (editing || deleting) event.preventDefault();
          }}
        >
          <DropdownMenuGroup>
            <DropdownMenuItem className="cursor-pointer" onSelect={() => setEditing(true)}>
              <Pencil /> {ui("Sửa")}
            </DropdownMenuItem>
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuItem
              className="cursor-pointer"
              variant="destructive"
              onSelect={() => setDeleting(true)}
            >
              <Trash2 /> {ui("Xóa")}
            </DropdownMenuItem>
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      <ProjectDeleteDialog
        project={project}
        open={deleting}
        onOpenChange={setDeleting}
        restoreFocusRef={menuTrigger}
      />
      {editing && <ProjectEditor project={project} onClose={() => setEditing(false)} />}
    </>
  );
});
