import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import {
  createColumnHelper,
  rowPaginationFeature,
  tableFeatures,
  useTable,
} from "@tanstack/react-table";
import { CircleAlert, Download, ScrollText, Search } from "lucide-react";
import { useEffect, useEffectEvent, useMemo, useState } from "react";
import { DataTable, type DataTableColumnMeta } from "@/components/data-table/data-table";
import { EmptyState } from "@/components/composites/empty-state";
import { PersonAvatar } from "@/components/composites/person-avatar";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Button } from "@/components/ui/button";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusBadge } from "@/components/ui/status-badge";
import { TablePagination } from "@/components/ui/table-pagination";
import { cursorTablePaging, useCursorPaging } from "@/features/sources/shared/use-cursor-paging";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listAuditEventsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { AuditEvent, ListAuditEventsData } from "@/lib/hey-api/types.gen";
import { AuditEventSheet } from "./audit-event-sheet";
import {
  actionLabels,
  classLabels,
  outcomeLabels,
  outcomeTones,
  periodLabels,
  periodStart,
  type AuditPeriod,
} from "./audit-actions";
import type { AuditLogSearch } from "./audit-log-search";

const ALL = "all";
const PAGE_SIZE = 50;
const periods: AuditPeriod[] = ["1d", "7d", "30d", "90d"];
const classes = Object.keys(classLabels) as AuditEvent["eventClass"][];
const outcomes = Object.keys(outcomeLabels) as AuditEvent["outcome"][];

const auditRoute = getRouteApi("/_authenticated/admin/audit");

const features = tableFeatures({
  rowPaginationFeature,
  columnMeta: {} as DataTableColumnMeta,
  tableMeta: {} as { open: (event: AuditEvent) => void },
});
const column = createColumnHelper<typeof features, AuditEvent>();

/*
 * Columns live at module scope, because a cell renderer is a component and a rebuilt list remounts
 * every cell.
 */
const columns = column.columns([
  column.accessor("occurredAt", {
    header: function TimeHeader() {
      const ui = useAppTranslation();
      return ui("Time");
    },
    meta: { width: "w-38" },
    cell: ({ row }) => (
      <span className="tabular-nums">
        <span className="block text-content-primary">
          {formatUiDate(row.original.occurredAt, {
            day: "2-digit",
            month: "2-digit",
            year: "numeric",
          })}
        </span>
        <span className="block font-secondary-body text-content-muted">
          {formatUiDate(row.original.occurredAt, { hour: "2-digit", minute: "2-digit" })}
        </span>
      </span>
    ),
  }),
  column.display({
    id: "person",
    header: function PersonHeader() {
      const ui = useAppTranslation();
      return ui("Person");
    },
    meta: { width: "w-3/10" },
    cell: function PersonCell({ row }) {
      const ui = useAppTranslation();
      const event = row.original;
      const person = event.actorLabel ?? ui("System");
      return (
        <span className="flex min-w-0 items-center gap-2.5">
          <PersonAvatar name={person} seed={event.actorId ?? person} />
          <span className="min-w-0">
            <span
              className="block truncate font-main-ui-action text-content-primary"
              title={person}
            >
              {person}
            </span>
            {event.actorEmail && event.actorEmail !== event.actorLabel ? (
              <span className="block truncate font-secondary-body text-content-muted">
                {event.actorEmail}
              </span>
            ) : null}
          </span>
        </span>
      );
    },
  }),
  column.display({
    id: "activity",
    header: function ActivityHeader() {
      const ui = useAppTranslation();
      return ui("Activity");
    },
    cell: function ActivityCell({ row, table }) {
      const ui = useAppTranslation();
      const event = row.original;
      return (
        <>
          {/* One sentence with its object, as 1Password and PlanetScale write an event. */}
          <button
            type="button"
            className="block max-w-full truncate rounded-sm text-left text-content-secondary focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
            onClick={() => table.options.meta?.open(event)}
          >
            {actionLabels[event.action] ? ui(actionLabels[event.action]!) : event.action}
            {event.resourceLabel ? (
              <span className="font-main-ui-action text-content-primary">
                {" "}
                {event.resourceLabel}
              </span>
            ) : null}
          </button>
          {/* Most events succeed; only the ones that did not carry a mark. */}
          {event.outcome !== "SUCCESS" ? (
            <StatusBadge tone={outcomeTones[event.outcome]} size="sm" className="mt-1">
              {ui(outcomeLabels[event.outcome])}
            </StatusBadge>
          ) : null}
        </>
      );
    },
  }),
  column.accessor("sourceIp", {
    header: function AddressHeader() {
      const ui = useAppTranslation();
      return ui("IP address");
    },
    meta: { width: "w-34" },
    cell: ({ row }) => (
      <span className="font-secondary-body text-content-muted tabular-nums">
        {row.original.sourceIp ?? "—"}
      </span>
    ),
  }),
]);

