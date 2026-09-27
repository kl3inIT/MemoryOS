import { useFieldValidity } from "@/components/form/form-context";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { SourceAccessChoice } from "./source-access-choice";
import { SourceGroupPicker } from "./source-group-picker";

type SourceAccess = SourceSummary["access"];

/** Who can read a Source, bound to the enclosing `form.AppField`. */
export function SourceAccessField({
  label,
  modes,
  disabled,
}: {
  label: string;
  modes: readonly SourceAccess[];
  disabled: boolean;
}) {
  const { field, invalid, errors } = useFieldValidity<SourceAccess>();
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

/** The groups that read a group-access Source, bound to the enclosing `form.AppField`. */
export function SourceGroupsField({
  label,
  placeholder,
  disabled,
}: {
  label: string;
  placeholder: string;
  disabled: boolean;
}) {
  const { field, invalid, errors } = useFieldValidity<ReadonlySet<string>>();
  return (
    <Field data-invalid={invalid || undefined}>
      <SourceGroupPicker
        label={label}
        placeholder={placeholder}
        selected={field.state.value}
        disabled={disabled}
        onChange={field.handleChange}
      />
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}
