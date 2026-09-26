import { uiLocale } from "@/i18n/format";
import { cn } from "@/lib/utils";
import { statLabelClass, statValueClass } from "@/components/composites/stat-strip";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { BookOpen, ChevronDown, ChevronRight, Files, ListFilter, Settings } from "lucide-react";
import { useMemo, useState } from "react";
import { BrandLoader } from "@/components/brand-loader";
import { EmptyState } from "@/components/composites/empty-state";
import { Card, CardContent } from "@/components/ui/card";
import { Field, FieldLabel } from "@/components/ui/field";
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
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
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
import {
  findSourceProvider,
  sourceProviders,
  type SourceProvider,
} from "@/features/sources/shared/source-provider-catalog";
import {
  SourceAccessBadge,
  SourceStatusBadge,
} from "@/features/sources/shared/source-status-badge";
import {
  sourceAccessOptions,
  sourceStatusOptions,
} from "@/features/sources/shared/source-status-presentation";
import { IDLE_SOURCE_POLL_MS } from "@/features/sources/shared/source-polling";

/** Radix selects reject an empty option value, so "any" stands for an unset filter. */
const anyFilterValue = "any";

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
        <SourceList sources={sources} />
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

type SourceFilters = { status: string; provider: string; access: string };
const noFilters: SourceFilters = { status: "", provider: "", access: "" };

function SourceList({ sources }: { sources: SourceSummary[] }) {
  const ui = useAppTranslation();

  const [searchQuery, setSearchQuery] = useState("");
  const [filters, setFilters] = useState(noFilters);
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
        (!filters.status || source.status === filters.status) &&
        (!filters.provider || source.type === filters.provider) &&
        (!filters.access || source.access === filters.access)
      );
    });
  }, [filters, searchQuery, sources, ui]);
  const groups = useMemo(() => groupSources(filteredSources), [filteredSources]);
  const hasExpandedGroups = groups.some((group) => !collapsedTypes.has(group.type));

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
          className="min-w-40 flex-1"
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

      {filtersOpen ? <SourceFilterFields value={filters} onChange={setFilters} /> : null}

      <div className="overflow-hidden rounded-lg border border-border-subtle">
        <Table aria-labelledby="connected-sources-caption" className="min-w-6xl table-fixed">
          <TableCaption id="connected-sources-caption" className="sr-only">
            {ui("Connected sources")}
          </TableCaption>
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
              <TableRow>
                <TableCell colSpan={6} className="px-4 py-12 text-center">
                  <span className="text-sm text-content-muted">
                    {ui("No sources match your search and filters.")}
                  </span>
                </TableCell>
              </TableRow>
            </TableBody>
          ) : null}
        </Table>
      </div>
    </>
  );
}

/** Status, provider and access filters; "any" stands for an unset filter, which Radix selects need. */
function SourceFilterFields({
  value,
  onChange,
}: {
  value: SourceFilters;
  onChange: (next: SourceFilters) => void;
}) {
  const ui = useAppTranslation();
  const fields = [
    {
      name: "status",
      label: ui("Status"),
      any: ui("All statuses"),
      options: sourceStatusOptions.map((option) => ({ ...option, label: ui(option.label) })),
    },
    {
      name: "provider",
      label: ui("Provider"),
      any: ui("All providers"),
      options: sourceProviders.map((provider) => ({
        value: provider.type,
        label: ui(provider.name),
      })),
    },
    {
      name: "access",
      label: ui("Access"),
      any: ui("All access"),
      options: sourceAccessOptions.map((option) => ({ ...option, label: ui(option.label) })),
    },
  ] as const;

  return (
    <Card id="source-filters" size="sm" className="mt-2">
      <CardContent>
        <div className="grid gap-3 sm:grid-cols-2 sm:items-end lg:grid-cols-4">
          {fields.map((field) => (
            <Field key={field.name}>
              <FieldLabel htmlFor={`source-filter-${field.name}`}>{field.label}</FieldLabel>
              <Select
                value={value[field.name] || anyFilterValue}
                onValueChange={(next) =>
                  onChange({ ...value, [field.name]: next === anyFilterValue ? "" : next })
                }
              >
                <SelectTrigger id={`source-filter-${field.name}`} size="sm" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value={anyFilterValue}>{field.any}</SelectItem>
                    {field.options.map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
          ))}
          <Button
            size="sm"
            prominence="tertiary"
            disabled={!value.status && !value.provider && !value.access}
            onClick={() => onChange(noFilters)}
          >
            {ui("Clear filters")}
          </Button>
        </div>
      </CardContent>
    </Card>
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
      <TableRow className="h-18">
        <TableHead scope="rowgroup" className="px-4">
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
        <TableCell />
      </TableRow>
      {!collapsed ? (
        <>
          <TableRow className="h-10.5">
            <TableHead scope="col" className="px-4 whitespace-nowrap">
              {ui("Name")}
            </TableHead>
            <TableHead scope="col" className="px-4 whitespace-nowrap">
              {ui("Last indexed")}
            </TableHead>
            <TableHead scope="col" className="px-4 whitespace-nowrap">
              {ui("Status")}
            </TableHead>
            <TableHead scope="col" className="px-4 whitespace-nowrap">
              {ui("Access")}
            </TableHead>
            <TableHead scope="col" className="px-4 whitespace-nowrap">
              {ui("Total docs")}
            </TableHead>
            <TableHead scope="col" className="px-4">
              <span className="sr-only">{ui("Manage")}</span>
            </TableHead>
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
    <TableCell className="px-4">
      <span className={cn("block whitespace-nowrap", statLabelClass)}>{label}</span>
      <span className={cn("mt-1 block", statValueClass)}>{value}</span>
    </TableCell>
  );
}

function SourceRow({ source }: { source: SourceSummary }) {
  const ui = useAppTranslation();

  return (
    <TableRow id={`source-${source.id}`} className="h-15">
      <TableCell className="px-4">
        <Link
          to="/admin/sources/$sourceId"
          params={{ sourceId: source.id }}
          className="text-sm font-medium text-content-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
        >
          {source.name}
        </Link>
      </TableCell>
      <TableCell className="px-4">
        <span className="font-secondary-body text-content-muted">
          <LastIndexed value={source.lastSucceededAt} />
        </span>
      </TableCell>
      <TableCell className="px-4">
        <SourceStatusBadge status={source.status} />
      </TableCell>
      <TableCell className="px-4">
        <SourceAccessBadge access={source.access} />
      </TableCell>
      <TableCell className="px-4">
        <span className="text-sm tabular-nums text-content-secondary">{source.documentCount}</span>
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
