import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, type Ref } from "react";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { useAppForm, useProblemErrors } from "@/components/form/app-form";
import { useFieldValidity } from "@/components/form/form-context";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Textarea } from "@/components/ui/textarea";
import { actionProblem } from "@/lib/action-errors";
import {
  createChatProjectMutation,
  updateChatProjectMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { zProjectInput } from "@/lib/hey-api/zod.gen";
import { useProblemMessage } from "@/lib/use-problem-message";
import { invalidateProjects, projectOf, type Project } from "./chat-projects-api";
import { ProjectIconPicker } from "./project-icon";

/** Creates a Project (name and icon) or edits one (name, icon and instructions) in a dialog. */
export function ProjectEditor({ project, onClose }: { project?: Project; onClose: () => void }) {
  const ui = useAppTranslation();
  const { t: errorText } = useTranslation("errors");
  const { t: common } = useTranslation("common");
  const cache = useQueryClient();
  const navigate = useNavigate();
  const problemErrors = useProblemErrors();
  const problemMessage = useProblemMessage();
  const nameInput = useRef<HTMLInputElement>(null);
  const create = useMutation(createChatProjectMutation());
  const update = useMutation(updateChatProjectMutation());
  // The generated request schema with the rules this editor adds.
  const schema = zProjectInput.pick({ name: true, instructions: true, iconName: true }).extend({
    name: zProjectInput.shape.name.unwrap().trim().min(1, errorText("required")).max(200),
    instructions: zProjectInput.shape.instructions.unwrap().max(32000),
    iconName: zProjectInput.shape.iconName.unwrap(),
  });
  const form = useAppForm({
    defaultValues: {
      name: project?.name ?? "",
      instructions: project?.instructions ?? "",
      iconName: project?.iconName ?? "",
    },
    validationLogic: revalidateLogic(),
    validators: { onDynamic: schema },
    onSubmit: async ({ value, formApi }) => {
      formApi.setErrorMap({ onSubmit: { form: undefined, fields: {} } });
      const body = {
        name: value.name.trim(),
        description: project?.description ?? "",
        instructions: value.instructions,
        fileIds: project?.fileIds ?? [],
        iconName: value.iconName,
      };
      try {
        if (project) {
          await update.mutateAsync({
            path: { projectId: project.id },
            query: { revision: project.revision },
            body,
          });
          await invalidateProjects(cache, project.id);
          onClose();
          return;
        }
        const created = projectOf(await create.mutateAsync({ body }));
        await invalidateProjects(cache);
        onClose();
        await navigate({ to: "/projects/$projectId", params: { projectId: created.id } });
      } catch (cause) {
        // Field violations go on their fields; the form says whether the outcome is known.
        formApi.setErrorMap({
          onSubmit: { ...problemErrors(cause), form: problemMessage(actionProblem(cause)) },
        });
      }
    },
  });
  const saving = useStore(form.store, (state) => state.isSubmitting);

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !saving) onClose();
      }}
    >
      <DialogContent
        showCloseButton={false}
        className="sm:max-w-2xl"
        onOpenAutoFocus={(event) => {
          event.preventDefault();
          nameInput.current?.focus();
        }}
      >
        <form
          noValidate
          className="flex flex-col gap-5"
          onSubmit={(event) => {
            event.preventDefault();
            void form.handleSubmit();
          }}
        >
          <DialogHeader>
            <DialogTitle>{project ? ui("Chỉnh sửa dự án") : ui("Tạo dự án")}</DialogTitle>
            <DialogDescription>
              {project
                ? ui("Hướng dẫn áp dụng cho những lượt tiếp theo trong dự án.")
                : ui("Đặt tên cho công việc bạn muốn tập trung.")}
            </DialogDescription>
          </DialogHeader>
          <FieldGroup>
            <form.Subscribe selector={(state) => state.values.iconName}>
              {(iconName) => (
                <form.AppField name="name">
                  {() => (
                    <ProjectNameField
                      inputRef={nameInput}
                      disabled={saving}
                      iconName={iconName}
                      onIcon={(key) => form.setFieldValue("iconName", key)}
                    />
                  )}
                </form.AppField>
              )}
            </form.Subscribe>
            {project && (
              <form.AppField name="instructions">
                {() => <ProjectInstructionsField disabled={saving} />}
              </form.AppField>
            )}
          </FieldGroup>
          <form.AppForm>
            <form.FormError />
            <DialogFooter>
              <Button type="button" prominence="secondary" disabled={saving} onClick={onClose}>
                {common("close")}
              </Button>
              <form.SubmitButton>{project ? ui("Lưu") : ui("Tạo dự án")}</form.SubmitButton>
            </DialogFooter>
          </form.AppForm>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** The Project name with its topic icon picker inside the field. */
function ProjectNameField({
  inputRef,
  disabled,
  iconName,
  onIcon,
}: {
  inputRef: Ref<HTMLInputElement>;
  disabled: boolean;
  iconName: string;
  onIcon: (key: string) => void;
}) {
  const ui = useAppTranslation();
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel htmlFor="project-name">{ui("Tên dự án")}</FieldLabel>
      <InputGroup>
        <InputGroupAddon>
          <ProjectIconPicker iconName={iconName} onIcon={onIcon} />
        </InputGroupAddon>
        <InputGroupInput
          ref={inputRef}
          id="project-name"
          name={field.name}
          maxLength={200}
          disabled={disabled}
          value={field.state.value}
          aria-invalid={invalid || undefined}
          onBlur={field.handleBlur}
          onChange={(event) => field.handleChange(event.target.value)}
        />
      </InputGroup>
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}

function ProjectInstructionsField({ disabled }: { disabled: boolean }) {
  const ui = useAppTranslation();
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel htmlFor="project-instructions">{ui("Hướng dẫn dự án")}</FieldLabel>
      <Textarea
        id="project-instructions"
        name={field.name}
        maxLength={32000}
        rows={6}
        disabled={disabled}
        value={field.state.value}
        aria-invalid={invalid || undefined}
        onBlur={field.handleBlur}
        onChange={(event) => field.handleChange(event.target.value)}
      />
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}
