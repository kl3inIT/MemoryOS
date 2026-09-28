import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Folder, Plus } from "lucide-react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { EmptyState } from "@/components/composites/empty-state";
import { Button } from "@/components/ui/button";
import { projectsOptions } from "./chat-projects-api";
import { ProjectEditor } from "./project-editor";
import { ProjectIcon } from "./project-icon";

export function ChatProjectsPage() {
  const ui = useAppTranslation();
  const [creating, setCreating] = useState(false);
  const projects = useQuery(projectsOptions());
  return (
    <>
      <AppShellHeader title={ui("Dự án")} />
      <div className="mx-auto w-full max-w-3xl overflow-y-auto px-6 py-10">
        <div className="mb-8 flex items-center justify-between gap-4">
          <h1 className="font-heading-h2 text-content-primary">{ui("Dự án")}</h1>
          <Button onClick={() => setCreating(true)}>
            <Plus data-icon="inline-start" />
            {ui("Tạo dự án")}
          </Button>
        </div>
        {projects.isPending && <p role="status">{ui("Đang tải dự án…")}</p>}
        {projects.isError && (
          <p role="alert">
            {ui("Không tải được dự án.")}{" "}
            <Button onClick={() => void projects.refetch()}>{ui("Tải lại")}</Button>
          </p>
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
        <div className="divide-y divide-border-subtle">
          {projects.data?.map((project) => (
            <Link
              key={project.id}
              to="/projects/$projectId"
              params={{ projectId: project.id }}
              className="flex items-center gap-3 rounded-xl p-4 hover:bg-surface-sunken focus-visible:ring-2 focus-visible:ring-ring"
            >
              <ProjectIcon
                iconName={project.iconName}
                className="size-5 shrink-0 text-content-muted"
              />
              <div className="min-w-0">
                <h2 className="truncate font-medium">{project.name}</h2>
                {project.description && (
                  <p className="mt-1 line-clamp-2 text-sm text-content-secondary">
                    {project.description}
                  </p>
                )}
              </div>
            </Link>
          ))}
        </div>
        {creating && <ProjectEditor onClose={() => setCreating(false)} />}
      </div>
    </>
  );
}
