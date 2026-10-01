import { Check, Copy, Eye, EyeOff } from "lucide-react";
import { useState } from "react";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group";
import { useAppTranslation } from "@/i18n/use-app-translation";

/** A value someone pastes into Claude or ChatGPT, with Copy; a secret stays masked until revealed. */
export function McpCopyField({
  id,
  label,
  value,
  secret = false,
}: {
  id: string;
  label: string;
  value: string;
  secret?: boolean;
}) {
  const ui = useAppTranslation();
  const [copy, setCopy] = useState<"copied" | "failed">();
  const [revealed, setRevealed] = useState(false);

  async function copyValue() {
    try {
      await navigator.clipboard.writeText(value);
      setCopy("copied");
      window.setTimeout(
        () => setCopy((current) => (current === "copied" ? undefined : current)),
        2000,
      );
    } catch {
      setCopy("failed");
    }
  }

  return (
    <Field data-invalid={copy === "failed" || undefined}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <InputGroup>
        <InputGroupInput
          id={id}
          readOnly
          value={value}
          type={secret && !revealed ? "password" : "text"}
          onFocus={(event) => event.currentTarget.select()}
        />
        <InputGroupAddon align="inline-end">
          {secret ? (
            <InputGroupButton
              size="icon-xs"
              aria-label={
                revealed ? ui("Ẩn {{label}}", { label }) : ui("Hiện {{label}}", { label })
              }
              aria-pressed={revealed}
              onClick={() => setRevealed((current) => !current)}
            >
              {revealed ? <EyeOff /> : <Eye />}
            </InputGroupButton>
          ) : null}
          <InputGroupButton
            size="icon-xs"
            aria-label={ui("Sao chép {{label}}", { label })}
            onClick={() => void copyValue()}
          >
            {copy === "copied" ? <Check /> : <Copy />}
          </InputGroupButton>
        </InputGroupAddon>
      </InputGroup>
      {copy === "failed" ? (
        <FieldError>{ui("Không sao chép được. Hãy chọn và sao chép thủ công.")}</FieldError>
      ) : copy === "copied" ? (
        <span role="status" className="sr-only">
          {ui("Đã sao chép.")}
        </span>
      ) : null}
    </Field>
  );
}
