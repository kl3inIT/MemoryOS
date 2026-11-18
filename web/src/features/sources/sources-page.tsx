import { useAppTranslation } from "@/i18n/use-app-translation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { Plug, Plus, Settings } from "lucide-react";
import { useEffect, useEffectEvent, useState } from "react";
import { BrandLoader } from "@/components/brand-loader";
import { EmptyState } from "@/components/composites/empty-state";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { IconButton } from "@/components/ui/icon-button";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useCapabilityAuthority } from "@/features/identity/application-session-context";
import { listSourcePageOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourcePage } from "@/lib/hey-api/types.gen";
import {
  sourceProviders,
  type SourceProvider,
} from "@/features/sources/shared/source-provider-catalog";
import { IDLE_SOURCE_POLL_MS } from "@/features/sources/shared/source-polling";
import type { SourceFilters } from "@/features/sources/source-filters";
import { SourceList } from "@/features/sources/source-list";
import { sourcePageQuery, type SourcesSearch } from "@/features/sources/sources-search";

export function SourcesPage() {
  const ui = useAppTranslation();

  const canCreate = useCapabilityAuthority("SOURCES_MANAGE") !== "none";
  const search = useSearch({ from: "/_authenticated/admin/" });
  const sourcesQuery = useQuery({
    ...listSourcePageOptions({ query: sourcePageQuery(search) }),
    retry: false,
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      query.state.data?.counts.pendingWork ? 1_500 : IDLE_SOURCE_POLL_MS,
  });
  const page = sourcesQuery.data;

  return (
    <SettingsLayout wide>
      <PageHeader
        icon={<Plug />}
        title={ui("Sources")}
        description={ui("Manage connected content and monitor indexing.")}
        actions={
          canCreate ? (
            <Button asChild>
              <Link to="/admin/sources/new">
                <Plus data-icon="inline-start" aria-hidden="true" />
                {ui("Add source")}
              </Link>
            </Button>
          ) : null
        }
      />

      {sourcesQuery.isPending ? (
        <div className="flex min-h-52 items-center justify-center">
          <BrandLoader label={ui("Loading sources")} />
        </div>
      ) : sourcesQuery.isError ? (
        <EmptyState
          role="alert"
          title={ui("Couldn't load sources")}
          action={
            <Button prominence="secondary" size="sm" onClick={() => void sourcesQuery.refetch()}>
              {ui("Try again")}
            </Button>
          }
        />
      ) : page === undefined ? null : page.counts.total === 0 ? (
        <SourcesEmpty canCreate={canCreate} />
      ) : (
        <AdminSourceList page={page} search={search} />
      )}
    </SettingsLayout>
  );
}

/** The action that starts each provider's setup, in the words of the first step. */
const emptyProviderActions: Record<SourceProvider["type"], string> = {
  GOOGLE_DRIVE: "Connect Google Drive",
  FILE: "Upload files",
  SHAREPOINT: "Connect SharePoint",
  LARK: "Connect Lark",
};

/**
 * The first run follows Fabric's "Let's add our first connection": the providers that can be
 * connected, one sentence on what they are for, and an action for each.
 */
function SourcesEmpty({ canCreate }: { canCreate: boolean }) {
  const ui = useAppTranslation();

  return (
    <Empty className="min-h-80">
      <EmptyHeader>
        <EmptyMedia>
          <span className="flex gap-3">
            {sourceProviders.map((provider) => {
              const ProviderIcon = provider.icon;
              return (
                <span
                  key={provider.type}
                  className="grid size-12 place-items-center rounded-2xl border border-border-subtle bg-surface-raised shadow-xs"
                >
                  <ProviderIcon className="size-6" aria-hidden="true" />
                </span>
              );
            })}
          </span>
        </EmptyMedia>
        <EmptyTitle>{ui("No sources yet")}</EmptyTitle>
        <EmptyDescription>
          {ui(
            "Connect Google Drive or upload files. MemoryOS keeps them indexed, so Search and Chat can cite them.",
          )}
        </EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        {canCreate ? (
          <div className="flex flex-wrap justify-center gap-2">
            {sourceProviders.map((provider) => {
              const ProviderIcon = provider.icon;
              return (
                <Button
                  key={provider.type}
                  asChild
                  size="sm"
                  prominence={provider.type === "GOOGLE_DRIVE" ? "primary" : "secondary"}
                >
                  <Link to={provider.setupPath}>
                    <ProviderIcon data-icon="inline-start" aria-hidden="true" />
                    {ui(emptyProviderActions[provider.type])}
                  </Link>
                </Button>
              );
            })}
          </div>
        ) : (
          <EmptyDescription>{ui("Ask a workspace manager to add a source.")}</EmptyDescription>
        )}
      </EmptyContent>
    </Empty>
  );
}

