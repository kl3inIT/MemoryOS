import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import {
  BookOpen,
  ChevronDown,
  ChevronRight,
  Files,
  ListFilter,
  LoaderCircle,
  Settings,
} from "lucide-react";
import { useMemo, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { listSourcesOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { findSourceProvider } from "./source-provider-catalog";
import { SourceAccessBadge, SourceStatusBadge } from "./source-status-badge";

export function SourcesPage() {
  const sourcesQuery = useQuery({
    ...listSourcesOptions(),
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.some((source) => source.pendingWork) ? 1_500 : false,
  });
  const sources = sourcesQuery.data ?? [];

  return (
    <SettingsLayout wide>
      <PageHeader
        icon={<BookOpen />}
        title="Existing sources"
        description="Manage connected content and monitor indexing."
        actions={
          <Button asChild>
            <Link to="/admin/sources/new">Add source</Link>
          </Button>
        }
      />

      {sourcesQuery.isPending ? (
        <div className="flex min-h-52 items-center justify-center">
          <span className="inline-flex items-center gap-2 text-sm text-content-muted">
            <LoaderCircle
              className="size-4 animate-spin motion-reduce:animate-none"
              aria-hidden="true"
            />
            Loading sources
          </span>
        </div>
      ) : sourcesQuery.isError ? (
        <div className="py-14 text-center">
          <h2 className="font-heading-h3 text-content-primary">Sources unavailable</h2>
          <Button
            prominence="secondary"
            size="sm"
            className="mt-4"
            onClick={() => void sourcesQuery.refetch()}
          >
            Try again
          </Button>
        </div>
      ) : sources.length === 0 ? (
        <div className="py-14 text-center">
          <span className="mx-auto grid size-10 place-items-center rounded-xl border border-border-subtle bg-surface-subtle text-content-secondary">
            <Files className="size-5" aria-hidden="true" />
          </span>
          <h2 className="mt-4 font-heading-h3 text-content-primary">No sources yet</h2>
          <Button asChild size="sm" className="mt-4">
            <Link to="/admin/sources/new">Add source</Link>
          </Button>
        </div>
      ) : (
        <SourceList sources={sources} />
      )}
    </SettingsLayout>
  );
}

function SourceList({ sources }: { sources: SourceSummary[] }) {
  const [searchQuery, setSearchQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [providerFilter, setProviderFilter] = useState("");
  const [accessFilter, setAccessFilter] = useState("");
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [collapsedTypes, setCollapsedTypes] = useState<Set<string>>(() => new Set());
  const filteredSources = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    return sources.filter((source) => {
      const providerName = findSourceProvider(source.type)?.name ?? source.type;
      return (
        (!query ||
          source.name.toLowerCase().includes(query) ||
          providerName.toLowerCase().includes(query)) &&
        (!statusFilter || source.status === statusFilter) &&
        (!providerFilter || source.type === providerFilter) &&
        (!accessFilter || source.access === accessFilter)
      );
    });
  }, [accessFilter, providerFilter, searchQuery, sources, statusFilter]);
  const groups = useMemo(() => groupSources(filteredSources), [filteredSources]);
  const hasExpandedGroups = groups.some((group) => !collapsedTypes.has(group.type));
  const hasActiveFilters = Boolean(statusFilter || providerFilter || accessFilter);

  function toggle(type: string) {
    setCollapsedTypes((current) => {
      const next = new Set(current);
      if (next.has(type)) next.delete(type);
      else next.add(type);
      return next;
    });
  }

  function toggleAll() {
    setCollapsedTypes(hasExpandedGroups ? new Set(groups.map((group) => group.type)) : new Set());
  }

  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        <Input
          type="search"
          size="sm"
          value={searchQuery}
          placeholder="Search sources"
          aria-label="Search sources"
          className="min-w-40 flex-1 bg-surface-sunken"
          onChange={(event) => setSearchQuery(event.target.value)}
        />
        <Button size="sm" prominence="tertiary" onClick={toggleAll}>
          {hasExpandedGroups ? "Collapse all" : "Expand all"}
        </Button>
        <IconButton
          size="sm"
          prominence={filtersOpen || hasActiveFilters ? "secondary" : "tertiary"}
          aria-label="Filter sources"
          aria-expanded={filtersOpen}
          aria-controls="source-filters"
          onClick={() => setFiltersOpen((open) => !open)}
        >
          <ListFilter />
        </IconButton>
      </div>

      {filtersOpen ? (
        <div
          id="source-filters"
          className="mt-2 grid gap-3 border border-border-subtle bg-surface-raised p-4 sm:grid-cols-2 sm:items-end lg:grid-cols-[repeat(3,minmax(0,1fr))_auto]"
        >
          <label className="grid gap-1.5 font-secondary-action text-content-secondary">
            Status
            <Select
              size="sm"
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value)}
            >
              <option value="">All statuses</option>
              <option value="NOT_STARTED">Scheduled</option>
              <option value="INDEXING">Indexing</option>
              <option value="ACTIVE">Active</option>
              <option value="FAILED">Failed</option>
              <option value="DELETING">Deleting</option>
            </Select>
          </label>
          <label className="grid gap-1.5 font-secondary-action text-content-secondary">
            Provider
            <Select
              size="sm"
              value={providerFilter}
              onChange={(event) => setProviderFilter(event.target.value)}
            >
              <option value="">All providers</option>
              <option value="FILE">File</option>
              <option value="GOOGLE_DRIVE">Google Drive</option>
            </Select>
          </label>
          <label className="grid gap-1.5 font-secondary-action text-content-secondary">
            Access
            <Select
              size="sm"
              value={accessFilter}
              onChange={(event) => setAccessFilter(event.target.value)}
            >
              <option value="">All access</option>
              <option value="PUBLIC">Workspace members</option>
              <option value="RESTRICTED">Restricted</option>
            </Select>
          </label>
          <Button
            size="sm"
            prominence="tertiary"
            disabled={!hasActiveFilters}
            onClick={() => {
              setStatusFilter("");
              setProviderFilter("");
              setAccessFilter("");
            }}
          >
            Clear filters
          </Button>
        </div>
      ) : null}

      <div
        className="relative overflow-x-auto rounded-lg"
        tabIndex={0}
        role="region"
        aria-label="Connected sources table"
      >
        <table className="w-full min-w-[64rem] table-fixed border-collapse">
          <caption className="sr-only">Connected sources</caption>
          <colgroup>
            <col />
            <col className="w-40" />
            <col className="w-40" />
            <col className="w-60" />
            <col className="w-44" />
            <col className="w-16" />
          </colgroup>
          {groups.map((group) => (
            <SourceGroupBody
              key={group.type}
              group={group}
              collapsed={collapsedTypes.has(group.type)}
              onToggle={() => toggle(group.type)}
            />
          ))}
          {groups.length === 0 ? (
            <tbody>
              <tr className="border border-border-subtle">
                <td colSpan={6} className="px-4 py-12 text-center text-sm text-content-muted">
                  No sources match your search and filters.
                </td>
              </tr>
            </tbody>
          ) : null}
        </table>
      </div>
    </>
  );
}

