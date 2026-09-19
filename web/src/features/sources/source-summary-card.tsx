import { useQuery } from "@tanstack/react-query";
import { statLabelClass, statValueClass } from "@/components/composites/stat-strip";
import type { ReactNode } from "react";
import { HelpPopover } from "@/components/ui/help-popover";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listSourceGroupsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { sourceAccessPresentation } from "./source-status-presentation";

/** Who reads a Google Drive Source depends on its mode; Public reads like any other Source. */
const googleDriveAccessHelp: Partial<Record<SourceSummary["access"], string>> = {
  PRIVATE:
    "Document access follows this Source's MemoryOS groups, not Google Drive file permissions.",
  SYNC: "Readers need access to each file in Google Drive and a verified login email that matches it. Groups only decide who manages this Source.",
};

/** The short answer to who can read; the help beside it states the rule. */
const readersLabel: Record<SourceSummary["access"], string> = {
  PUBLIC: "Workspace members",
  PRIVATE: "Members of its groups",
  SYNC: "People with access in Google Drive",
};

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
  const groups = useQuery({
    ...listSourceGroupsOptions({ path: { sourceId: source.id } }),
    retry: false,
  });
  const groupNames = (groups.data?.items ?? [])
    .filter((group) => group.systemKey === null)
    .map((group) => group.name);
  const readers =
    (source.type === "GOOGLE_DRIVE" ? googleDriveAccessHelp[source.access] : undefined) ??
    sourceAccessPresentation[source.access].title;

  return (
    <div
      className={cn("rounded-xl border border-border-subtle bg-surface-raised text-sm", className)}
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
            <dt className="text-content-muted">{ui("Last indexed successfully")}</dt>
            <dd className="mt-1 flex min-h-8 items-center text-content-primary">
              {source.lastSucceededAt ? (
                <time dateTime={source.lastSucceededAt}>
                  {new Date(source.lastSucceededAt).toLocaleString(uiLocale())}
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
            <dt className="text-content-muted">{ui("Who can read")}</dt>
            <dd className="flex min-w-0 items-center gap-0.5 text-content-primary">
              {ui(readersLabel[source.access])}
              <HelpPopover label={ui("Who can read")}>
                <p>{ui(readers)}</p>
              </HelpPopover>
            </dd>
          </div>
          <div className="flex min-h-8 min-w-0 items-center gap-2">
            <dt className="text-content-muted">{ui("Groups")}</dt>
            <dd className="min-w-0 break-words text-content-primary">
              {groups.isPending
                ? ui("Loading…")
                : groups.isError
                  ? ui("Unavailable")
                  : groupNames.length
                    ? groupNames.join(", ")
                    : ui("None")}
            </dd>
          </div>
        </div>
      </dl>
    </div>
  );
}
