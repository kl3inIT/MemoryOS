import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Field, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { TextButton } from "@/components/ui/text-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { createChatProjectMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { FormDialog } from "@/components/composites/form-dialog";
import { invalidateLibrary, type LibraryFile } from "@/features/library/library";
import { addToProject, ProjectFull, PROJECT_FILE_LIMIT } from "./chat-project-files";
import { invalidateProjects, projectOf, projectsOptions, type Project } from "./chat-projects-api";

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
  const open = files !== undefined;
  const [projectId, setProjectId] = useState("");
  const [name, setName] = useState("");
  const [newProject, setNewProject] = useState(false);
  const projects = useQuery({ ...projectsOptions(), enabled: open });
  const create = useMutation({
    ...createChatProjectMutation(),
    onSuccess: () => invalidateProjects(cache),
  });
  const add = useMutation({
    mutationFn: ({ project, added }: { project: Project; added: readonly LibraryFile[] }) =>
      addToProject(project.id, added, AbortSignal.timeout(120_000)),
    // Generated files are copied into uploads first, so the library changes even when the Project is full.
    onSettled: () => invalidateLibrary(cache),
    onSuccess: (_, { project }) => invalidateProjects(cache, project.id),
  });
  const chosen = projects.data?.find((project) => project.id === projectId) ?? projects.data?.[0];
  // With no Project yet there is nothing to choose, so the dialog starts on making one.
  const creating = name.length > 0 || newProject || projects.data?.length === 0;
  const full = add.error instanceof ProjectFull;

  return (
    <FormDialog
      open={open}
      onOpenChange={(next) => {
        add.reset();
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
        add.reset();
        // A first Project is made here rather than sending the person away to make one and come back.
        const project = creating
          ? projectOf(
              await create.mutateAsync({
                body: { name: name.trim(), description: "", instructions: "", fileIds: [] },
              }),
            )
          : chosen;
        if (!project) return;
        try {
          await add.mutateAsync({ project, added: files });
        } catch (failure) {
          // A full Project is named here; any other failure is the dialog's generic error.
          if (failure instanceof ProjectFull) return;
          throw failure;
        }
        onAdded?.(project.name);
        onOpenChange(false);
      }}
    >
      {projects.isPending && open && <p role="status">{ui("Đang tải dự án…")}</p>}
      {projects.isError && <p role="alert">{ui("Không tải được danh sách dự án.")}</p>}
      {projects.data && creating && (
        <Field>
          <FieldLabel htmlFor="add-to-project-name">{ui("Tên dự án")}</FieldLabel>
          <Input
            id="add-to-project-name"
            value={name}
            maxLength={120}
            placeholder={ui("Ví dụ: Báo cáo quý 4")}
            onChange={(event) => setName(event.target.value)}
          />
        </Field>
      )}
      {projects.data && projects.data.length > 0 && !creating && (
        <Field>
          <FieldLabel htmlFor="add-to-project-target">{ui("Dự án")}</FieldLabel>
          <NativeSelect
            id="add-to-project-target"
            value={chosen?.id ?? ""}
            onChange={(event) => setProjectId(event.target.value)}
          >
            {projects.data.map((project) => (
              <NativeSelectOption key={project.id} value={project.id}>
                {project.name} ({project.fileIds.length}/{PROJECT_FILE_LIMIT})
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
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
        <Alert variant="destructive">
          <AlertTitle>
            {ui("Dự án chỉ giữ tối đa {{max}} tệp. Gỡ bớt tệp khỏi dự án rồi thử lại.", {
              max: PROJECT_FILE_LIMIT,
            })}
          </AlertTitle>
        </Alert>
      )}
    </FormDialog>
  );
}
