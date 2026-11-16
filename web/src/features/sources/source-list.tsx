import { formatUiDay, formatUiMoment, formatUiRelative } from "@/i18n/format";
import { useAppTranslation, type AppTranslate } from "@/i18n/use-app-translation";
import { Files, FilterX, ListFilter } from "lucide-react";
import { useState, type ReactNode } from "react";
import { FilterChips } from "@/components/composites/filter-chips";
import { SearchField } from "@/components/composites/search-field";
import { TooltipIconButton } from "@/components/composites/tooltip-icon-button";
import { Card, CardContent } from "@/components/ui/card";
import { Field, FieldLabel } from "@/components/ui/field";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { TablePagination } from "@/components/ui/table-pagination";
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import {
  findSourceProvider,
  sourceProviders,
} from "@/features/sources/shared/source-provider-catalog";
import { sourceStatusMessage } from "@/features/sources/shared/source-errors";
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
  /** Who answers for the Source; a list that does not know leaves it out. */
  managerName?: string | null;
  /** Why the last run failed, as the server's code. */
  errorCode?: string | null;
  /** Whether work on the Source is running or waiting. */
  pendingWork?: boolean;
};

/** The figures the stat strip and the status chips show; the page that owns the list counts them. */
export type SourceListTotals = {
  total: number;
  documents: number;
  /** How many Sources hold each status; the chips list the statuses that hold any. */
  byStatus: Readonly<Record<string, number>>;
};

/** The place of the rows in a list the server cuts into pages; `page` counts from 0. */
export type SourceListPager = {
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  onPage: (next: number) => void;
};

/**
 * The Sources as the Sources page lists them: their totals, a search, the status, provider and access filters
 * and one flat table. The administration page and a member's library both show it; each owns what its rows are
 * (searched, filtered and paged where it likes) and says what a row's document count means and where its name
 * and action lead. The search and the filters belong to the page that shows the list, so a page that keeps them
 * in its address can.
 */