type SourceGroup = {
  type: string;
  sources: SourceSummary[];
};

function SourceGroupBody({
  group,
  collapsed,
  onToggle,
}: {
  group: SourceGroup;
  collapsed: boolean;
  onToggle: () => void;
}) {
  const provider = findSourceProvider(group.type);
  const ProviderIcon = provider?.icon ?? Files;
  const documentCount = group.sources.reduce((total, source) => total + source.documentCount, 0);
  const activeCount = group.sources.filter((source) => source.status === "ACTIVE").length;
  const workspaceAccessCount = group.sources.filter((source) => source.access === "PUBLIC").length;

  return (
    <tbody>
      <tr aria-hidden="true">
        <td colSpan={6} className="h-4 p-0" />
      </tr>
      <tr className="h-[72px] bg-surface-raised">
        <th scope="rowgroup" className="border-y border-l border-border-subtle px-4 text-left">
          <button
            type="button"
            aria-expanded={!collapsed}
            aria-label={`${provider?.name ?? group.type} group, ${group.sources.length} sources, ${documentCount} documents`}
            onClick={onToggle}
            className="flex h-full w-full items-center gap-2 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
          >
            {collapsed ? (
              <ChevronRight className="size-4 text-content-secondary" aria-hidden="true" />
            ) : (
              <ChevronDown className="size-4 text-content-secondary" aria-hidden="true" />
            )}
            <ProviderIcon className="size-5 text-content-secondary" aria-hidden="true" />
            <span className="text-xl font-semibold text-content-primary">
              {provider?.name ?? group.type}
            </span>
          </button>
        </th>
        <SummaryMetric label="Total sources" value={group.sources.length} />
        <SummaryMetric label="Active sources" value={`${activeCount}/${group.sources.length}`} />
        <SummaryMetric
          label="Workspace-visible sources"
          value={`${workspaceAccessCount}/${group.sources.length}`}
        />
        <SummaryMetric label="Total docs indexed" value={documentCount} />
        <td className="border-y border-r border-border-subtle" />
      </tr>
      {!collapsed ? (
        <>
          <tr className="h-[42px] border-x border-b border-border-subtle text-left">
            <SourceColumnHeader>Name</SourceColumnHeader>
            <SourceColumnHeader>Last indexed</SourceColumnHeader>
            <SourceColumnHeader>Status</SourceColumnHeader>
            <SourceColumnHeader>Access</SourceColumnHeader>
            <SourceColumnHeader>Total docs</SourceColumnHeader>
            <SourceColumnHeader>
              <span className="sr-only">Manage</span>
            </SourceColumnHeader>
          </tr>
          {group.sources.map((source) => (
            <SourceRow key={source.id} source={source} />
          ))}
        </>
      ) : null}
    </tbody>
  );
}

