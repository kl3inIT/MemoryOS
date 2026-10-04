import { formatUiDay, formatUiMoment, uiLocale } from "@/i18n/format";
import { useAppTranslation, type AppTranslate } from "@/i18n/use-app-translation";
import {
  ChevronDown,
  ChevronRight,
  Files,
  FileText,
  ListFilter,
  Plug,
  TriangleAlert,
} from "lucide-react";
import { useMemo, useState, type ReactNode } from "react";
import { StatStrip, StatTile, StatToggleTile } from "@/components/composites/stat-strip";
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
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import { TablePagination } from "@/components/ui/table-pagination";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import {
  findSourceProvider,
  sourceProviders,
} from "@/features/sources/shared/source-provider-catalog";
import { SourceAccess, SourceStatusBadge } from "@/features/sources/shared/source-status-badge";
import {
  sourceAccessOptions,
  sourceAccessPresentation,
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
 * The Sources grouped by provider, as the Sources page lists them: their totals, a search, the status, provider
 * and access filters, and one section per provider with its own table and pages. The administration page and a member's library both
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
  /** Names what `documents` counts, in its column and in the total over every Source listed. */
  documentsLabel: string;
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
  const row = { documents, renderName, renderAccess, renderAction };
  const failedCount = sources.filter((source) => source.status === "FAILED").length;
  const showingFailed = filters.status === "FAILED";
  const documentCount = sources.reduce((total, source) => total + documents(source), 0);

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
      {/*
        What an administrator acts on: how many Sources there are, which ones failed, and what they hold. The
        failed tile filters the list to them, as the Users counts filter theirs; its figure turns red only when
        a Source has failed.
      */}
      <StatStrip columns={3}>
        <StatTile
          icon={<Plug />}
          iconClass="text-chart-1"
          label={ui("Total sources")}
          value={sources.length.toLocaleString(uiLocale())}
        />
        <StatToggleTile
          icon={<TriangleAlert />}
          iconClass="text-status-danger-content"
          label={ui("Failed")}
          value={failedCount.toLocaleString(uiLocale())}
          tone={failedCount > 0 ? "danger" : undefined}
          selected={showingFailed}
          onToggle={() => onFilters({ ...filters, status: showingFailed ? "" : "FAILED" })}
          accessibleLabel={ui("Show failed sources, {{count}}", { count: failedCount })}
        />
        <StatTile
          icon={<FileText />}
          iconClass="text-chart-2"
          label={documentsLabel}
          value={documentCount.toLocaleString(uiLocale())}
        />
      </StatStrip>

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

      {/* Each provider is a section of its own: its heading, its table and its pages. */}
      <section aria-label={ui("Connected sources")} className="flex flex-col gap-8">
        {groups.map((group) => (
          <SourceGroupSection
            key={group.type}
            group={group}
            collapsed={collapsedTypes.has(group.type)}
            onToggle={() => toggle(group.type)}
            documentsLabel={documentsLabel}
            row={row}
          />
        ))}
        {groups.length === 0 ? (
          <p className="rounded-lg border border-border-subtle px-4 py-12 text-center text-sm text-content-muted">
            {ui("No sources match your search and filters.")}
          </p>
        ) : null}
      </section>
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

/** How a list renders its rows: the count it shows and where a row leads. */
type RowRendering<T> = {
  documents: (source: T) => number;
  renderName: (source: T) => ReactNode;
  renderAccess?: (source: T) => ReactNode;
  renderAction?: (source: T) => ReactNode;
};

/** The Sources one page of a provider's table can show; a provider with more than the first gets pages of its own. */
const GROUP_PAGE_SIZES = [10, 20, 50] as const;

/**
 * One provider's Sources: a heading that folds them, their table, and the pages of that table when there are more
 * than one page holds.
 */
function SourceGroupSection<T extends SourceListItem>({
  group,
  collapsed,
  onToggle,
  documentsLabel,
  row,
}: {
  group: SourceGroup<T>;
  collapsed: boolean;
  onToggle: () => void;
  documentsLabel: string;
  row: RowRendering<T>;
}) {
  const ui = useAppTranslation();
  const [requestedPage, setRequestedPage] = useState(0);
  const [size, setSize] = useState<number>(GROUP_PAGE_SIZES[0]);

  const provider = findSourceProvider(group.type);
  const ProviderIcon = provider?.icon ?? Files;
  const providerName = ui(provider?.name ?? group.type);
  const documentCount = group.sources.reduce((total, source) => total + row.documents(source), 0);
  const totalPages = Math.ceil(group.sources.length / size);
  // A search or a filter can leave fewer pages than the one being read.
  const page = Math.min(requestedPage, totalPages - 1);
  const first = page * size;
  const shown = failuresFirst(group.sources).slice(first, first + size);

  return (
    <div className="flex flex-col gap-3">
      <h2>
        <button
          type="button"
          aria-expanded={!collapsed}
          aria-label={ui("{{v1}} group, {{v2}} sources, {{v3}} documents", {
            v1: providerName,
            v2: group.sources.length,
            v3: documentCount,
          })}
          onClick={onToggle}
          className="flex items-center gap-2 rounded-sm text-left focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
        >
          {collapsed ? (
            <ChevronRight className="size-4 text-content-secondary" aria-hidden="true" />
          ) : (
            <ChevronDown className="size-4 text-content-secondary" aria-hidden="true" />
          )}
          <ProviderIcon className="size-5 text-content-secondary" aria-hidden="true" />
          <span className="font-heading-h3 text-content-primary">{providerName}</span>
          <span className="font-secondary-body text-content-muted tabular-nums">
            {group.sources.length}
          </span>
        </button>
      </h2>
      {collapsed ? null : (
        // The table's own width picks its layout: from 56rem every column shows; narrower, the name cell carries
        // the access, count and date on a second line, so nothing scrolls sideways on a phone or beside the sidebar.
        <div className="@container overflow-hidden rounded-lg border border-border-subtle">
          <Table aria-label={providerName} className="@4xl:table-fixed">
            <TableHeader>
              <TableRow className="h-10.5">
                <TableHead scope="col" className="px-4">
                  {ui("Name")}
                </TableHead>
                <TableHead
                  scope="col"
                  className="hidden w-44 px-4 whitespace-nowrap @4xl:table-cell"
                >
                  {ui("Last indexed")}
                </TableHead>
                <TableHead scope="col" className="w-px px-2 whitespace-nowrap @4xl:w-48 @4xl:px-4">
                  {ui("Status")}
                </TableHead>
                <TableHead
                  scope="col"
                  className="hidden w-64 px-4 whitespace-nowrap @4xl:table-cell"
                >
                  {ui("Access")}
                </TableHead>
                <TableHead
                  scope="col"
                  className="hidden w-40 px-4 whitespace-nowrap @4xl:table-cell"
                >
                  {documentsLabel}
                </TableHead>
                {row.renderAction ? (
                  <TableHead scope="col" className="w-14 px-2">
                    <span className="sr-only">{ui("Manage")}</span>
                  </TableHead>
                ) : null}
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown.map((source) => (
                <SourceRow key={source.id} source={source} row={row} />
              ))}
            </TableBody>
          </Table>
          {group.sources.length > GROUP_PAGE_SIZES[0] ? (
            <TablePagination
              label={ui("Trang nguồn {{provider}}", { provider: providerName })}
              page={page}
              totalPages={totalPages}
              previousDisabled={page === 0}
              nextDisabled={page === totalPages - 1}
              onPrevious={() => setRequestedPage(page - 1)}
              onNext={() => setRequestedPage(page + 1)}
              summary={ui("Showing {{first}}–{{last}} of {{total}}", {
                first: first + 1,
                last: first + shown.length,
                total: group.sources.length,
              })}
            >
              <PageSizeSelect
                label={ui("Rows per page")}
                rowsLabel={ui("Rows")}
                value={size}
                sizes={GROUP_PAGE_SIZES}
                onSizeChange={(next) => {
                  setSize(next);
                  setRequestedPage(0);
                }}
              />
            </TablePagination>
          ) : null}
        </div>
      )}
    </div>
  );
}

