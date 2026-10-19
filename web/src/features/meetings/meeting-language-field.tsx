import { useId } from "react";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { NativeSelect } from "@/components/ui/native-select";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { rememberLanguage, type MeetingLanguage } from "./meeting-language";

/** What the meeting is spoken in, asked the same way before a recording and before an uploaded one is read. */
export function MeetingLanguageField({
  value,
  onChange,
  hint,
}: {
  value: MeetingLanguage;
  onChange: (language: MeetingLanguage) => void;
  /** When the choice stops being changeable. */
  hint: string;
}) {
  const ui = useAppTranslation();
  const id = useId();
  return (
    <Field>
      <FieldLabel htmlFor={`${id}-language`}>{ui("Ngôn ngữ")}</FieldLabel>
      <NativeSelect
        id={`${id}-language`}
        value={value}
        aria-describedby={`${id}-language-hint`}
        onChange={(event) => {
          const language = event.target.value as MeetingLanguage;
          onChange(language);
          rememberLanguage(language);
        }}
      >
        <option value="vi">{ui("Tiếng Việt")}</option>
        <option value="auto">{ui("Tiếng Việt xen tiếng Anh")}</option>
        <option value="en">{ui("Tiếng Anh")}</option>
      </NativeSelect>
      <FieldDescription id={`${id}-language-hint`}>{hint}</FieldDescription>
    </Field>
  );
}
