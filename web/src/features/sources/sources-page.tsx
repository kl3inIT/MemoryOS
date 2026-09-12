import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
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
import { useCapabilityAuthority } from "@/features/identity/application-session-context";
import { listSourcesOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { findSourceProvider } from "./source-provider-catalog";
import { SourceAccessBadge, SourceStatusBadge } from "./source-status-badge";

export function SourcesPage() {
  const ui = useAppTranslation();

  const canCreate = useCapabilityAuthority("SOURCES_MANAGE") !== "none";
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
          <span className="inline-flex items-center gap-2 text-sm text-content-muted">
            <LoaderCircle
              className="size-4 animate-spin motion-reduce:animate-none"
              aria-hidden="true"
            />
            {ui("Loading sources")}
          </span>
        </div>
      ) : sourcesQuery.isError ? (
        <div className="py-14 text-center">
          <h2 className="font-heading-h3 text-content-primary">{ui("Sources unavailable")}</h2>
          <Button
            prominence="secondary"
            size="sm"
            className="mt-4"
            onClick={() => void sourcesQuery.refetch()}
          >
            {ui("Try again")}
          </Button>
        </div>
      ) : sources.length === 0 ? (
        <div className="py-14 text-center">
          <span className="mx-auto grid size-10 place-items-center rounded-xl border border-border-subtle bg-surface-subtle text-content-secondary">
            <Files className="size-5" aria-hidden="true" />
          </span>
          <h2 className="mt-4 font-heading-h3 text-content-primary">{ui("No sources yet")}</h2>
          {canCreate ? (
            <Button asChild size="sm" className="mt-4">
              <Link to="/admin/sources/new">{ui("Add source")}</Link>
            </Button>
          ) : null}
        </div>
      ) : (
        <SourceList sources={sources} />
      )}
    </SettingsLayout>
  );
}

function SourceList({ sources }: { sources: SourceSummary[] }) {
  const ui = useAppTranslation();

  const [searchQuery, setSearchQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [providerFilter, setProviderFilter] = useState("");
  const [accessFilter, setAccessFilter] = useState("");
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [collapsedTypes, setCollapsedTypes] = useState<Set<string>>(() => new Set());
  const filteredSources = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    return sources.filter((source) => {
      const providerName = ui(findSourceProvider(source.type)?.name ?? source.type);
      return (
        (!query ||
          source.name.toLowerCase().includes(query) ||
          providerName.toLowerCase().includes(query)) &&
        (!statusFilter || source.status === statusFilter) &&
        (!providerFilter || source.type === providerFilter) &&
        (!accessFilter || source.access === accessFilter)
      );
    });
  }, [accessFilter, providerFilter, searchQuery, sources, statusFilter, ui]);
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
          placeholder={ui("Search sources")}
          aria-label={ui("Search sources")}
          className="min-w-40 flex-1 bg-surface-sunken"
          onChange={(event) => setSearchQuery(event.target.value)}
        />
        <Button size="sm" prominence="tertiary" onClick={toggleAll}>
          {hasExpandedGroups ? ui("Collapse all") : ui("Expand all")}
        </Button>
        <IconButton
          size="sm"
          prominence={filtersOpen || hasActiveFilters ? "secondary" : "tertiary"}
          aria-label={ui("Filter sources")}
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
            {ui("Status")}
            <Select
              size="sm"
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value)}
            >
              <option value="">{ui("All statuses")}</option>
              <option value="NOT_STARTED">{ui("Scheduled")}</option>
              <option value="INDEXING">{ui("Indexing")}</option>
              <option value="ACTIVE">{ui("Active")}</option>
              <option value="FAILED">{ui("Failed")}</option>
              <option value="DELETING">{ui("Deleting")}</option>
            </Select>
          </label>
          <label className="grid gap-1.5 font-secondary-action text-content-secondary">
            {ui("Provider")}
            <Select
              size="sm"
              value={providerFilter}
              onChange={(event) => setProviderFilter(event.target.value)}
            >
              <option value="">{ui("All providers")}</option>
              <option value="FILE">{ui("File")}</option>
              <option value="GOOGLE_DRIVE">{ui("Google Drive")}</option>
            </Select>
          </label>
          <label className="grid gap-1.5 font-secondary-action text-content-secondary">
            {ui("Access")}
            <Select
              size="sm"
              value={accessFilter}
              onChange={(event) => setAccessFilter(event.target.value)}
            >
              <option value="">{ui("All access")}</option>
              <option value="PUBLIC">{ui("Workspace members")}</option>
              <option value="RESTRICTED">{ui("Restricted")}</option>
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
            {ui("Clear filters")}
          </Button>
        </div>
      ) : null}

      <div
        className="relative overflow-x-auto rounded-lg"
        tabIndex={0}
        role="region"
        aria-label={ui("Connected sources table")}
      >
        <table className="w-full min-w-[64rem] table-fixed border-collapse">
          <caption className="sr-only">{ui("Connected sources")}</caption>
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
                  {ui("No sources match your search and filters.")}
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
  const ui = useAppTranslation();

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
            aria-label={ui("{{v1}} group, {{v2}} sources, {{v3}} documents", {
              v1: ui(provider?.name ?? group.type),
              v2: group.sources.length,
              v3: documentCount,
            })}
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
              {ui(provider?.name ?? group.type)}
            </span>
          </button>
        </th>
        <SummaryMetric label={ui("Total sources")} value={group.sources.length} />
        <SummaryMetric
          label={ui("Active sources")}
          value={`${activeCount}/${group.sources.length}`}
        />
        <SummaryMetric
          label={ui("Workspace-visible sources")}
          value={`${workspaceAccessCount}/${group.sources.length}`}
        />
        <SummaryMetric label={ui("Total docs indexed")} value={documentCount} />
        <td className="border-y border-r border-border-subtle" />
      </tr>
      {!collapsed ? (
        <>
          <tr className="h-[42px] border-x border-b border-border-subtle text-left">
            <SourceColumnHeader>{ui("Name")}</SourceColumnHeader>
            <SourceColumnHeader>{ui("Last indexed")}</SourceColumnHeader>
            <SourceColumnHeader>{ui("Status")}</SourceColumnHeader>
            <SourceColumnHeader>{ui("Access")}</SourceColumnHeader>
            <SourceColumnHeader>{ui("Total docs")}</SourceColumnHeader>
            <SourceColumnHeader>
              <span className="sr-only">{ui("Manage")}</span>
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
  const ui = useAppTranslation();

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
        {source.actions.length > 0 ? (
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
        ) : null}
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

function LastIndexed({ value }: { value: string | null }) {
  if (!value) return <>-</>;
  const date = new Date(value);
  return (
    <time dateTime={value} title={date.toLocaleString(uiLocale())}>
      {new Intl.DateTimeFormat(uiLocale(), { dateStyle: "medium" }).format(date)}
    </time>
  );
}
