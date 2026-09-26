import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { TextButton } from "@/components/ui/text-button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { createChatProject } from "@/lib/hey-api/sdk.gen";
import { FormDialog } from "@/components/composites/form-dialog";
import { chatLibraryKey, type LibraryFile } from "@/features/library/library";
import { addToProject, ProjectFull, PROJECT_FILE_LIMIT } from "./chat-project-files";
import { loadProjects, projectSchema } from "@/features/chat/projects/chat-projects-api";

/**
 * Adds library files to one of the caller's Projects (MEM-152). The Project's own update admits them, so the
 * 20-file limit and readiness are the Project editor's rules; a full Project is named instead of silently trimmed.
 */
export function ChatAddToProjectDialog({
  files,
  onOpenChange,
  onAdded,
}: {
  /** The files to add; the dialog is open while this is set. */
  files: readonly LibraryFile[] | undefined;
  onOpenChange: (open: boolean) => void;
  onAdded?: (projectName: string) => void;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const open = files !== undefined;
  const [projectId, setProjectId] = useState("");
  const [name, setName] = useState("");
  const [newProject, setNewProject] = useState(false);
  const [full, setFull] = useState(false);
  const projects = useQuery({
    queryKey: ["chat-projects", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadProjects(signal),
    enabled: open,
  });
  const chosen = projects.data?.find((project) => project.id === projectId) ?? projects.data?.[0];
  // With no Project yet there is nothing to choose, so the dialog starts on making one.
  const creating = name.length > 0 || newProject || projects.data?.length === 0;

  return (
    <FormDialog
      open={open}
      onOpenChange={(next) => {
        setFull(false);
        onOpenChange(next);
      }}
      title={ui("Thêm vào dự án")}
      description={ui(
        "Tệp được dùng trong mọi hội thoại của dự án. Gỡ khỏi dự án không xoá tệp khỏi thư viện.",
      )}
      submitLabel={creating ? ui("Tạo và thêm tệp") : ui("Thêm vào dự án")}
      submitDisabled={creating ? name.trim().length === 0 : !chosen}
      closeOnSuccess={false}
      onSubmit={async () => {
        if (!files) return;
        setFull(false);
        // A first Project is made here rather than sending the person away to make one and come back.
        const project = creating
          ? projectSchema.parse(
              (
                await createChatProject({
                  body: { name: name.trim(), description: "", instructions: "", fileIds: [] },
                  signal: AbortSignal.timeout(30000),
                })
              ).data,
            )
          : chosen;
        if (!project) return;
        try {
          await addToProject(project.id, files, AbortSignal.timeout(120_000));
        } catch (failure) {
          // A full Project is named here; any other failure is the dialog's generic error.
          if (failure instanceof ProjectFull) return setFull(true);
          throw failure;
        } finally {
          await cache.invalidateQueries({ queryKey: chatLibraryKey });
        }
        await cache.invalidateQueries({ queryKey: ["chat-projects"] });
        await cache.invalidateQueries({ queryKey: ["chat-project"] });
        onAdded?.(project.name);
        onOpenChange(false);
      }}
    >
      {projects.isPending && open && <p role="status">{ui("Đang tải dự án…")}</p>}
      {projects.isError && <p role="alert">{ui("Không tải được danh sách dự án.")}</p>}
      {projects.data && creating && (
        <label className="block space-y-1">
          <span>{ui("Tên dự án")}</span>
          <Input
            value={name}
            autoFocus
            maxLength={120}
            placeholder={ui("Ví dụ: Báo cáo quý 4")}
            onChange={(event) => setName(event.target.value)}
          />
        </label>
      )}
      {projects.data && projects.data.length > 0 && !creating && (
        <label className="block space-y-1">
          <span>{ui("Dự án")}</span>
          <Select value={chosen?.id ?? ""} onChange={(event) => setProjectId(event.target.value)}>
            {projects.data.map((project) => (
              <option key={project.id} value={project.id}>
                {project.name} ({project.fileIds.length}/{PROJECT_FILE_LIMIT})
              </option>
            ))}
          </Select>
        </label>
      )}
      {projects.data && projects.data.length > 0 && (
        <TextButton
          size="sm"
          onClick={() => {
            setNewProject(!creating);
            setName("");
          }}
        >
          {creating ? ui("Chọn dự án có sẵn") : ui("Tạo dự án mới")}
        </TextButton>
      )}
      <p className="text-sm text-content-secondary">
        {ui("Đã chọn {{count}} tệp", { count: files?.length ?? 0 })}
      </p>
      {full && (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui("Dự án chỉ giữ tối đa {{max}} tệp. Gỡ bớt tệp khỏi dự án rồi thử lại.", {
            max: PROJECT_FILE_LIMIT,
          })}
        </p>
      )}
    </FormDialog>
  );
}
