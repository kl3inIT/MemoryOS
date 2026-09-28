import { uiLocale } from "@/i18n/format";
import { cn } from "@/lib/utils";
import { statLabelClass, statValueClass } from "@/components/composites/stat-strip";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChevronDown, ChevronRight, Files, ListFilter } from "lucide-react";
import { useMemo, useState, type ReactNode } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { Field, FieldLabel } from "@/components/ui/field";
import { Button } from "@/components/ui/button";
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
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableRow,
} from "@/components/ui/table";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import {
  findSourceProvider,
  sourceProviders,
} from "@/features/sources/shared/source-provider-catalog";
import {
  SourceAccessBadge,
  SourceStatusBadge,
} from "@/features/sources/shared/source-status-badge";
import {
  sourceAccessOptions,
  sourceStatusOptions,
} from "@/features/sources/shared/source-status-presentation";
import { noSourceFilters, type SourceFilters } from "@/features/sources/source-filters";

/** Radix selects reject an empty option value, so "any" stands for an unset filter. */
const anyFilterValue = "any";

/** What a row says of a Source, whoever reads the list. */
export type SourceListItem = {
  id: string;
  name: string;
  type: string;
  status: string;
  access: SourceSummary["access"];
  lastSucceededAt: string | null;
};

/**
 * The Sources grouped by provider, as the Sources page lists them: a search, the status, provider and access
 * filters, and one table section per provider with its totals. The administration page and a member's library both
 * show it; each says what a row's document count means and where its name and action lead. The search and the
 * filters belong to the page that shows the list, so a page that keeps them in its address can.
 */
export function SourceList<T extends SourceListItem>({
  sources,
  search,
  onSearch,
  filters,
  onFilters,
  statuses,
  documents,
  documentsLabel,
  documentsTotalLabel,
  renderName,
  renderAccess,
  renderAction,
}: {
  sources: readonly T[];
  search: string;
  onSearch: (next: string) => void;
  filters: SourceFilters;
  onFilters: (next: SourceFilters) => void;
  /** The statuses this list can hold; the status filter offers only these. Every status by default. */
  statuses?: readonly string[];
  /** The documents a row counts. */
  documents: (source: T) => number;
  /** The column naming what `documents` counts. */
  documentsLabel: string;
  /** A provider's total of `documents`. */
  documentsTotalLabel: string;
  /** The Source's name, leading wherever this list opens a Source. */
  renderName: (source: T) => ReactNode;
  /** What the access cell adds under its badge. */
  renderAccess?: (source: T) => ReactNode;
  /** The row's action; a list without one has no action column. */
  renderAction?: (source: T) => ReactNode;
}) {
  const ui = useAppTranslation();

  const [filtersOpen, setFiltersOpen] = useState(
    () => filters.status !== "" || filters.provider !== "" || filters.access !== "",
  );
  const [collapsedTypes, setCollapsedTypes] = useState<Set<string>>(() => new Set());
  const filteredSources = useMemo(() => {
    const query = search.trim().toLowerCase();
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
  }, [filters, search, sources, ui]);
  const groups = useMemo(() => groupSources(filteredSources), [filteredSources]);
  const hasExpandedGroups = groups.some((group) => !collapsedTypes.has(group.type));
  const columns = renderAction ? 6 : 5;
  const row = {
    documents,
    documentsLabel,
    documentsTotalLabel,
    renderName,
    renderAccess,
    renderAction,
  };

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
          value={search}
          placeholder={ui("Search sources")}
          aria-label={ui("Search sources")}
          className="min-w-40 flex-1"
          onChange={(event) => onSearch(event.target.value)}
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
        <SourceFilterFields value={filters} statuses={statuses} onChange={onFilters} />
      ) : null}

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
            {renderAction ? <col className="w-16" /> : null}
          </colgroup>
          {groups.map((group) => (
            <SourceGroupBody
              key={group.type}
              group={group}
              collapsed={collapsedTypes.has(group.type)}
              onToggle={() => toggle(group.type)}
              row={row}
            />
          ))}
          {groups.length === 0 ? (
            <TableBody>
              <TableRow>
                <TableCell colSpan={columns} className="px-4 py-12 text-center">
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
  statuses,
  onChange,
}: {
  value: SourceFilters;
  statuses?: readonly string[];
  onChange: (next: SourceFilters) => void;
}) {
  const ui = useAppTranslation();
  const fields = [
    {
      name: "status",
      label: ui("Status"),
      any: ui("All statuses"),
      options: sourceStatusOptions
        .filter((option) => !statuses || statuses.includes(option.value))
        .map((option) => ({ ...option, label: ui(option.label) })),
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
            onClick={() => onChange(noSourceFilters)}
          >
            {ui("Clear filters")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

type SourceGroup<T> = {
  type: string;
  sources: T[];
};

/** How a list renders its rows: the counts it shows and where a row leads. */
type RowRendering<T> = {
  documents: (source: T) => number;
  documentsLabel: string;
  documentsTotalLabel: string;
  renderName: (source: T) => ReactNode;
  renderAccess?: (source: T) => ReactNode;
  renderAction?: (source: T) => ReactNode;
};

function SourceGroupBody<T extends SourceListItem>({
  group,
  collapsed,
  onToggle,
  row,
}: {
  group: SourceGroup<T>;
  collapsed: boolean;
  onToggle: () => void;
  row: RowRendering<T>;
}) {
  const ui = useAppTranslation();

  const provider = findSourceProvider(group.type);
  const ProviderIcon = provider?.icon ?? Files;
  const documentCount = group.sources.reduce((total, source) => total + row.documents(source), 0);
  const activeCount = group.sources.filter((source) => source.status === "ACTIVE").length;
  const workspaceAccessCount = group.sources.filter((source) => source.access === "PUBLIC").length;

  return (
    <TableBody>
      {/* The whole row toggles its group; the provider's button stays the keyboard's way to it. */}
      <TableRow
        className="h-18 cursor-pointer"
        onClick={(event) => {
          if (!(event.target as Element).closest("button, a")) onToggle();
        }}
      >
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
            className="flex h-full w-full items-center gap-2 text-left focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
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
        <SummaryMetric label={row.documentsTotalLabel} value={documentCount} />
        {row.renderAction ? <TableCell /> : null}
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
              {row.documentsLabel}
            </TableHead>
            {row.renderAction ? (
              <TableHead scope="col" className="px-4">
                <span className="sr-only">{ui("Manage")}</span>
              </TableHead>
            ) : null}
          </TableRow>
          {group.sources.map((source) => (
            <TableRow key={source.id} id={`source-${source.id}`} className="h-15">
              <TableCell className="px-4">{row.renderName(source)}</TableCell>
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
                {row.renderAccess?.(source)}
              </TableCell>
              <TableCell className="px-4">
                <span className="text-sm text-content-secondary tabular-nums">
                  {row.documents(source)}
                </span>
              </TableCell>
              {row.renderAction ? (
                <TableCell className="px-4 text-center">{row.renderAction(source)}</TableCell>
              ) : null}
            </TableRow>
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

function groupSources<T extends SourceListItem>(sources: T[]): SourceGroup<T>[] {
  const groups = new Map<string, T[]>();
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
