import { useId, type ComponentProps } from "react";
import { useFieldValidity } from "@/components/form/form-context";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";

type BoundProps = "id" | "name" | "value" | "onChange" | "onBlur";

/**
 * A text control bound inside `form.AppField`. Unlike the shared `field.TextField` its id is unique on the page, since
 * several minutes items can be open for editing at once.
 */
export function InputField({
  label,
  hideLabel = false,
  ...input
}: Omit<ComponentProps<typeof Input>, BoundProps> & { label: string; hideLabel?: boolean }) {
  const id = useId();
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel htmlFor={id} className={hideLabel ? "sr-only" : undefined}>
        {label}
      </FieldLabel>
      <Input
        {...input}
        id={id}
        name={field.name}
        value={field.state.value}
        aria-invalid={invalid || undefined}
        onBlur={field.handleBlur}
        onChange={(event) => field.handleChange(event.target.value)}
      />
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}

/** A multi-line text control bound inside `form.AppField`, with a label that may be read only by assistive technology. */
export function TextareaField({
  label,
  hideLabel = false,
  ...textarea
}: Omit<ComponentProps<typeof Textarea>, BoundProps> & { label: string; hideLabel?: boolean }) {
  const id = useId();
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel htmlFor={id} className={hideLabel ? "sr-only" : undefined}>
        {label}
      </FieldLabel>
      <Textarea
        {...textarea}
        id={id}
        name={field.name}
        value={field.state.value}
        aria-invalid={invalid || undefined}
        onBlur={field.handleBlur}
        onChange={(event) => field.handleChange(event.target.value)}
      />
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}
