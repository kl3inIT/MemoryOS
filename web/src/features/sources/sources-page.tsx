import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { BookOpen, ChevronDown, ChevronRight, Files, ListFilter, Settings } from "lucide-react";
import { useMemo, useState, type ReactNode } from "react";
import { BrandLoader } from "@/components/brand-loader";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableRow,
} from "@/components/ui/table";
import { useCapabilityAuthority } from "@/features/identity/application-session-context";
import { listSourcesOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { findSourceProvider } from "./source-provider-catalog";
import { SourceAccessBadge, SourceStatusBadge } from "./source-status-badge";

/** Radix selects reject an empty option value, so "any" stands for an unset filter. */
const anyFilterValue = "any";

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
          <BrandLoader label={ui("Loading sources")} />
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
        <Button size="sm" prominence="secondary" onClick={toggleAll}>
          {hasExpandedGroups ? ui("Collapse all") : ui("Expand all")}
        </Button>
        <IconButton
          size="sm"
          prominence="secondary"
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
              value={statusFilter || anyFilterValue}
              onValueChange={(next) => setStatusFilter(next === anyFilterValue ? "" : next)}
            >
              <SelectTrigger size="sm" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={anyFilterValue}>{ui("All statuses")}</SelectItem>
                <SelectItem value="NOT_STARTED">{ui("Scheduled")}</SelectItem>
                <SelectItem value="INDEXING">{ui("Indexing")}</SelectItem>
                <SelectItem value="ACTIVE">{ui("Active")}</SelectItem>
                <SelectItem value="FAILED">{ui("Failed")}</SelectItem>
                <SelectItem value="DELETING">{ui("Deleting")}</SelectItem>
              </SelectContent>
            </Select>
          </label>
          <label className="grid gap-1.5 font-secondary-action text-content-secondary">
            {ui("Provider")}
            <Select
              value={providerFilter || anyFilterValue}
              onValueChange={(next) => setProviderFilter(next === anyFilterValue ? "" : next)}
            >
              <SelectTrigger size="sm" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={anyFilterValue}>{ui("All providers")}</SelectItem>
                <SelectItem value="FILE">{ui("File")}</SelectItem>
                <SelectItem value="GOOGLE_DRIVE">{ui("Google Drive")}</SelectItem>
              </SelectContent>
            </Select>
          </label>
          <label className="grid gap-1.5 font-secondary-action text-content-secondary">
            {ui("Access")}
            <Select
              value={accessFilter || anyFilterValue}
              onValueChange={(next) => setAccessFilter(next === anyFilterValue ? "" : next)}
            >
              <SelectTrigger size="sm" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={anyFilterValue}>{ui("All access")}</SelectItem>
                <SelectItem value="PUBLIC">{ui("Workspace members")}</SelectItem>
                <SelectItem value="PRIVATE">{ui("Private")}</SelectItem>
                <SelectItem value="SYNC">{ui("Auto Sync")}</SelectItem>
              </SelectContent>
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
        <Table className="w-full min-w-[74rem] table-fixed border-collapse">
          <TableCaption className="sr-only">{ui("Connected sources")}</TableCaption>
          <colgroup>
            <col />
            <col className="w-44" />
            <col className="w-44" />
            <col className="w-72" />
            <col className="w-48" />
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
            <TableBody>
              <TableRow className="border border-border-subtle">
                <TableCell
                  colSpan={6}
                  className="px-4 py-12 text-center text-sm text-content-muted"
                >
                  {ui("No sources match your search and filters.")}
                </TableCell>
              </TableRow>
            </TableBody>
          ) : null}
        </Table>
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
    <TableBody>
      <TableRow aria-hidden="true">
        <TableCell colSpan={6} className="h-4 p-0" />
      </TableRow>
      <TableRow
        className="h-[72px] cursor-pointer bg-surface-raised transition-colors hover:bg-surface-subtle/70"
        onClick={onToggle}
      >
        <TableHead
          scope="rowgroup"
          className="border-y border-l border-border-subtle px-4 text-left"
        >
          <button
            type="button"
            aria-expanded={!collapsed}
            aria-label={ui("{{v1}} group, {{v2}} sources, {{v3}} documents", {
              v1: ui(provider?.name ?? group.type),
              v2: group.sources.length,
              v3: documentCount,
            })}
            onClick={(event) => {
              event.stopPropagation();
              onToggle();
            }}
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
        </TableHead>
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
        <TableCell className="border-y border-r border-border-subtle" />
      </TableRow>
      {!collapsed ? (
        <>
          <TableRow className="h-[42px] border-x border-b border-border-subtle text-left">
            <SourceColumnHeader>{ui("Name")}</SourceColumnHeader>
            <SourceColumnHeader>{ui("Last indexed")}</SourceColumnHeader>
            <SourceColumnHeader>{ui("Status")}</SourceColumnHeader>
            <SourceColumnHeader>{ui("Access")}</SourceColumnHeader>
            <SourceColumnHeader>{ui("Total docs")}</SourceColumnHeader>
            <SourceColumnHeader>
              <span className="sr-only">{ui("Manage")}</span>
            </SourceColumnHeader>
          </TableRow>
          {group.sources.map((source) => (
            <SourceRow key={source.id} source={source} />
          ))}
        </>
      ) : null}
    </TableBody>
  );
}

function SummaryMetric({ label, value }: { label: string; value: string | number }) {
  return (
    <TableCell className="border-y border-border-subtle px-4">
      <span className="block text-sm whitespace-nowrap text-content-muted">{label}</span>
      <span className="mt-1 block text-xl font-semibold tabular-nums text-content-primary">
        {value}
      </span>
    </TableCell>
  );
}

function SourceColumnHeader({ children }: { children: ReactNode }) {
  return (
    <TableHead
      scope="col"
      className="px-4 text-sm font-medium whitespace-nowrap text-content-muted"
    >
      {children}
    </TableHead>
  );
}
function SourceRow({ source }: { source: SourceSummary }) {
  const ui = useAppTranslation();

  return (
    <TableRow
      id={`source-${source.id}`}
      className="h-[60px] border-x border-b border-border-subtle hover:bg-surface-subtle/50"
    >
      <TableCell className="px-4">
        <Link
          to="/admin/sources/$sourceId"
          params={{ sourceId: source.id }}
          className="text-sm font-medium text-content-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
        >
          {source.name}
        </Link>
      </TableCell>
      <TableCell className="px-4 font-secondary-body text-content-muted">
        <LastIndexed value={source.lastSucceededAt} />
      </TableCell>
      <TableCell className="px-4">
        <SourceStatusBadge status={source.status} />
      </TableCell>
      <TableCell className="px-4">
        <SourceAccessBadge access={source.access} />
      </TableCell>
      <TableCell className="px-4 text-sm tabular-nums text-content-secondary">
        {source.documentCount}
      </TableCell>
      <TableCell className="px-4 text-center">
        {Object.values(source.permissions).some(Boolean) ? (
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
      </TableCell>
    </TableRow>
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
