import { useQuery } from "@tanstack/react-query";
import { statLabelClass, statValueClass } from "@/components/composites/stat-strip";
import type { ReactNode } from "react";
import { HelpPopover } from "@/components/ui/help-popover";
import { formatUiMoment, uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listSourceGroupsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { sourceAccessPresentation } from "./source-status-presentation";
import { SourceHint } from "./source-hint";

/** Who reads a provider Source that can follow the provider's own sharing; Public reads like any other. */
const providerAccessHelp: Partial<
  Record<SourceSummary["type"], Partial<Record<SourceSummary["access"], string>>>
> = {
  GOOGLE_DRIVE: {
    PRIVATE:
      "Document access follows this Source's MemoryOS groups, not Google Drive file permissions.",
    SYNC: "Whoever can open the file in Google Drive can read it.",
  },
  LARK: {
    PRIVATE: "Document access follows this Source's MemoryOS groups, not Lark sharing.",
    SYNC: "Whoever the document is shared with in Lark can read it.",
  },
  SHAREPOINT: {
    PRIVATE: "Document access follows this Source's MemoryOS groups, not SharePoint permissions.",
    SYNC: "Whoever can open the item in SharePoint can read it.",
  },
};

/** The short answer to who can read; the help beside it states the rule. */
const readersLabel: Record<SourceSummary["access"], string> = {
  PUBLIC: "All members",
  PRIVATE: "Members of the chosen groups",
  SYNC: "People with access in Google Drive",
};

/** The provider whose sharing a SYNC Source follows, where it is not Google Drive. */
const providerReadersLabel: Partial<Record<SourceSummary["type"], string>> = {
  LARK: "People with access in Lark",
  SHAREPOINT: "People with access in SharePoint",
};

const shownGroupCount = 3;

/**
 * Facts about a Source that its header badges do not carry: counts and schedule first, then who
 * reads it, as in Customer.io's integration details. Groups come from the query the groups
 * section also uses, so both read the same cache entry.
 */
export function SourceSummaryCard({
  source,
  header,
  className,
  children,
}: {
  source: SourceSummary;
  /** A title and actions above the facts. */
  header?: ReactNode;
  className?: string;
  /** Further facts, each a `div` holding a `dt` and a `dd`. */
  children?: ReactNode;
}) {
  const ui = useAppTranslation();
  // Only group access reads through groups, so other Sources have none to show.
  const showGroups = source.access === "PRIVATE";
  const groups = useQuery({
    ...listSourceGroupsOptions({ path: { sourceId: source.id } }),
    enabled: showGroups,
    retry: false,
  });
  const groupNames = (groups.data?.items ?? [])
    .filter((group) => group.systemKey === null)
    .map((group) => group.name);
  const shownGroupNames = groupNames.slice(0, shownGroupCount);
  const additionalGroupNames = groupNames.slice(shownGroupCount);
  const readers =
    providerAccessHelp[source.type]?.[source.access] ??
    sourceAccessPresentation[source.access].title;

  return (
    <div
      className={cn(
        "rounded-xl border border-border-subtle bg-surface-raised font-main-ui-body",
        className,
      )}
    >
      {header ? <div className="border-b border-border-subtle px-5 py-3">{header}</div> : null}
      <dl aria-label={ui("Source summary")}>
        <div
          className={cn(
            "grid gap-x-8 gap-y-5 px-5 py-5 sm:grid-cols-2",
            children ? "lg:grid-cols-3" : null,
          )}
        >
          <div>
            <dt className={statLabelClass}>{ui("Documents indexed")}</dt>
            <dd className={cn("mt-1", statValueClass)}>
              {source.documentCount.toLocaleString(uiLocale())}
            </dd>
          </div>
          <div>
            <dt className={statLabelClass}>{ui("Last indexed successfully")}</dt>
            <dd className={cn("mt-1 flex min-h-8 items-center", statValueClass)}>
              {source.lastSucceededAt ? (
                <time dateTime={source.lastSucceededAt}>
                  {formatUiMoment(source.lastSucceededAt)}
                </time>
              ) : (
                ui("Not yet")
              )}
            </dd>
          </div>
          {children}
        </div>
        <div className="flex flex-wrap gap-x-10 gap-y-2 border-t border-border-subtle px-5 py-2">
          <div className="flex min-w-0 items-center gap-2">
            <dt className={statLabelClass}>{ui("Who can read")}</dt>
            <dd className="flex min-w-0 items-center gap-0.5 text-content-primary">
              {ui(
                (source.access === "SYNC" ? providerReadersLabel[source.type] : undefined) ??
                  readersLabel[source.access],
              )}
              <HelpPopover label={ui("Who can read")}>
                <p>{ui(readers)}</p>
              </HelpPopover>
            </dd>
          </div>
          {showGroups ? (
            <div className="flex min-h-8 min-w-0 items-center gap-2">
              <dt className={statLabelClass}>{ui("Groups")}</dt>
              <dd className="min-w-0 break-words text-content-primary">
                {groups.isPending ? (
                  ui("Loading…")
                ) : groups.isError ? (
                  ui("Unavailable")
                ) : groupNames.length ? (
                  <>
                    {shownGroupNames.join(", ")}
                    {additionalGroupNames.length ? (
                      <>
                        {" "}
                        <SourceHint
                          hint={ui("Additional groups: {{v1}}", {
                            v1: additionalGroupNames.join(", "),
                          })}
                        >
                          <button
                            type="button"
                            aria-label={ui("Additional groups: {{v1}}", {
                              v1: additionalGroupNames.join(", "),
                            })}
                            className="cursor-default font-secondary-action text-content-secondary underline decoration-dotted underline-offset-2 outline-none focus-visible:rounded-sm focus-visible:ring-2 focus-visible:ring-focus-ring"
                          >
                            {ui("+{{count}} more groups", {
                              count: additionalGroupNames.length,
                            })}
                          </button>
                        </SourceHint>
                      </>
                    ) : null}
                  </>
                ) : (
                  ui("None")
                )}
              </dd>
            </div>
          ) : null}
        </div>
      </dl>
    </div>
  );
}
