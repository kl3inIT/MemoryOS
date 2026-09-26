import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ReactNode } from "react";
import { useFieldValidity, useFormContext } from "@/components/form/form-context";
import { Button, type ButtonProps } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldError,
  FieldLabel,
} from "@/components/ui/field";
import { Input, type InputProps } from "@/components/ui/input";

type TextFieldProps = Omit<InputProps, "id" | "name" | "value" | "onChange" | "onBlur"> & {
  label: string;
  /** Marks the field as optional beside its label. */
  optional?: boolean;
  description?: ReactNode;
};

function TextField({ label, optional = false, description, ...input }: TextFieldProps) {
  const ui = useAppTranslation();
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel htmlFor={field.name}>
        {label}
        {optional && <span className="text-content-muted">{ui("(không bắt buộc)")}</span>}
      </FieldLabel>
      <Input
        {...input}
        id={field.name}
        name={field.name}
        value={field.state.value}
        aria-invalid={invalid || undefined}
        onBlur={field.handleBlur}
        onChange={(event) => field.handleChange(event.target.value)}
      />
      {description ? <FieldDescription>{description}</FieldDescription> : null}
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}

type CheckboxFieldProps = {
  label: string;
  disabled?: boolean;
  /** Controls beside the label that must not toggle the choice, such as a help popover. */
  children?: ReactNode;
};

function CheckboxField({ label, disabled, children }: CheckboxFieldProps) {
  const { field, invalid, errors } = useFieldValidity<boolean>();
  return (
    <Field orientation="horizontal" data-invalid={invalid || undefined}>
      <Checkbox
        id={field.name}
        name={field.name}
        checked={field.state.value}
        disabled={disabled}
        aria-invalid={invalid || undefined}
        onBlur={field.handleBlur}
        onCheckedChange={(checked) => field.handleChange(checked === true)}
      />
      <FieldContent>
        <FieldLabel htmlFor={field.name}>{label}</FieldLabel>
        {invalid ? <FieldError errors={errors} /> : null}
      </FieldContent>
      {children}
    </Field>
  );
}

/** Submits the form and shows it pending while `onSubmit` runs. */
function SubmitButton(props: Omit<ButtonProps, "type" | "pending">) {
  const form = useFormContext();
  return (
    <form.Subscribe selector={(state) => state.isSubmitting}>
      {(submitting) => (
        <Button
          {...props}
          type="submit"
          disabled={props.disabled || submitting}
          pending={submitting}
        />
      )}
    </form.Subscribe>
  );
}

/** The form-level error a failed submission leaves, such as a rejected mutation. */
function FormError() {
  const form = useFormContext();
  return (
    <form.Subscribe selector={(state) => state.errorMap.onServer}>
      {(error) => (typeof error === "string" && error ? <FieldError>{error}</FieldError> : null)}
    </form.Subscribe>
  );
}

export { CheckboxField, FormError, SubmitButton, TextField };
