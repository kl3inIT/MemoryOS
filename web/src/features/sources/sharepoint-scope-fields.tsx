import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useId } from "react";
import { inputVariants } from "@/components/ui/input";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { HelpPopover } from "@/components/ui/help-popover";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Switch } from "@/components/ui/switch";
import type { SharePointSelectionPolicyResponse } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import {
  parseSharePointLines,
  sharePointAddressProblems,
  sharePointExclusionProblems,
  type SharePointLineProblem,
  type SharePointScopeDraft,
} from "./sharepoint-scope";

export function SharePointScopeFields({
  draft,
  policy,
  disabled,
  onChange,
}: {
  draft: SharePointScopeDraft;
  policy: SharePointSelectionPolicyResponse | undefined;
  disabled: boolean;
  onChange: (draft: SharePointScopeDraft) => void;
}) {
  const ui = useAppTranslation();

  const id = useId();
  const roots = parseSharePointLines(draft.siteUrlsText);
  const problems =
    draft.scopeMode === "ALL_SITES" ? [] : sharePointAddressProblems(draft.siteUrlsText);

  return (
    <div className="space-y-5">
      <div className="flex items-center gap-2">
        <h3 className="font-heading-h3 text-content-primary">{ui("Selected content")}</h3>
        <HelpPopover label={ui("Selected content")}>
          <p>
            {ui(
              "Paste addresses as they appear in the browser: a site, one of its document libraries, or a folder inside a library. Sharing links and library view addresses are accepted and reduced to the underlying path.",
            )}
          </p>
          <p>
            {ui(
              "A library is matched by the path in its URL, not by its displayed name, so a site created in another language resolves like any other. Every address must be on the same SharePoint host, and no address may sit inside another.",
            )}
          </p>
          <p>
            {ui(
              "Addresses are verified with Microsoft after you submit. Nothing is saved when one of them does not resolve.",
            )}
          </p>
        </HelpPopover>
      </div>
      <fieldset disabled={disabled} className="space-y-2">
        <legend className="font-secondary-action text-content-primary">{ui("Scope")}</legend>
        <RadioGroup
          className="flex flex-wrap gap-3"
          value={draft.scopeMode}
          onValueChange={(mode) =>
            onChange({ ...draft, scopeMode: mode as SharePointScopeDraft["scopeMode"] })
          }
        >
          {(["SPECIFIC", "ALL_SITES"] as const).map((mode) => (
            <label
              key={mode}
              className="flex min-h-11 cursor-pointer items-center gap-2 rounded-lg border border-border-default px-3 has-checked:bg-surface-subtle has-disabled:cursor-default"
            >
              <RadioGroupItem value={mode} aria-describedby={`${id}-scope-description`} />
              {mode === "ALL_SITES" ? ui("All sites") : ui("Specific sites")}
            </label>
          ))}
        </RadioGroup>
        <p id={`${id}-scope-description`} className="text-sm text-content-secondary">
          {draft.scopeMode === "ALL_SITES"
            ? ui(
                "Every site the Entra application can read in this directory, minus the exclusions below. New sites are picked up as they appear.",
              )
            : ui(
                "Only the sites, libraries and folders you paste{{v1}}, including everything inside them.",
                {
                  v1: policy
                    ? ui(" (up to {{count}})", {
                        count: policy.maxRootsPerSource.toLocaleString(uiLocale()),
                      })
                    : "",
                },
              )}
        </p>
      </fieldset>

      {draft.scopeMode === "SPECIFIC" ? (
        <div>
          <label htmlFor={`${id}-roots`} className="font-secondary-action text-content-primary">
            {ui("Site, library or folder addresses")}
          </label>
          <textarea
            id={`${id}-roots`}
            rows={5}
            value={draft.siteUrlsText}
            disabled={disabled}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            aria-describedby={`${id}-roots-count${problems.length ? ` ${id}-roots-problems` : ""}`}
            aria-invalid={problems.length > 0}
            className={cn(inputVariants(), "mt-2 h-auto min-h-28 resize-y py-2")}
            placeholder="https://contoso.sharepoint.com/sites/Finance"
            onChange={(event) => onChange({ ...draft, siteUrlsText: event.target.value })}
          />
          <p
            id={`${id}-roots-count`}
            className="mt-1 text-xs text-content-muted"
            aria-live="polite"
          >
            {policy
              ? ui("{{count}} of {{max}} addresses · one per line", {
                  count: roots.length.toLocaleString(uiLocale()),
                  max: policy.maxRootsPerSource.toLocaleString(uiLocale()),
                })
              : ui("{{count}} addresses · one per line", {
                  count: roots.length.toLocaleString(uiLocale()),
                })}
          </p>
          <LineProblems id={`${id}-roots-problems`} problems={problems} />
        </div>
      ) : null}

      <Collapsible className="rounded-xl border border-border-subtle bg-surface-raised">
        <CollapsibleTrigger className="w-full cursor-pointer rounded-xl px-4 py-3 text-left outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/30">
          <span className="block font-secondary-action text-content-primary">{ui("Advanced")}</span>
          <span className="mt-0.5 block font-secondary-body text-content-muted">
            {ui("What to collect, and what to leave out")}
          </span>
        </CollapsibleTrigger>
        <CollapsibleContent>
          <div className="space-y-5 border-t border-border-subtle p-4 sm:p-5">
            <div className="space-y-3">
              {(
                [
                  {
                    key: "includeDocuments",
                    label: "Documents",
                    description:
                      "Files in the document libraries covered by this scope, in the formats MemoryOS already supports.",
                  },
                  {
                    key: "includePages",
                    label: "Site pages",
                    description:
                      "Published SharePoint pages, read from their canvas: headings, paragraphs, list items and tables.",
                  },
                ] as const
              ).map((option) => (
                <label
                  key={option.key}
                  className="group/field-label flex cursor-pointer items-start justify-between gap-4 has-disabled:cursor-default"
                >
                  <span className="min-w-0">
                    <span className="block font-secondary-action text-content-primary">
                      {ui(option.label)}
                    </span>
                    <span className="mt-0.5 block font-secondary-body text-content-muted">
                      {ui(option.description)}
                    </span>
                  </span>
                  <Switch
                    checked={draft[option.key]}
                    disabled={disabled}
                    aria-label={ui(option.label)}
                    onCheckedChange={(checked) => onChange({ ...draft, [option.key]: checked })}
                  />
                </label>
              ))}
            </div>
            <ExclusionField
              id={`${id}-excluded-sites`}
              label={ui("Excluded sites")}
              help={ui(
                "One address or wildcard pattern per line, matched against site addresses. Example: https://contoso.sharepoint.com/sites/Archive*",
              )}
              placeholder={ui("https://contoso.sharepoint.com/sites/Archive*")}
              value={draft.excludedSitesText}
              policy={policy}
              disabled={disabled}
              onChange={(value) => onChange({ ...draft, excludedSitesText: value })}
            />
            <ExclusionField
              id={`${id}-excluded-paths`}
              label={ui("Excluded paths")}
              help={ui(
                "One wildcard pattern per line, matched case-insensitively against the path of each item. Example: */Archive/*",
              )}
              placeholder={ui("*/Archive/*")}
              value={draft.excludedPathsText}
              policy={policy}
              disabled={disabled}
              onChange={(value) => onChange({ ...draft, excludedPathsText: value })}
            />
          </div>
        </CollapsibleContent>
      </Collapsible>
    </div>
  );
}

