import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useId, type ReactNode, type Ref } from "react";
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldError,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { HelpPopover } from "@/components/ui/help-popover";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Textarea } from "@/components/ui/textarea";
import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveSelectionPolicyResponse,
} from "@/lib/hey-api/types.gen";
import { parseGoogleDriveLinks } from "./google-drive-selection";

type ScopeMode = GetGoogleDriveConfigurationResponse["scopeMode"];

export function GoogleDriveLinks({
  scopeMode,
  policy,
  value,
  disabled,
  readOnly = false,
  showLabel = true,
  actions,
  inputRef,
  errorMessage,
  onChange,
  onScopeModeChange,
  onEscape,
}: {
  scopeMode: ScopeMode;
  policy: GoogleDriveSelectionPolicyResponse | undefined;
  value: string;
  disabled: boolean;
  readOnly?: boolean;
  /** Hide the visible field label when an enclosing section already names the field. */
  showLabel?: boolean;
  /** Controls rendered beside the root count, so the count and the actions share one row. */
  actions?: ReactNode;
  inputRef?: Ref<HTMLTextAreaElement>;
  errorMessage?: string | null;
  onChange: (value: string) => void;
  onScopeModeChange?: (mode: ScopeMode) => void;
  /** Escape in the links; returns whether it was taken, so the key goes no further. */
  onEscape?: () => boolean;
}) {
  const ui = useAppTranslation();

  const id = useId();
  const links = parseGoogleDriveLinks(value);
  const error =
    errorMessage ??
    (policy && links.length > policy.maxExplicitRootsPerSource
      ? ui("Use at most {{count}} file or folder links.", {
          count: policy.maxExplicitRootsPerSource.toLocaleString(uiLocale()),
        })
      : null);
  const labelled = !readOnly && showLabel;

  return (
    <div className="flex flex-col gap-3">
      {onScopeModeChange ? (
        <FieldSet disabled={disabled}>
          {/* A field label like the rest of the form, not a second heading (Cohere's connector form). */}
          <FieldLegend variant="label">
            {ui("Scope")}
            <HelpPopover label={ui("Scope")}>
              <p>
                {ui(
                  "For Selected files and folders, paste file or folder links, one per line or separated by commas. Only selected files and folder contents are synced. Choose a folder or its descendants, not both. Links and Google access are checked when you save.",
                )}
              </p>
              <p>
                {ui(
                  "OAuth permissions are broader than selected files and folders. Entire My Drive synchronizes the connected account's My Drive tree, not all content accessible to the account.",
                )}
              </p>
              <p>
                {ui(
                  "Supported formats: Google Docs, Sheets, Slides, PDF, DOCX, PPTX, XLSX, CSV, TXT, and Markdown. Existing file-size and processing limits still apply.",
                )}
              </p>
            </HelpPopover>
          </FieldLegend>
          {/* A plain stacked radio list, as in Pipedrive's and Airtable's sync scope choices. */}
          <RadioGroup
            value={scopeMode}
            onValueChange={(mode) => onScopeModeChange(mode as ScopeMode)}
          >
            {(["SPECIFIC", "GENERAL"] as const).map((mode) => (
              <Field key={mode} orientation="horizontal">
                <RadioGroupItem
                  id={`${id}-scope-${mode}`}
                  value={mode}
                  aria-describedby={scopeMode === mode ? `${id}-scope-description` : undefined}
                />
                <FieldContent>
                  <FieldLabel htmlFor={`${id}-scope-${mode}`}>
                    {mode === "GENERAL" ? ui("Entire My Drive") : ui("Selected files and folders")}
                  </FieldLabel>
                  {scopeMode === mode ? (
                    <FieldDescription id={`${id}-scope-description`}>
                      {mode === "GENERAL"
                        ? ui(
                            "Every supported file in the connected account's My Drive. Files shared with you and shared drives are not included.",
                          )
                        : policy
                          ? ui("Only the files and folders you link below, up to {{count}}.", {
                              count: policy.maxExplicitRootsPerSource.toLocaleString(uiLocale()),
                            })
                          : ui("Only the files and folders you link below.")}
                    </FieldDescription>
                  ) : null}
                </FieldContent>
              </Field>
            ))}
          </RadioGroup>
        </FieldSet>
      ) : null}
      {scopeMode === "SPECIFIC" ? (
        <Field data-invalid={error ? true : undefined}>
          {labelled ? <FieldLabel htmlFor={id}>{ui("File or folder links")}</FieldLabel> : null}
          <Textarea
            id={id}
            ref={inputRef}
            rows={readOnly ? Math.min(Math.max(links.length, 2), 6) : 5}
            value={value}
            disabled={disabled}
            readOnly={readOnly}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            aria-label={labelled ? undefined : ui("File or folder links")}
            aria-describedby={`${id}-count${error ? ` ${id}-error` : ""}`}
            aria-invalid={Boolean(error)}
            className={readOnly ? "resize-none" : "min-h-28 resize-y"}
            placeholder="https://drive.google.com/drive/folders/…"
            onChange={(event) => onChange(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape" && onEscape?.()) event.preventDefault();
            }}
          />
          {readOnly ? null : (
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p id={`${id}-count`} className="text-xs text-content-muted" aria-live="polite">
                {policy
                  ? ui("{{count}} of {{max}} explicit roots", {
                      count: links.length.toLocaleString(uiLocale()),
                      max: policy.maxExplicitRootsPerSource.toLocaleString(uiLocale()),
                    })
                  : ui("{{count}} explicit roots", {
                      count: links.length.toLocaleString(uiLocale()),
                    })}
              </p>
              {actions}
            </div>
          )}
          {error ? (
            <FieldError id={`${id}-error`} role="alert">
              {error}
            </FieldError>
          ) : null}
        </Field>
      ) : null}
    </div>
  );
}
