import { useInfiniteQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { CircleAlert, Download, ScrollText, Search } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { PersonAvatar } from "@/components/composites/person-avatar";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/radix-select";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { StatusBadge } from "@/components/ui/status-badge";
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
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listAuditEventsInfiniteOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
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

/**
 * Admin › Monitoring › Audit log (ADR 0013): who changed sign-in, users, Groups, models, connections and Sources.
 * One row of filters, a table of events newest first, and a panel for one event, as 1Password, Okta and Vanta show theirs.
 */
export function AuditLogPage() {
  const ui = useAppTranslation();
  // The filters live in the address, so a reload or a shared link shows the same events.
  const filters = auditRoute.useSearch();
  const navigate = auditRoute.useNavigate();
  const setFilters = (next: Partial<AuditLogSearch>) =>
    void navigate({
      search: (current) => ({ ...current, ...next }),
      replace: true,
      resetScroll: false,
    });
  const period = filters.period;
  const [text, setText] = useState(filters.q ?? "");
  const [shownText, setShownText] = useState(filters.q);
  if (shownText !== filters.q) {
    // Back and forward change the searched text; the box follows it.
    setShownText(filters.q);
    if ((text.trim() || undefined) !== filters.q) setText(filters.q ?? "");
  }
  const [open, setOpen] = useState<AuditEvent | null>(null);
  useEffect(() => {
    const searched = text.trim() || undefined;
    if (searched === filters.q) return;
    const timer = setTimeout(
      () =>
        void navigate({
          search: (current) => ({ ...current, q: searched }),
          replace: true,
          resetScroll: false,
        }),
      300,
    );
    return () => clearTimeout(timer);
  }, [text, filters.q, navigate]);

  // The period is fixed when the filters change, so later pages share the first page's bounds.
  const query = useMemo<NonNullable<ListAuditEventsData["query"]>>(
    () => ({
      from: periodStart(period),
      q: filters.q,
      eventClass: filters.eventClass,
      outcome: filters.outcome,
      size: PAGE_SIZE,
    }),
    [period, filters.q, filters.eventClass, filters.outcome],
  );
  const events = useInfiniteQuery({
    ...listAuditEventsInfiniteOptions({ query }),
    initialPageParam: undefined as unknown as string,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  // The page belongs to the filters it was read with; new filters start again at the newest events.
  const [paging, setPaging] = useState({ query, page: 0 });
  const page = paging.query === query ? paging.page : 0;
  const setPage = (next: number) => setPaging({ query, page: next });
  const pages = events.data?.pages ?? [];
  const rows = pages[Math.min(page, Math.max(pages.length - 1, 0))]?.items ?? [];
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
              <Download aria-hidden="true" />
              {ui("Export CSV")}
            </a>
          </Button>
        }
      />

      <div className="flex flex-wrap items-center gap-2" role="group" aria-label={ui("Filters")}>
        <Select
          value={period}
          onValueChange={(value) => setFilters({ period: value as AuditPeriod })}
        >
          <SelectTrigger aria-label={ui("Period")} className="w-40">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {periods.map((period) => (
              <SelectItem key={period} value={period}>
                {ui(periodLabels[period])}
              </SelectItem>
            ))}
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
            <SelectItem value={ALL}>{ui("All categories")}</SelectItem>
            {classes.map((value) => (
              <SelectItem key={value} value={value}>
                {ui(classLabels[value])}
              </SelectItem>
            ))}
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
            <SelectItem value={ALL}>{ui("All outcomes")}</SelectItem>
            {outcomes.map((value) => (
              <SelectItem key={value} value={value}>
                {ui(outcomeLabels[value])}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <div className="relative min-w-56 flex-1">
          <Search
            aria-hidden="true"
            className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-content-muted"
          />
          <Input
            value={text}
            onChange={(event) => setText(event.target.value)}
            placeholder={ui("Search people or items")}
            aria-label={ui("Search people or items")}
            className="pl-8"
          />
        </div>
      </div>

      {events.isPending ? (
        <p role="status" className="font-secondary-body text-content-muted">
          {ui("Loading the audit log…")}
        </p>
      ) : events.isError ? (
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
      ) : rows.length === 0 ? (
        <EmptyState
          icon={<ScrollText />}
          title={ui("No events match these filters.")}
          detail={ui("Choose a longer period or clear the search.")}
        />
      ) : (
        <div className="min-w-0 overflow-hidden rounded-xl border border-border-subtle">
          <div
            role="region"
            aria-label={ui("Scrollable audit log")}
            tabIndex={0}
            className="overflow-x-auto outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40 focus-visible:ring-inset"
          >
            <Table className="w-full min-w-[48rem] table-fixed border-collapse">
              <TableCaption className="sr-only">{ui("Audit log")}</TableCaption>
              <colgroup>
                <col className="w-[9.5rem]" />
                <col className="w-[30%]" />
                <col />
                <col className="w-[8.5rem]" />
              </colgroup>
              <TableHeader className="border-b border-border-subtle bg-surface-subtle text-left">
                <TableRow>
                  <TableHead scope="col" className="h-11 px-4">
                    {ui("Time")}
                  </TableHead>
                  <TableHead scope="col" className="h-11 px-4">
                    {ui("Person")}
                  </TableHead>
                  <TableHead scope="col" className="h-11 px-4">
                    {ui("Activity")}
                  </TableHead>
                  <TableHead scope="col" className="h-11 px-4">
                    {ui("IP address")}
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody className="divide-y divide-border-subtle">
                {rows.map((event) => {
                  const person = event.actorLabel ?? ui("System");
                  return (
                    <TableRow
                      key={event.id}
                      className="cursor-pointer bg-surface-raised align-middle transition-colors hover:bg-surface-subtle"
                      onClick={() => setOpen(event)}
                    >
                      <TableCell className="h-16 px-4 py-3 tabular-nums">
                        <span className="block text-content-primary">
                          {formatUiDate(event.occurredAt, {
                            day: "2-digit",
                            month: "2-digit",
                            year: "numeric",
                          })}
                        </span>
                        <span className="block font-secondary-body text-content-muted">
                          {formatUiDate(event.occurredAt, { hour: "2-digit", minute: "2-digit" })}
                        </span>
                      </TableCell>
                      <TableCell className="px-4 py-3">
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
                      </TableCell>
                      <TableCell className="px-4 py-3">
                        {/* One sentence with its object, as 1Password and PlanetScale write an event. */}
                        <button
                          type="button"
                          className="block max-w-full truncate rounded-sm text-left text-content-secondary focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
                          onClick={(click) => {
                            click.stopPropagation();
                            setOpen(event);
                          }}
                        >
                          {actionLabels[event.action]
                            ? ui(actionLabels[event.action]!)
                            : event.action}
                          {event.resourceLabel ? (
                            <span className="font-main-ui-action text-content-primary">
                              {" "}
                              {event.resourceLabel}
                            </span>
                          ) : null}
                        </button>
                        {/* Most events succeed; only the ones that did not carry a mark. */}
                        {event.outcome !== "SUCCESS" ? (
                          <StatusBadge
                            tone={outcomeTones[event.outcome]}
                            size="sm"
                            className="mt-1"
                          >
                            {ui(outcomeLabels[event.outcome])}
                          </StatusBadge>
                        ) : null}
                      </TableCell>
                      <TableCell className="px-4 py-3 font-secondary-body text-content-muted tabular-nums">
                        {event.sourceIp ?? "—"}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
          {/* The stream only grows, so there is no total: the pager counts the pages read so far. */}
          <TablePagination
            label={ui("Audit log pages")}
            page={page}
            totalPages={events.hasNextPage ? undefined : pages.length}
            previousDisabled={page === 0}
            nextDisabled={
              events.isFetchingNextPage || (page + 1 >= pages.length && !events.hasNextPage)
            }
            onPrevious={() => setPage(page - 1)}
            onNext={() => {
              if (page + 1 < pages.length) setPage(page + 1);
              else void events.fetchNextPage().then(() => setPage(page + 1));
            }}
          />
        </div>
      )}

      <AuditEventSheet event={open} onClose={() => setOpen(null)} />
    </SettingsLayout>
  );
}
