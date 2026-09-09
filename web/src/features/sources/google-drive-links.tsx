import { useId, type Ref } from "react";
import { inputVariants } from "@/components/ui/input";
import { HelpPopover } from "@/components/ui/help-popover";
import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveSelectionPolicyResponse,
} from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { parseGoogleDriveLinks } from "./google-drive-selection";

const defaultRootType = { label: "File", color: "#8A9099", mark: "file" };
const rootTypes = new Map<string, typeof defaultRootType>([
  ["application/vnd.google-apps.folder", { label: "Folder", color: "#9AA0A6", mark: "folder" }],
  [
    "application/vnd.google-apps.spreadsheet",
    { label: "Google Sheets", color: "#00AC47", mark: "grid" },
  ],
  [
    "application/vnd.google-apps.document",
    { label: "Google Docs", color: "#4285F4", mark: "lines" },
  ],
  [
    "application/vnd.google-apps.presentation",
    { label: "Google Slides", color: "#F4B400", mark: "slides" },
  ],
  ["application/msword", { label: "Word", color: "#2B7CD3", mark: "W" }],
  [
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    { label: "Word", color: "#2B7CD3", mark: "W" },
  ],
  ["application/vnd.ms-excel", { label: "Excel", color: "#21A366", mark: "X" }],
  [
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    { label: "Excel", color: "#21A366", mark: "X" },
  ],
  [
    "application/vnd.ms-excel.sheet.macroEnabled.12",
    { label: "Excel", color: "#21A366", mark: "X" },
  ],
  ["application/vnd.ms-powerpoint", { label: "PowerPoint", color: "#D35230", mark: "P" }],
  [
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    { label: "PowerPoint", color: "#D35230", mark: "P" },
  ],
  ["application/pdf", { label: "PDF", color: "#E34B47", mark: "PDF" }],
  ["text/csv", { label: "CSV", color: "#21A366", mark: "grid" }],
]);

function RootTypeIcon({ color, mark }: { color: string; mark: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 16 16" className="size-4" fill="none">
      {mark === "folder" ? (
        <path
          d="M1 4a2 2 0 0 1 2-2h3l2 2h5a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2Z"
          fill={color}
        />
      ) : (
        <>
          {mark === "lines" || mark === "file" ? (
            <>
              <path d="M3 1h7l4 4v9a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V2a1 1 0 0 1 1-1Z" fill={color} />
              <path d="M10 1v4h4Z" fill="white" fillOpacity=".35" />
            </>
          ) : (
            <rect x="1" y="1" width="14" height="14" rx="2" fill={color} />
          )}
          {mark === "grid" ? (
            <path d="M5.5 3.5v9M3.5 5.5h9" stroke="white" strokeWidth="1.5" strokeLinecap="round" />
          ) : mark === "lines" || mark === "file" ? (
            <path
              d="M5 7.5h6M5 10h6M5 12.5h4"
              stroke="white"
              strokeWidth="1.3"
              strokeLinecap="round"
            />
          ) : mark === "slides" ? (
            <rect x="3.5" y="4.5" width="9" height="7" rx=".5" stroke="white" strokeWidth="1.5" />
          ) : (
            <text
              x="8"
              y={mark === "PDF" ? "10.5" : "12"}
              textAnchor="middle"
              fontFamily="Arial, sans-serif"
              fontSize={mark === "PDF" ? "6" : "11"}
              fontWeight="700"
              fill="white"
            >
              {mark}
            </text>
          )}
        </>
      )}
    </svg>
  );
}

export function GoogleDriveMimeIcon({ mimeType }: { mimeType: string }) {
  const type = rootTypes.get(mimeType) ?? defaultRootType;
  return (
    <span title={type.label} className="mt-0.5 shrink-0">
      <RootTypeIcon color={type.color} mark={type.mark} />
      <span className="sr-only">{type.label}: </span>
    </span>
  );
}

export function GoogleDriveLinks({
  scopeMode,
  policy,
  value,
  disabled,
  readOnly = false,
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
  inputRef?: Ref<HTMLTextAreaElement>;
  errorMessage?: string | null;
  onChange: (value: string) => void;
  onScopeModeChange?: (mode: GetGoogleDriveConfigurationResponse["scopeMode"]) => void;
}) {
  const id = useId();
  const links = parseGoogleDriveLinks(value);
  const error =
    errorMessage ??
    (policy && links.length > policy.maxExplicitRootsPerSource
      ? `Use at most ${policy.maxExplicitRootsPerSource.toLocaleString()} file or folder links.`
      : null);

  return (
    <div className="space-y-3">
      {onScopeModeChange ? (
        <>
          <div className="flex items-center gap-2">
            <h3 className="font-heading-h3 text-content-primary">Selected content</h3>
            <HelpPopover label="Selected content">
              <p>
                For Specific, paste file or folder links, one per line or separated by commas. Only
                selected files and folder contents are synced. Choose a folder or its descendants,
                not both. Links and Google access are checked when you save.
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
                : `Choose explicit file or folder links${policy ? ` (up to ${policy.maxExplicitRootsPerSource.toLocaleString()})` : ""}. Only those files and folder contents are synchronized.`}
            </p>
          </fieldset>
        </>
      ) : null}
      {scopeMode === "SPECIFIC" ? (
        <div>
          <label htmlFor={id} className="font-secondary-action text-content-primary">
            File or folder links
          </label>
          <textarea
            id={id}
            ref={inputRef}
            rows={readOnly ? Math.min(Math.max(links.length, 2), 6) : 5}
            value={value}
            disabled={disabled}
            readOnly={readOnly}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            aria-describedby={`${id}-count${error ? ` ${id}-error` : ""}`}
            aria-invalid={Boolean(error)}
            className={cn(
              "mt-2 h-auto py-2",
              readOnly
                ? "w-full min-w-0 resize-none rounded-lg border border-transparent bg-transparent px-3 text-sm text-content-secondary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus-ring"
                : cn(inputVariants(), "min-h-28 resize-y"),
            )}
            placeholder="https://drive.google.com/drive/folders/…"
            onChange={(event) => onChange(event.target.value)}
          />
          <p id={`${id}-count`} className="mt-1 text-xs text-content-muted" aria-live="polite">
            {links.length.toLocaleString()}
            {readOnly
              ? " links · Read only"
              : `${policy ? ` of ${policy.maxExplicitRootsPerSource.toLocaleString()}` : ""} explicit roots`}
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
