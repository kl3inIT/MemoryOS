import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useMutation } from "@tanstack/react-query";
import { type RefObject, useRef } from "react";
import { z } from "zod";
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
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  renameSourceMutation,
  updateSourceAccessMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { zRenameSourceRequest, zUpdateSourceAccessRequest } from "@/lib/hey-api/zod.gen";
import { SourceAccessChoice } from "@/features/sources/shared/source-access-choice";

export type SourceMetadataField = "name" | "access";

const fileAccessModes = ["PUBLIC", "PRIVATE"] as const;
const googleDriveAccessModes = ["SYNC", "PRIVATE", "PUBLIC"] as const;

/** Renames a Source or changes who can read it. Mounted only while open. */
export function SourceMetadataDialog({
  source,
  field,
  disabled,
  restoreFocusRef,
  onClose,
  onSaved,
}: {
  source: SourceSummary;
  field: SourceMetadataField;
  disabled: boolean;
  restoreFocusRef: RefObject<HTMLElement | null>;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  const rename = useMutation(renameSourceMutation());
  const updateAccess = useMutation(updateSourceAccessMutation());
  const problemErrors = useProblemErrors();
  const saved = useRef(false);
  const form = useAppForm({
    defaultValues: { name: source.name, access: source.access },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: z.object({
        name: zRenameSourceRequest.shape.name.trim().min(1, ui("Enter a source name.")),
        access: zUpdateSourceAccessRequest.shape.access,
      }),
      // A new attempt clears the previous attempt's server errors.
      onSubmit: () => undefined,
    },
    onSubmit: async ({ value, formApi }) => {
      formApi.setErrorMap({ onSubmit: { form: undefined, fields: {} } });
      try {
        if (field === "name")
          await rename.mutateAsync({
            path: { sourceId: source.id },
            body: { name: value.name.trim() },
          });
        else
          await updateAccess.mutateAsync({
            path: { sourceId: source.id },
            body: { access: value.access },
          });
      } catch (cause) {
        formApi.setErrorMap({ onSubmit: problemErrors(cause) });
        return;
      }
      saved.current = true;
      onClose();
      await onSaved();
    },
  });
  const pending = useStore(form.store, (state) => state.isSubmitting);
  const unchanged = useStore(form.store, (state) =>
    field === "name"
      ? state.values.name.trim() === source.name
      : state.values.access === source.access,
  );

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !pending) onClose();
      }}
    >
      <DialogContent
        className="sm:max-w-lg"
        {...(field === "name" ? { "aria-describedby": undefined } : {})}
        onCloseAutoFocus={(event) => {
          // A saved change moves focus itself, since it can remove the menu with the permission.
          event.preventDefault();
          if (!saved.current) restoreFocusRef.current?.focus();
        }}
      >
        <form
          className="flex flex-col gap-4"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void form.handleSubmit();
          }}
        >
          <DialogHeader>
            <DialogTitle>
              {field === "name" ? ui("Rename source") : ui("Change visibility")}
            </DialogTitle>
            {field === "access" ? (
              <DialogDescription>{ui("Choose who can read this Source.")}</DialogDescription>
            ) : null}
          </DialogHeader>
          {field === "name" ? (
            <form.AppField name="name">
              {(nameField) => (
                <nameField.TextField
                  label={ui("Source name")}
                  maxLength={120}
                  disabled={disabled || pending}
                />
              )}
            </form.AppField>
          ) : (
            <form.AppField name="access">
              {() => (
                <AccessField
                  label={ui("Visibility")}
                  modes={source.type === "GOOGLE_DRIVE" ? googleDriveAccessModes : fileAccessModes}
                  disabled={disabled || pending}
                />
              )}
            </form.AppField>
          )}
          <form.AppForm>
            <form.FormError />
            <DialogFooter>
              <Button prominence="secondary" disabled={pending} onClick={onClose}>
                {ui("Cancel")}
              </Button>
              <form.SubmitButton disabled={disabled || unchanged}>
                {field === "name" ? ui("Save name") : ui("Save visibility")}
              </form.SubmitButton>
            </DialogFooter>
          </form.AppForm>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** The access dropdown bound to the form's `access` field. */
function AccessField({
  label,
  modes,
  disabled,
}: {
  label: string;
  modes: readonly SourceSummary["access"][];
  disabled: boolean;
}) {
  const { field, invalid, errors } = useFieldValidity<SourceSummary["access"]>();
  const labelId = `${field.name}-label`;
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel id={labelId} htmlFor={field.name}>
        {label}
      </FieldLabel>
      <SourceAccessChoice
        id={field.name}
        labelledBy={labelId}
        modes={modes}
        value={field.state.value}
        disabled={disabled}
        onValueChange={field.handleChange}
      />
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}