/**
 * Admin › Monitoring › Audit log (ADR 0013): who changed sign-in, users, Groups, models, connections and Sources.
 * One row of filters, a table of events newest first, and a panel for one event, as 1Password, Okta and Vanta show theirs.
 */
export function AuditLogPage() {
  const ui = useAppTranslation();
  // The filters live in the address, so a reload or a shared link shows the same events.
  const filters = auditRoute.useSearch();
  const [open, setOpen] = useState<AuditEvent | null>(null);

  // The period is fixed when the filters change, so later pages share the first page's bounds.
  const query = useMemo<NonNullable<ListAuditEventsData["query"]>>(
    () => ({
      from: periodStart(filters.period),
      q: filters.q,
      eventClass: filters.eventClass,
      outcome: filters.outcome,
      size: PAGE_SIZE,
    }),
    [filters.period, filters.q, filters.eventClass, filters.outcome],
  );
  const exportHref = `/api/audit/export?${new URLSearchParams(
    Object.entries({ ...query, size: undefined }).filter(
      (entry): entry is [string, string] => typeof entry[1] === "string",
    ),
  ).toString()}`;

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("Audit log")}
        icon={<ScrollText />}
        description={ui(
          "History of administrative changes in your organization. Events are kept for 365 days.",
        )}
        actions={
          <Button asChild prominence="secondary">
            <a href={exportHref} download>
              <Download data-icon="inline-start" aria-hidden="true" />
              {ui("Export CSV")}
            </a>
          </Button>
        }
      />
      <AuditLogFilters filters={filters} />
      {/* The events belong to the filters they were read with; new filters start again at the newest. */}
      <AuditEvents key={JSON.stringify(query)} query={query} onOpen={setOpen} />
      <AuditEventSheet event={open} onClose={() => setOpen(null)} />
    </SettingsLayout>
  );
}