function ExclusionField({
  id,
  label,
  help,
  placeholder,
  value,
  policy,
  disabled,
  onChange,
}: {
  id: string;
  label: string;
  help: string;
  placeholder: string;
  value: string;
  policy: SharePointSelectionPolicyResponse | undefined;
  disabled: boolean;
  onChange: (value: string) => void;
}) {
  const ui = useAppTranslation();

  const problems = sharePointExclusionProblems(value);
  const count = parseSharePointLines(value).length;
  const overLimit = Boolean(policy && count > policy.maxExclusionsPerKind);

  return (
    <div>
      <label htmlFor={id} className="font-secondary-action text-content-primary">
        {label}
      </label>
      <p className="mt-1 font-secondary-body text-content-muted">{help}</p>
      <textarea
        id={id}
        rows={3}
        value={value}
        disabled={disabled}
        autoComplete="off"
        autoCapitalize="off"
        spellCheck={false}
        aria-describedby={`${id}-count${problems.length ? ` ${id}-problems` : ""}`}
        aria-invalid={problems.length > 0 || overLimit}
        className={cn(inputVariants(), "mt-2 h-auto min-h-20 resize-y py-2")}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
      />
      <p id={`${id}-count`} className="mt-1 text-xs text-content-muted" aria-live="polite">
        {policy
          ? ui("{{count}} of {{max}} patterns", {
              count: count.toLocaleString(uiLocale()),
              max: policy.maxExclusionsPerKind.toLocaleString(uiLocale()),
            })
          : ui("{{count}} patterns", { count: count.toLocaleString(uiLocale()) })}
      </p>
      <LineProblems id={`${id}-problems`} problems={problems} />
    </div>
  );
}

function LineProblems({ id, problems }: { id: string; problems: SharePointLineProblem[] }) {
  const ui = useAppTranslation();

  if (problems.length === 0) return null;
  return (
    <ul id={id} className="mt-2 space-y-1">
      {problems.map((problem) => (
        <li
          key={`${problem.line}:${problem.value}`}
          role="alert"
          className="text-sm text-status-danger-content"
        >
          {ui("Line {{line}}: {{message}}", {
            line: problem.line,
            message: ui(problem.message),
          })}
        </li>
      ))}
    </ul>
  );
}
