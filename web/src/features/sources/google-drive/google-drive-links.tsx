import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useId, type ReactNode, type Ref } from "react";
import { inputVariants } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { HelpPopover } from "@/components/ui/help-popover";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveSelectionPolicyResponse,
} from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { parseGoogleDriveLinks } from "./google-drive-selection";

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
}: {
  scopeMode: GetGoogleDriveConfigurationResponse["scopeMode"];
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
  onScopeModeChange?: (mode: GetGoogleDriveConfigurationResponse["scopeMode"]) => void;
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

  return (
    <div className="space-y-3">
      {onScopeModeChange ? (
        <>
          <fieldset disabled={disabled} className="space-y-2">
            {/* A field label like the rest of the form, not a second heading (Cohere's connector form). */}
            <legend className="flex items-center gap-1.5 text-sm font-medium text-content-primary">
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
            </legend>
            {/* A plain stacked radio list, as in Pipedrive's and Airtable's sync scope choices. */}
            <RadioGroup
              className="gap-3"
              value={scopeMode}
              onValueChange={(mode) => onScopeModeChange(mode as "SPECIFIC" | "GENERAL")}
            >
              {(["SPECIFIC", "GENERAL"] as const).map((mode) => (
                <div key={mode} className="flex items-start gap-3">
                  <RadioGroupItem
                    id={`${id}-scope-${mode}`}
                    value={mode}
                    className="mt-0.5"
                    aria-describedby={scopeMode === mode ? `${id}-scope-description` : undefined}
                  />
                  <div className="grid gap-1">
                    <label
                      htmlFor={`${id}-scope-${mode}`}
                      className="cursor-pointer text-sm text-content-primary"
                    >
                      {mode === "GENERAL"
                        ? ui("Entire My Drive")
                        : ui("Selected files and folders")}
                    </label>
                    {scopeMode === mode ? (
                      <p id={`${id}-scope-description`} className="text-sm text-content-muted">
                        {mode === "GENERAL"
                          ? ui(
                              "Every supported file in the connected account's My Drive. Files shared with you and shared drives are not included.",
                            )
                          : policy
                            ? ui("Only the files and folders you link below, up to {{count}}.", {
                                count: policy.maxExplicitRootsPerSource.toLocaleString(uiLocale()),
                              })
                            : ui("Only the files and folders you link below.")}
                      </p>
                    ) : null}
                  </div>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
        </>
      ) : null}
      {scopeMode === "SPECIFIC" ? (
        <div>
          {readOnly || !showLabel ? null : (
            <label htmlFor={id} className="text-sm font-medium text-content-primary">
              {ui("File or folder links")}
            </label>
          )}
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
            aria-label={readOnly || !showLabel ? ui("File or folder links") : undefined}
            aria-describedby={`${id}-count${error ? ` ${id}-error` : ""}`}
            aria-invalid={Boolean(error)}
            className={cn(
              "h-auto py-2 field-sizing-fixed",
              readOnly
                ? "w-full min-w-0 resize-none rounded-lg border border-transparent bg-transparent px-0 text-sm text-content-secondary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus-ring"
                : cn(inputVariants(), "mt-2 min-h-28 resize-y"),
            )}
            placeholder="https://drive.google.com/drive/folders/…"
            onChange={(event) => onChange(event.target.value)}
          />
          {readOnly ? null : (
            <div className="mt-1 flex flex-wrap items-center justify-between gap-2">
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
            <p id={`${id}-error`} role="alert" className="mt-2 text-sm text-status-danger-content">
              {error}
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