function SourceRow<T extends SourceListItem>({ source, row }: { source: T; row: RowRendering<T> }) {
  const ui = useAppTranslation();
  const documents = row.documents(source);

  return (
    <TableRow id={`source-${source.id}`} className="h-15">
      <TableCell className="px-4 py-2.5">
        {row.renderName(source)}
        <span className="mt-0.5 block font-secondary-body text-content-muted @4xl:hidden">
          {ui(sourceAccessPresentation[source.access].label)} · {documentsOf(ui, documents)}
          {source.lastSucceededAt ? (
            <>
              {" · "}
              <LastIndexed value={source.lastSucceededAt} />
            </>
          ) : null}
        </span>
        {row.renderAccess ? <span className="@4xl:hidden">{row.renderAccess(source)}</span> : null}
      </TableCell>
      <TableCell className="hidden px-4 @4xl:table-cell">
        <span className="font-secondary-body text-content-muted">
          {source.lastSucceededAt ? <LastIndexed value={source.lastSucceededAt} /> : "-"}
        </span>
      </TableCell>
      <TableCell className="px-2 py-2.5 @4xl:px-4">
        <SourceStatusBadge status={source.status} />
      </TableCell>
      <TableCell className="hidden px-4 @4xl:table-cell">
        <SourceAccess access={source.access} />
        {row.renderAccess?.(source)}
      </TableCell>
      <TableCell className="hidden px-4 @4xl:table-cell">
        <span className="text-sm text-content-secondary tabular-nums">{documents}</span>
      </TableCell>
      {row.renderAction ? (
        <TableCell className="px-2 text-center">{row.renderAction(source)}</TableCell>
      ) : null}
    </TableRow>
  );
}

/** "1 document", "2 documents": each count has its own sentence, so a translation is never stitched together. */
function documentsOf(ui: AppTranslate, count: number) {
  return count === 1 ? ui("1 document") : ui("{{count}} documents", { count });
}

/** A failed Source is what an administrator opens the list for, so it leads its group. */
function failuresFirst<T extends SourceListItem>(sources: T[]): T[] {
  return [
    ...sources.filter((source) => source.status === "FAILED"),
    ...sources.filter((source) => source.status !== "FAILED"),
  ];
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

function LastIndexed({ value }: { value: string }) {
  const date = new Date(value);
  return (
    <time dateTime={value} title={formatUiMoment(date)}>
      {formatUiDay(date)}
    </time>
  );
}