function SummaryMetric({ label, value }: { label: string; value: string | number }) {
  return (
    <td className="border-y border-border-subtle px-4">
      <span className="block text-sm whitespace-nowrap text-content-muted">{label}</span>
      <span className="mt-1 block text-xl font-semibold tabular-nums text-content-primary">
        {value}
      </span>
    </td>
  );
}

function SourceColumnHeader({ children }: { children: ReactNode }) {
  return (
    <th scope="col" className="px-4 text-sm font-medium whitespace-nowrap text-content-muted">
      {children}
    </th>
  );
}
function SourceRow({ source }: { source: SourceSummary }) {
  return (
    <tr
      id={`source-${source.id}`}
      className="h-[60px] border-x border-b border-border-subtle hover:bg-surface-subtle/50"
    >
      <td className="px-4">
        <Link
          to="/admin/sources/$sourceId"
          params={{ sourceId: source.id }}
          className="text-sm font-medium text-content-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
        >
          {source.name}
        </Link>
      </td>
      <td className="px-4 font-secondary-body text-content-muted">
        <LastIndexed value={source.lastSucceededAt} />
      </td>
      <td className="px-4">
        <SourceStatusBadge status={source.status} />
      </td>
      <td className="px-4">
        <SourceAccessBadge access={source.access} />
      </td>
      <td className="px-4 text-sm tabular-nums text-content-secondary">{source.documentCount}</td>
      <td className="px-4 text-center">
        <IconButton asChild size="sm" prominence="tertiary" aria-label={`Manage ${source.name}`}>
          <Link to="/admin/sources/$sourceId" params={{ sourceId: source.id }}>
            <Settings />
          </Link>
        </IconButton>
      </td>
    </tr>
  );
}

function groupSources(sources: SourceSummary[]) {
  const groups = new Map<string, SourceSummary[]>();
  for (const source of sources) {
    const group = groups.get(source.type);
    if (group) group.push(source);
    else groups.set(source.type, [source]);
  }
  return Array.from(groups, ([type, groupedSources]) => ({ type, sources: groupedSources }));
}

const sourceDateFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

function LastIndexed({ value }: { value: string | null }) {
  if (!value) return <>-</>;
  const date = new Date(value);
  return (
    <time dateTime={value} title={date.toLocaleString()}>
      {sourceDateFormatter.format(date)}
    </time>
  );
}
