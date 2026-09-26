import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChevronDown, Globe2, ListTree } from "lucide-react";
import { useId } from "react";
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
  FieldTitle,
} from "@/components/ui/field";
import { Textarea } from "@/components/ui/textarea";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { HelpPopover } from "@/components/ui/help-popover";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Switch } from "@/components/ui/switch";
import type { SharePointSelectionPolicyResponse } from "@/lib/hey-api/types.gen";
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
    <FieldGroup>
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
      <FieldSet disabled={disabled}>
        <FieldLegend variant="label">{ui("Scope")}</FieldLegend>
        {/* Choice cards: the whole card is the radio's label. */}
        <RadioGroup
          value={draft.scopeMode}
          onValueChange={(mode) =>
            onChange({ ...draft, scopeMode: mode as SharePointScopeDraft["scopeMode"] })
          }
        >
          <div className="grid gap-3 sm:grid-cols-2">
            <FieldLabel htmlFor={`${id}-scope-specific`}>
              <Field orientation="horizontal">
                <RadioGroupItem id={`${id}-scope-specific`} value="SPECIFIC" />
                <FieldContent>
                  <FieldTitle>
                    <ListTree aria-hidden="true" className="size-4 text-content-secondary" />
                    {ui("Specific sites")}
                  </FieldTitle>
                  <FieldDescription>
                    {ui(
                      "Only the sites, libraries and folders you paste{{v1}}, including everything inside them.",
                      {
                        v1: policy
                          ? ui(" (up to {{count}})", {
                              count: policy.maxRootsPerSource.toLocaleString(uiLocale()),
                            })
                          : "",
                      },
                    )}
                  </FieldDescription>
                  <span className="font-mono text-xs text-content-secondary">
                    {ui("Works with Sites.Selected or Sites.Read.All")}
                  </span>
                </FieldContent>
              </Field>
            </FieldLabel>
            <FieldLabel htmlFor={`${id}-scope-all`}>
              <Field orientation="horizontal">
                <RadioGroupItem id={`${id}-scope-all`} value="ALL_SITES" />
                <FieldContent>
                  <FieldTitle>
                    <Globe2 aria-hidden="true" className="size-4 text-content-secondary" />
                    {ui("All sites")}
                  </FieldTitle>
                  <FieldDescription>
                    {ui(
                      "Every site the Entra application can read in this directory, minus the exclusions below. New sites are picked up as they appear.",
                    )}
                  </FieldDescription>
                  <span className="font-mono text-xs text-content-secondary">
                    {ui("Sites.Read.All")}
                  </span>
                </FieldContent>
              </Field>
            </FieldLabel>
          </div>
        </RadioGroup>
      </FieldSet>

      {draft.scopeMode === "SPECIFIC" ? (
        <Field data-invalid={problems.length > 0 || undefined}>
          <FieldLabel htmlFor={`${id}-roots`}>{ui("Site, library or folder addresses")}</FieldLabel>
          <Textarea
            id={`${id}-roots`}
            rows={5}
            value={draft.siteUrlsText}
            disabled={disabled}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            aria-describedby={`${id}-roots-count${problems.length ? ` ${id}-roots-problems` : ""}`}
            aria-invalid={problems.length > 0}
            className="min-h-28 resize-y"
            placeholder="https://contoso.sharepoint.com/sites/Finance"
            onChange={(event) => onChange({ ...draft, siteUrlsText: event.target.value })}
          />
          <p id={`${id}-roots-count`} className="text-xs text-content-muted" aria-live="polite">
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
        </Field>
      ) : null}

      <Collapsible className="group/advanced">
        <div className="rounded-xl border border-border-subtle bg-surface-raised">
          <CollapsibleTrigger asChild>
            <button
              type="button"
              className="flex w-full cursor-pointer items-center gap-3 rounded-xl px-4 py-3 text-left outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/30"
            >
              <span className="min-w-0 flex-1">
                <span className="block font-secondary-action text-content-primary">
                  {ui("Advanced")}
                </span>
                <span className="mt-0.5 block font-secondary-body text-content-muted">
                  {ui("What to collect, and what to leave out")}
                </span>
              </span>
              <ChevronDown
                aria-hidden="true"
                className="size-4 shrink-0 text-content-muted transition-transform group-data-[state=open]/advanced:rotate-180 motion-reduce:transition-none"
              />
            </button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <div className="flex flex-col gap-5 border-t border-border-subtle p-4 sm:p-5">
              <FieldGroup>
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
                  <Field key={option.key} orientation="horizontal">
                    <FieldContent>
                      <FieldLabel htmlFor={`${id}-${option.key}`}>{ui(option.label)}</FieldLabel>
                      <FieldDescription>{ui(option.description)}</FieldDescription>
                    </FieldContent>
                    <Switch
                      id={`${id}-${option.key}`}
                      checked={draft[option.key]}
                      disabled={disabled}
                      onCheckedChange={(checked) => onChange({ ...draft, [option.key]: checked })}
                    />
                  </Field>
                ))}
              </FieldGroup>
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
        </div>
      </Collapsible>
    </FieldGroup>
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
    <Field data-invalid={problems.length > 0 || overLimit || undefined}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <FieldDescription>{help}</FieldDescription>
      <Textarea
        id={id}
        rows={3}
        value={value}
        disabled={disabled}
        autoComplete="off"
        autoCapitalize="off"
        spellCheck={false}
        aria-describedby={`${id}-count${problems.length ? ` ${id}-problems` : ""}`}
        aria-invalid={problems.length > 0 || overLimit}
        className="min-h-20 resize-y"
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
      />
      <p id={`${id}-count`} className="text-xs text-content-muted" aria-live="polite">
        {policy
          ? ui("{{count}} of {{max}} patterns", {
              count: count.toLocaleString(uiLocale()),
              max: policy.maxExclusionsPerKind.toLocaleString(uiLocale()),
            })
          : ui("{{count}} patterns", { count: count.toLocaleString(uiLocale()) })}
      </p>
      <LineProblems id={`${id}-problems`} problems={problems} />
    </Field>
  );
}

function LineProblems({ id, problems }: { id: string; problems: SharePointLineProblem[] }) {
  const ui = useAppTranslation();

  if (problems.length === 0) return null;
  return (
    <ul id={id} className="flex flex-col gap-1">
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
