import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Select } from "@/components/ui/select";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChatDialog } from "./chat-dialog";
import {
  addToProject,
  chatLibraryKey,
  ProjectFull,
  PROJECT_FILE_LIMIT,
  type LibraryFile,
} from "./chat-library";
import { loadProjects } from "./chat-workspace-api";

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
  const [full, setFull] = useState(false);
  const projects = useQuery({
    queryKey: ["chat-projects", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadProjects(signal),
    enabled: open,
  });
  const chosen = projects.data?.find((project) => project.id === projectId) ?? projects.data?.[0];

  return (
    <ChatDialog
      open={open}
      onOpenChange={(next) => {
        setFull(false);
        onOpenChange(next);
      }}
      title={ui("Thêm vào dự án")}
      description={ui(
        "Tệp được dùng trong mọi hội thoại của dự án. Gỡ khỏi dự án không xoá tệp khỏi thư viện.",
      )}
      submitLabel={ui("Thêm vào dự án")}
      submitDisabled={!chosen}
      closeOnSuccess={false}
      onSubmit={async () => {
        if (!chosen || !files) return;
        setFull(false);
        try {
          await addToProject(chosen.id, files, AbortSignal.timeout(120_000));
        } catch (failure) {
          // A full Project is named here; any other failure is the dialog's generic error.
          if (failure instanceof ProjectFull) return setFull(true);
          throw failure;
        } finally {
          await cache.invalidateQueries({ queryKey: chatLibraryKey });
        }
        await cache.invalidateQueries({ queryKey: ["chat-projects"] });
        await cache.invalidateQueries({ queryKey: ["chat-project"] });
        onAdded?.(chosen.name);
        onOpenChange(false);
      }}
    >
      {projects.isPending && open && <p role="status">{ui("Đang tải dự án…")}</p>}
      {projects.isError && <p role="alert">{ui("Không tải được danh sách dự án.")}</p>}
      {projects.data?.length === 0 && (
        <p role="status" className="text-content-muted">
          {ui("Bạn chưa có dự án nào. Tạo dự án trong mục Dự án trước.")}
        </p>
      )}
      {projects.data && projects.data.length > 0 && (
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
      <p className="text-sm text-content-secondary">
        {ui("Đã chọn {{count}} tệp", { count: files?.length ?? 0 })}
      </p>
      {full && (
        <p role="alert" className="text-sm text-content-danger">
          {ui("Dự án chỉ giữ tối đa {{max}} tệp. Gỡ bớt tệp khỏi dự án rồi thử lại.", {
            max: PROJECT_FILE_LIMIT,
          })}
        </p>
      )}
    </ChatDialog>
  );
}
