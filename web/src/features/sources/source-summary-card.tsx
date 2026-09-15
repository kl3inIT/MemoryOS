import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
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

/**
 * Facts about a Source that its header badges do not carry. Groups come from the query the
 * groups section also uses, so both read the same cache entry.
 */
export function SourceSummaryCard({
  source,
  children,
}: {
  source: SourceSummary;
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
    <dl
      aria-label={ui("Source summary")}
      className={cn(
        "my-6 grid grid-cols-2 gap-x-8 gap-y-5 rounded-lg border border-border-subtle px-4 py-5 text-sm lg:gap-x-12",
        children ? "lg:grid-cols-3" : "lg:grid-cols-4",
      )}
    >
      <div className="col-span-2 lg:col-span-1">
        <dt className="text-content-muted">{ui("Who can read")}</dt>
        <dd className="mt-2 text-content-primary">{ui(readers)}</dd>
      </div>
      <div className="col-span-2 min-w-0 lg:col-span-1">
        <dt className="text-content-muted">{ui("Groups")}</dt>
        <dd className="mt-2 break-words text-content-primary">
          {groups.isPending
            ? ui("Loading…")
            : groups.isError
              ? ui("Unavailable")
              : groupNames.length
                ? groupNames.join(", ")
                : ui("None")}
        </dd>
      </div>
      <div>
        <dt className="text-content-muted">{ui("Documents indexed")}</dt>
        <dd className="mt-2 text-lg font-semibold tabular-nums text-content-primary">
          {source.documentCount}
        </dd>
      </div>
      <div>
        <dt className="text-content-muted">{ui("Last indexed successfully")}</dt>
        <dd className="mt-2 text-content-primary">
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
    </dl>
  );
}