export function SourceList<T extends SourceListItem>({
  sources,
  totals,
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
  pager,
}: {
  /** The rows to show, already narrowed by the search and the filters. */
  sources: readonly T[];
  totals: SourceListTotals;
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
  /** Present when the rows are one page of a longer list. */
  pager?: SourceListPager;
}) {
  const ui = useAppTranslation();

  const [filtersOpen, setFiltersOpen] = useState(
    () => filters.status !== "" || filters.provider !== "" || filters.access !== "",
  );
  const columns = renderAction ? 5 : 4;
  const row = { documents, renderName, renderAccess, renderAction };
  const statusChips = sourceStatusOptions
    .filter((option) => !statuses || statuses.includes(option.value))
    .filter((option) => (totals.byStatus[option.value] ?? 0) > 0 || option.value === filters.status)
    .map((option) => ({
      value: option.value,
      label: ui(option.label),
      count: totals.byStatus[option.value] ?? 0,
    }));

  return (
    <>
      {/*
        No stat strip: the status counts beside the search already say how many Sources failed and filter to
        them, and the total and the documents close the table, as Onyx's list counts its connectors under it.
      */}
      {/* One control row, as Onyx's list has: the search, the status counts that filter, then the other filters. */}
      <div className="flex flex-wrap items-center gap-2">
        <SearchField
          value={search}
          onChange={onSearch}
          label={ui("Search sources")}
          className="min-w-40 flex-1"
        />
        {statusChips.length > 0 ? (
          <FilterChips
            label={ui("Filter sources by status")}
            // The status chips stand as tall as the search field and the filter button beside them, so the
            // control row keeps one height.
            className="[&>button]:h-9"
            chips={statusChips}
            value={filters.status || undefined}
            onChange={(status) => onFilters({ ...filters, status: status ?? "" })}
          />
        ) : null}
        <TooltipIconButton
          prominence="secondary"
          aria-label={ui("Filter sources")}
          aria-expanded={filtersOpen}
          aria-controls="source-filters"
          onClick={() => setFiltersOpen((open) => !open)}
        >
          <ListFilter />
        </TooltipIconButton>
      </div>

      {filtersOpen ? (
        <SourceFilterFields value={filters} statuses={statuses} onChange={onFilters} />
      ) : null}

      {/*
        The list's own width picks its layout: from 56rem every column shows; narrower, the name cell carries the
        access, count and date on a second line, so nothing scrolls sideways on a phone or beside the sidebar.
      */}
      <div className="@container overflow-hidden rounded-lg border border-border-subtle">
        <Table aria-labelledby="connected-sources-caption" className="@4xl:table-fixed">
          <TableCaption id="connected-sources-caption" className="sr-only">
            {ui("Connected sources")}
          </TableCaption>
          <TableHeader>
            <TableRow className="h-10.5">
              <TableHead scope="col" className="px-4">
                {ui("Name")}
              </TableHead>
              <TableHead scope="col" className="w-px px-2 whitespace-nowrap @4xl:w-64 @4xl:px-4">
                {ui("Status")}
              </TableHead>
              <TableHead scope="col" className="hidden w-64 px-4 whitespace-nowrap @4xl:table-cell">
                {ui("Access")}
              </TableHead>
              <TableHead scope="col" className="hidden w-40 px-4 whitespace-nowrap @4xl:table-cell">
                {documentsLabel}
              </TableHead>
              {renderAction ? (
                <TableHead scope="col" className="w-14 px-2">
                  <span className="sr-only">{ui("Manage")}</span>
                </TableHead>
              ) : null}
            </TableRow>
          </TableHeader>
          <TableBody>
            {sources.map((source) => (
              <SourceRow key={source.id} source={source} row={row} />
            ))}
            {sources.length === 0 ? (
              <TableRow>
                <TableCell colSpan={columns} className="px-4 py-12 text-center">
                  <span className="text-sm text-content-muted">
                    {ui("No sources match your search and filters.")}
                  </span>
                </TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
        {pager && pager.totalItems > 0 ? (
          <SourcePager pager={pager} documents={totals.documents} />
        ) : !pager && sources.length > 0 ? (
          <p className="border-t border-border-subtle px-4 py-3 font-secondary-body text-content-muted tabular-nums">
            {sourcesOf(ui, sources.length)} · {documentsOf(ui, totals.documents)}
          </p>
        ) : null}
      </div>
    </>
  );
}

function SourcePager({ pager, documents }: { pager: SourceListPager; documents: number }) {
  const ui = useAppTranslation();
  const first = pager.page * pager.size + 1;
  const last = Math.min((pager.page + 1) * pager.size, pager.totalItems);

  return (
    <TablePagination
      label={ui("Source pages")}
      page={pager.page}
      totalPages={pager.totalPages}
      summary={`${ui("Showing {{first}}–{{last}} of {{total}}", {
        first,
        last,
        total: pager.totalItems,
      })} · ${documentsOf(ui, documents)}`}
      previousDisabled={pager.page <= 0}
      nextDisabled={pager.page + 1 >= pager.totalPages}
      onPrevious={() => pager.onPage(pager.page - 1)}
      onNext={() => pager.onPage(pager.page + 1)}
    />
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
          <TooltipIconButton
            size="sm"
            prominence="secondary"
            className="justify-self-start"
            aria-label={ui("Clear filters")}
            disabled={!value.status && !value.provider && !value.access}
            onClick={() => onChange(noSourceFilters)}
          >
            <FilterX />
          </TooltipIconButton>
        </div>
      </CardContent>
    </Card>
  );
}

/** How a list renders its rows: the count it shows and where a row leads. */
type RowRendering<T> = {
  documents: (source: T) => number;
  renderName: (source: T) => ReactNode;
  renderAccess?: (source: T) => ReactNode;
  renderAction?: (source: T) => ReactNode;
};

function SourceRow<T extends SourceListItem>({ source, row }: { source: T; row: RowRendering<T> }) {
  const ui = useAppTranslation();
  const documents = row.documents(source);
  const ProviderIcon = findSourceProvider(source.type)?.icon ?? Files;

  return (
    <TableRow id={`source-${source.id}`} className="h-15">
      <TableCell className="px-4 py-2.5">
        <div className="flex items-start gap-3">
          <ProviderIcon
            className="mt-0.5 size-4 shrink-0 text-content-secondary"
            aria-hidden="true"
          />
          <div className="min-w-0 flex-1">
            {row.renderName(source)}
            {source.managerName ? (
              <span className="block truncate font-secondary-body text-content-muted">
                {ui("Responsible manager: {{v1}}", { v1: source.managerName })}
              </span>
            ) : null}
            <span className="mt-0.5 block font-secondary-body text-content-muted @4xl:hidden">
              {ui(sourceAccessPresentation[source.access].label)} · {documentsOf(ui, documents)}
              {source.lastSucceededAt ? (
                <>
                  {" · "}
                  <LastIndexed value={source.lastSucceededAt} />
                </>
              ) : null}
            </span>
            {row.renderAccess ? (
              <span className="@4xl:hidden">{row.renderAccess(source)}</span>
            ) : null}
          </div>
        </div>
      </TableCell>
      <TableCell className="px-2 py-2.5 @4xl:px-4">
        <SourceStatusBadge status={source.status} />
        <SourceStatusLine source={source} />
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

/** "1 source", "2 sources", as `documentsOf` counts documents. */
function sourcesOf(ui: AppTranslate, count: number) {
  return count === 1 ? ui("1 source") : ui("{{count}} sources", { count });
}

/** "1 document", "2 documents": each count has its own sentence, so a translation is never stitched together. */
function documentsOf(ui: AppTranslate, count: number) {
  return count === 1 ? ui("1 document") : ui("{{count}} documents", { count });
}

/**
 * Under the status: why a failed Source failed, that work is running, or how long ago it last succeeded. The
 * reason keeps to one line with the whole message on hover, so a long one does not stretch its row; a Source that
 * did not fail keeps its time even when one of its files left an error code.
 */
function SourceStatusLine({ source }: { source: SourceListItem }) {
  const ui = useAppTranslation();
  const className = "mt-0.5 block max-w-60 truncate font-secondary-body text-content-muted";

  if (source.status === "FAILED" && source.errorCode) {
    const reason = ui(sourceStatusMessage(source.errorCode));
    return (
      <span className={className} title={reason}>
        {reason}
      </span>
    );
  }
  if (source.pendingWork) return <span className={className}>{ui("Still working")}</span>;
  if (!source.lastSucceededAt) return null;
  const date = new Date(source.lastSucceededAt);
  return (
    <time dateTime={source.lastSucceededAt} title={formatUiMoment(date)} className={className}>
      {formatUiRelative(source.lastSucceededAt) ?? formatUiDay(date)}
    </time>
  );
}

function LastIndexed({ value }: { value: string }) {
  const date = new Date(value);
  return (
    <time dateTime={value} title={formatUiMoment(date)}>
      {formatUiDay(date)}
    </time>
  );
}
