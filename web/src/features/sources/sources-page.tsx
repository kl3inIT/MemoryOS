import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { BookOpen, Settings } from "lucide-react";
import { useState } from "react";
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
import { useCapabilityAuthority } from "@/features/identity/application-session-context";
import { listSourcesOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import {
  sourceProviders,
  type SourceProvider,
} from "@/features/sources/shared/source-provider-catalog";
import { IDLE_SOURCE_POLL_MS } from "@/features/sources/shared/source-polling";
import { noSourceFilters } from "@/features/sources/source-filters";
import { SourceList } from "@/features/sources/source-list";

export function SourcesPage() {
  const ui = useAppTranslation();

  const canCreate = useCapabilityAuthority("SOURCES_MANAGE") !== "none";
  const sourcesQuery = useQuery({
    ...listSourcesOptions(),
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.some((source) => source.pendingWork) ? 1_500 : IDLE_SOURCE_POLL_MS,
  });
  const sources = sourcesQuery.data ?? [];

  return (
    <SettingsLayout wide>
      <PageHeader
        icon={<BookOpen />}
        title={ui("Existing sources")}
        description={ui("Manage connected content and monitor indexing.")}
        actions={
          canCreate ? (
            <Button asChild>
              <Link to="/admin/sources/new">{ui("Add source")}</Link>
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
          title={ui("Sources unavailable")}
          action={
            <Button prominence="secondary" size="sm" onClick={() => void sourcesQuery.refetch()}>
              {ui("Try again")}
            </Button>
          }
        />
      ) : sources.length === 0 ? (
        <SourcesEmpty canCreate={canCreate} />
      ) : (
        <AdminSourceList sources={sources} />
      )}
    </SettingsLayout>
  );
}

/** The action that starts each provider's setup, in the words of the first step. */
const emptyProviderActions: Record<SourceProvider["type"], string> = {
  GOOGLE_DRIVE: "Connect Google Drive",
  FILE: "Upload files",
  SHAREPOINT: "Connect SharePoint",
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
 * Every Source the administrator may see: each name opens its detail page, and a Source they hold any authority
 * over offers its settings.
 */
function AdminSourceList({ sources }: { sources: SourceSummary[] }) {
  const ui = useAppTranslation();
  const [search, setSearch] = useState("");
  const [filters, setFilters] = useState(noSourceFilters);

  return (
    <SourceList
      sources={sources}
      search={search}
      onSearch={setSearch}
      filters={filters}
      onFilters={setFilters}
      documents={(source) => source.documentCount}
      documentsLabel={ui("Total docs")}
      documentsTotalLabel={ui("Total docs indexed")}
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
