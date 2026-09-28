import type { ComponentType, ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { BrandLoader } from "@/components/brand-loader";
import { EmptyState } from "@/components/composites/empty-state";
import { Button } from "@/components/ui/button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listChatLibrarySourcesOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatLibrarySource } from "@/lib/hey-api/types.gen";
import { IDLE_SOURCE_POLL_MS } from "@/features/sources/shared/source-polling";
import type { SourceFilters } from "@/features/sources/source-filters";
import { SourceList } from "@/features/sources/source-list";

/** What a member sees of a Source's state: a Source still pausing already reads as paused, a deleting one is gone. */
const READABLE_STATUSES = ["NOT_STARTED", "INDEXING", "ACTIVE", "PAUSED", "FAILED"] as const;

/** Opens the documents of one Source wherever the page lists them. */
type DocumentsLink = ComponentType<{ sourceId: string; className?: string; children: ReactNode }>;

/**
 * The Sources a member may read from, as the Sources page lists them but without managing them: the same groups,
 * totals, statuses and access, counting only the documents this member may read. A Source's name leads to those
 * documents while Search serves them; a paused Source keeps its documents out of Search, so its name leads
 * nowhere until it resumes. Managing a Source stays on the administration pages. Nothing reports running work to a
 * member, so the list rereads slowly to notice a synchronization finishing.
 */
export function ReadableSources({
  search,
  onSearch,
  filters,
  onFilters,
  DocumentsLink,
}: {
  search: string;
  onSearch: (next: string) => void;
  filters: SourceFilters;
  onFilters: (next: SourceFilters) => void;
  DocumentsLink: DocumentsLink;
}) {
  const ui = useAppTranslation();
  const sources = useQuery({
    ...listChatLibrarySourcesOptions(),
    refetchInterval: IDLE_SOURCE_POLL_MS,
  });

  if (sources.isPending)
    return (
      <div className="flex min-h-52 items-center justify-center">
        <BrandLoader label={ui("Loading sources")} />
      </div>
    );
  if (sources.isError)
    return (
      <EmptyState
        role="alert"
        title={ui("Sources unavailable")}
        action={
          <Button prominence="secondary" size="sm" onClick={() => void sources.refetch()}>
            {ui("Try again")}
          </Button>
        }
      />
    );
  if (sources.data.length === 0)
    return (
      <EmptyState
        title={ui("No sources you can read yet")}
        detail={ui("Ask a Source manager or an administrator for access.")}
      />
    );
  return (
    <SourceList
      sources={sources.data}
      search={search}
      onSearch={onSearch}
      filters={filters}
      onFilters={onFilters}
      statuses={READABLE_STATUSES}
      documents={(source) => source.readableDocuments}
      documentsLabel={ui("Documents you can read")}
      documentsTotalLabel={ui("Documents you can read")}
      renderName={(source) => <SourceName source={source} DocumentsLink={DocumentsLink} />}
      renderAccess={(source) =>
        source.groups.length > 0 ? (
          <span
            className="mt-1 block truncate font-secondary-body text-content-muted"
            title={source.groups.join(", ")}
          >
            {source.groups.join(", ")}
          </span>
        ) : null
      }
    />
  );
}

/** The Source's name, leading to its documents while Search serves them, and who answers for it. */
function SourceName({
  source,
  DocumentsLink,
}: {
  source: ChatLibrarySource;
  DocumentsLink: DocumentsLink;
}) {
  const ui = useAppTranslation();
  const opens = source.status !== "PAUSED" && source.readableDocuments > 0;
  return (
    <span className="flex min-w-0 flex-col">
      {opens ? (
        <DocumentsLink
          sourceId={source.id}
          className="truncate text-sm font-medium text-content-primary hover:underline focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
        >
          {source.name}
        </DocumentsLink>
      ) : (
        <span className="truncate text-sm font-medium text-content-primary">{source.name}</span>
      )}
      {source.managerName ? (
        <span className="truncate font-secondary-body text-content-muted">
          {ui("Responsible manager: {{v1}}", { v1: source.managerName })}
        </span>
      ) : null}
    </span>
  );
}
