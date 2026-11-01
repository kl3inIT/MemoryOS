import { useFieldValidity } from "@/components/form/form-context";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ImageFormat } from "./custom-image-presets";

const generateVariables = ["{{prompt}}", "{{model}}", "{{aspect_ratio}}"];
const editVariables = [...generateVariables, "{{image_data_uri}}", "{{image_base64}}"];

/** The JSON request body template of one step, bound to its form field. */
export function TemplateBodyField({ label, edit }: { label: string; edit: boolean }) {
  const ui = useAppTranslation();
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel htmlFor={field.name}>{label}</FieldLabel>
      <Textarea
        id={field.name}
        name={field.name}
        variant="mono"
        rows={4}
        spellCheck={false}
        maxLength={16384}
        value={field.state.value}
        aria-invalid={invalid || undefined}
        onBlur={field.handleBlur}
        onChange={(event) => field.handleChange(event.target.value)}
      />
      <FieldDescription>
        {ui("Biến dùng được:")}{" "}
        {(edit ? editVariables : generateVariables).map((variable) => (
          <code key={variable} className="mr-1 font-mono">
            {variable}
          </code>
        ))}
      </FieldDescription>
      {invalid ? <FieldError errors={errors} /> : null}
    </Field>
  );
}

/** How the response carries the image, bound to its form field. */
export function ImageFormatField() {
  const ui = useAppTranslation();
  const { field } = useFieldValidity<ImageFormat>();
  const labels: Record<ImageFormat, string> = {
    BASE64: ui("Base64"),
    DATA_URI: ui("Data URI"),
    RAW: ui("Ảnh thô (raw)"),
  };
  return (
    <Field>
      <FieldLabel htmlFor={field.name}>{ui("Định dạng ảnh trả về")}</FieldLabel>
      <NativeSelect
        id={field.name}
        name={field.name}
        value={field.state.value}
        onBlur={field.handleBlur}
        onChange={(event) => field.handleChange(event.target.value as ImageFormat)}
      >
        {Object.entries(labels).map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </NativeSelect>
    </Field>
  );
}