function AuditLogFilters({ filters }: { filters: AuditLogSearch }) {
  const ui = useAppTranslation();
  const navigate = auditRoute.useNavigate();
  const setFilters = (next: Partial<AuditLogSearch>) =>
    void navigate({
      search: (current) => ({ ...current, ...next }),
      replace: true,
      resetScroll: false,
    });
  const [text, setText] = useState(filters.q ?? "");
  const [shownText, setShownText] = useState(filters.q);
  if (shownText !== filters.q) {
    // Back and forward change the searched text; the box follows it.
    setShownText(filters.q);
    if ((text.trim() || undefined) !== filters.q) setText(filters.q ?? "");
  }
  // The typed search reaches the address once typing pauses.
  const searched = useDebouncedValue(text.trim() || undefined, 300);
  const writeSearch = useEffectEvent((q: string | undefined) => {
    if (q !== filters.q) setFilters({ q });
  });
  useEffect(() => writeSearch(searched), [searched]);

  return (
    <div className="flex flex-wrap items-center gap-2" role="group" aria-label={ui("Filters")}>
      <Select
        value={filters.period}
        onValueChange={(value) => setFilters({ period: value as AuditPeriod })}
      >
        <SelectTrigger aria-label={ui("Period")} className="w-40">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            {periods.map((period) => (
              <SelectItem key={period} value={period}>
                {ui(periodLabels[period])}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
      <Select
        value={filters.eventClass ?? ALL}
        onValueChange={(value) =>
          setFilters({
            eventClass: value === ALL ? undefined : (value as AuditEvent["eventClass"]),
          })
        }
      >
        <SelectTrigger aria-label={ui("Category")} className="w-40">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            <SelectItem value={ALL}>{ui("All categories")}</SelectItem>
            {classes.map((value) => (
              <SelectItem key={value} value={value}>
                {ui(classLabels[value])}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
      <Select
        value={filters.outcome ?? ALL}
        onValueChange={(value) =>
          setFilters({ outcome: value === ALL ? undefined : (value as AuditEvent["outcome"]) })
        }
      >
        <SelectTrigger aria-label={ui("Outcome")} className="w-36">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            <SelectItem value={ALL}>{ui("All outcomes")}</SelectItem>
            {outcomes.map((value) => (
              <SelectItem key={value} value={value}>
                {ui(outcomeLabels[value])}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
      <InputGroup className="min-w-56 flex-1">
        <InputGroupAddon>
          <Search aria-hidden="true" />
        </InputGroupAddon>
        <InputGroupInput
          value={text}
          onChange={(event) => setText(event.target.value)}
          placeholder={ui("Search people or items")}
          aria-label={ui("Search people or items")}
        />
      </InputGroup>
    </div>
  );
}

/** One filter selection's events, newest first, paged by the cursors the API returns. */
function AuditEvents({
  query,
  onOpen,
}: {
  query: NonNullable<ListAuditEventsData["query"]>;
  onOpen: (event: AuditEvent) => void;
}) {
  const ui = useAppTranslation();
  const paging = useCursorPaging();
  const events = useQuery({
    ...listAuditEventsOptions({ query: { ...query, cursor: paging.cursor } }),
    placeholderData: keepPreviousData,
  });
  const nextCursor = events.data?.nextCursor;
  // The stream only grows, so there is no total: the last page is known once it has no successor.
  const totalPages = events.data && !nextCursor ? paging.page + 1 : undefined;
  const table = useTable({
    features,
    columns,
    data: events.data?.items ?? [],
    getRowId: (event) => event.id,
    meta: { open: onOpen },
    ...cursorTablePaging(paging, PAGE_SIZE, nextCursor, totalPages),
  });

  if (events.isPending)
    return (
      <div role="status" aria-label={ui("Loading the audit log…")} className="flex flex-col gap-2">
        {Array.from({ length: 4 }, (_, index) => (
          <Skeleton key={index} className="h-16" />
        ))}
      </div>
    );
  if (events.isError)
    return (
      <EmptyState
        role="alert"
        icon={<CircleAlert />}
        title={ui("The audit log could not be loaded.")}
        action={
          <Button prominence="secondary" onClick={() => void events.refetch()}>
            {ui("Try again")}
          </Button>
        }
      />
    );
  if (events.data.items.length === 0 && !paging.hasPrevious)
    return (
      <EmptyState
        icon={<ScrollText />}
        title={ui("No events match these filters.")}
        detail={ui("Choose a longer period or clear the search.")}
      />
    );
  return (
    <DataTable
      table={table}
      label={ui("Audit log")}
      className="min-w-192"
      footer={
        <TablePagination
          label={ui("Audit log pages")}
          page={paging.page}
          totalPages={totalPages}
          previousDisabled={events.isPlaceholderData || !table.getCanPreviousPage()}
          nextDisabled={events.isPlaceholderData || !table.getCanNextPage()}
          onPrevious={() => table.previousPage()}
          onNext={() => table.nextPage()}
        />
      }
    />
  );
}