/**
 * One page of the Sources the administrator may see: each name opens its detail page, and a Source they hold any
 * authority over offers its settings. The search, the filters and the page live in the address; narrowing the
 * list returns to its first page.
 */
function AdminSourceList({ page, search }: { page: SourcePage; search: SourcesSearch }) {
  const ui = useAppTranslation();
  const navigate = useNavigate({ from: "/admin" });
  const { counts } = page;
  const filters: SourceFilters = {
    status: search.status ?? "",
    provider: search.provider ?? "",
    access: search.access ?? "",
  };

  // Typing applies the search once it pauses; only a settled draft applies, so history navigation is not undone.
  const appliedSearch = search.search ?? "";
  const [draft, setDraft] = useState({ applied: appliedSearch, value: appliedSearch });
  if (draft.applied !== appliedSearch) setDraft({ applied: appliedSearch, value: appliedSearch });
  const searchValue = draft.applied === appliedSearch ? draft.value : appliedSearch;
  const settledSearch = useDebouncedValue(searchValue.trim(), 250);
  const applySettledSearch = useEffectEvent((next: string) => {
    if (next === appliedSearch || next !== searchValue.trim()) return;
    narrow({ search: next || undefined }, true);
  });
  useEffect(() => applySettledSearch(settledSearch), [settledSearch]);

  // A page past the end, after Sources went away or an old link, returns to the last one.
  const lastPage = Math.max(page.totalPages - 1, 0);
  useEffect(() => {
    if (search.page > lastPage)
      void navigate({ replace: true, search: (current) => ({ ...current, page: lastPage }) });
  }, [navigate, search.page, lastPage]);

  function narrow(update: Partial<SourcesSearch>, replace = false) {
    void navigate({ replace, search: (current) => ({ ...current, ...update, page: 0 }) });
  }

  return (
    <SourceList
      sources={page.items}
      totals={{
        total: counts.total,
        documents: counts.documents,
        byStatus: {
          NOT_STARTED: counts.notStarted,
          INDEXING: counts.indexing,
          ACTIVE: counts.active,
          FAILED: counts.failed,
          PAUSED: counts.paused,
          PAUSING: counts.pausing,
          DELETING: counts.deleting,
        },
      }}
      search={searchValue}
      onSearch={(value) => setDraft({ applied: appliedSearch, value })}
      filters={filters}
      onFilters={(next) =>
        narrow({
          status: (next.status || undefined) as SourcesSearch["status"],
          provider: (next.provider || undefined) as SourcesSearch["provider"],
          access: (next.access || undefined) as SourcesSearch["access"],
        })
      }
      pager={{
        page: page.page,
        size: page.size,
        totalItems: page.totalItems,
        totalPages: page.totalPages,
        onPage: (next) => void navigate({ search: (current) => ({ ...current, page: next }) }),
      }}
      documents={(source) => source.documentCount}
      documentsLabel={ui("Total docs")}
      renderName={(source) => (
        <Link
          to="/admin/sources/$sourceId"
          params={{ sourceId: source.id }}
          className="text-sm font-medium text-content-primary hover:underline focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
        >
          {source.name}
        </Link>
      )}
      renderAction={(source) =>
        Object.values(source.permissions).some(Boolean) ? (
          <IconButton
            asChild
            size="sm"
            prominence="tertiary"
            aria-label={ui("Manage {{v1}}", { v1: source.name })}
          >
            <Link to="/admin/sources/$sourceId" params={{ sourceId: source.id }}>
              <Settings />
            </Link>
          </IconButton>
        ) : null
      }
    />
  );
}
