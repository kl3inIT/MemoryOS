import { useId } from "react";
import { inputVariants } from "@/components/ui/input";
import { HelpPopover } from "@/components/ui/help-popover";
import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveRootResponse,
} from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import {
  parseGoogleDriveLinks,
  MAX_GOOGLE_DRIVE_ROOTS,
  MAX_GOOGLE_DRIVE_LINK_LENGTH,
} from "./google-drive-selection";

export function GoogleDriveLinks({
  roots,
  scopeMode,
  savedScopeMode,
  value,
  disabled,
  onChange,
  onScopeModeChange,
}: {
  roots: GoogleDriveRootResponse[];
  scopeMode: GetGoogleDriveConfigurationResponse["scopeMode"];
  savedScopeMode?: GetGoogleDriveConfigurationResponse["scopeMode"];
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
  onScopeModeChange: (mode: GetGoogleDriveConfigurationResponse["scopeMode"]) => void;
}) {
  const id = useId();
  const links = parseGoogleDriveLinks(value);
  const error =
    links.length > MAX_GOOGLE_DRIVE_ROOTS
      ? "Use at most 20 file or folder links."
      : links.some((link) => link.length > MAX_GOOGLE_DRIVE_LINK_LENGTH)
        ? "Each link must be no longer than 2,048 characters."
        : null;

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2">
        <h3 className="font-heading-h3 text-content-primary">Selected content</h3>
        <HelpPopover label="Selected content">
          <p>
            For Specific, paste file or folder links, one per line or separated by commas. Only
            selected files and folder contents are synced. Choose a folder or its descendants, not
            both. Links and Google access are checked when you save.
          </p>
          <p>
            OAuth permissions are broader than a Specific selection. General synchronizes the
            connected account&apos;s My Drive tree, not all content accessible to the account.
          </p>
          <p>
            Supported formats: Google Docs, Sheets, Slides, PDF, DOCX, PPTX, XLSX, CSV, TXT, and
            Markdown. Existing file-size and processing limits still apply.
          </p>
        </HelpPopover>
      </div>
      <fieldset disabled={disabled} className="space-y-2">
        <legend className="font-secondary-action text-content-primary">Scope</legend>
        <div className="flex flex-wrap gap-3">
          {(["SPECIFIC", "GENERAL"] as const).map((mode) => (
            <label
              key={mode}
              className="flex min-h-11 cursor-pointer items-center gap-2 rounded-lg border border-border-default px-3 has-checked:bg-surface-subtle has-disabled:cursor-default"
            >
              <input
                type="radio"
                name={`${id}-scope`}
                checked={scopeMode === mode}
                onChange={() => onScopeModeChange(mode)}
                aria-describedby={`${id}-scope-description`}
                className="size-4 shrink-0 accent-primary focus-visible:ring-3 focus-visible:ring-focus-ring"
              />
              {mode === "GENERAL" ? "General" : "Specific"}
            </label>
          ))}
        </div>
        <p id={`${id}-scope-description`} className="text-sm text-content-secondary">
          {scopeMode === "GENERAL"
            ? "Entire My Drive of the connected OAuth account, including supported files in its folders. Does not scan Shared with me, Shared Drives, or everyone else's drives."
            : "Choose 1–20 explicit file or folder links. Only those files and folder contents are synchronized."}
        </p>
      </fieldset>
      {savedScopeMode && (savedScopeMode === "GENERAL" || roots.length > 0) ? (
        <div>
          <h3 className="font-secondary-action text-content-primary">Saved scope</h3>
          {savedScopeMode === "GENERAL" ? (
            <p className="mt-2 text-sm text-content-secondary">
              General — entire My Drive of the connected account.
            </p>
          ) : (
            <ul
              aria-label="Saved roots"
              className="mt-2 divide-y divide-border-subtle rounded-lg border border-border-subtle text-sm text-content-secondary"
            >
              {roots.map((root) => (
                <li
                  key={root.id}
                  className="flex flex-wrap items-center justify-between gap-2 break-words px-3 py-2"
                >
                  <span className="min-w-0 flex-1 break-words">{root.name}</span>
                  <span className="shrink-0 text-content-muted">
                    ({root.mimeType === "application/vnd.google-apps.folder" ? "folder" : "file"})
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
      {scopeMode === "SPECIFIC" ? (
        <div>
          <label htmlFor={id} className="font-secondary-action text-content-primary">
            File or folder links
          </label>
          <textarea
            id={id}
            rows={5}
            value={value}
            disabled={disabled}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            aria-describedby={`${id}-count${error ? ` ${id}-error` : ""}`}
            aria-invalid={Boolean(error)}
            className={cn(inputVariants(), "mt-2 h-auto min-h-28 resize-y py-2")}
            placeholder="https://drive.google.com/drive/folders/…"
            onChange={(event) => onChange(event.target.value)}
          />
          <p id={`${id}-count`} className="mt-1 text-xs text-content-muted" aria-live="polite">
            {links.length} of 20 links
          </p>
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
