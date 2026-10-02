import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import {
  createColumnHelper,
  rowPaginationFeature,
  tableFeatures,
  useTable,
} from "@tanstack/react-table";
import { Activity, CircleAlert, Search } from "lucide-react";
import { useEffect, useEffectEvent, useMemo, useState } from "react";
import { DataTable, type DataTableColumnMeta } from "@/components/data-table/data-table";
import { cursorTablePaging, useCursorPaging } from "@/components/data-table/use-cursor-paging";
import { EmptyState } from "@/components/composites/empty-state";
import { PersonAvatar } from "@/components/composites/person-avatar";
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
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import type { AppCopy } from "@/i18n/app-text";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listMcpEndpointActivityOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ListMcpEndpointActivityData, McpEndpointCallResponse } from "@/lib/hey-api/types.gen";
import { McpClientMark } from "./mcp-client-mark";
import {
  activityClients,
  activityOutcomes,
  activityPeriodDays,
  activityPeriodLabels,
  activityPeriodStart,
  activityTools,
  type ActivityPeriod,
  type McpEndpointAdminSearch,
} from "./mcp-endpoint-admin-search";

const ALL = "all";
const PAGE_SIZE = 50;
const periods = Object.keys(activityPeriodDays) as ActivityPeriod[];

type Outcome = McpEndpointCallResponse["outcome"];
type Client = McpEndpointCallResponse["client"];

const outcomeLabels: Record<Outcome, AppCopy> = {
  SUCCESS: "Thành công",
  REFUSED: "Bị từ chối",
  FAILED: "Thất bại",
  RATE_LIMITED: "Vượt giới hạn",
};
const outcomeTones = { REFUSED: "warning", FAILED: "danger", RATE_LIMITED: "warning" } as const;
const clientLabels: Record<Client, AppCopy> = {
  CLAUDE: "Claude",
  CHATGPT: "ChatGPT",
  OTHER: "Ứng dụng khác",
};

const endpointRoute = getRouteApi("/_authenticated/admin/mcp-endpoint");

const features = tableFeatures({
  rowPaginationFeature,
  columnMeta: {} as DataTableColumnMeta,
});
const column = createColumnHelper<typeof features, McpEndpointCallResponse>();

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
          {formatUiDate(row.original.occurredAt, {
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
          })}
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
    meta: { width: "w-1/4" },
    cell: function PersonCell({ row }) {
      const call = row.original;
      const person = call.actorName ?? call.actorEmail ?? call.actorId;
      return (
        <span className="flex min-w-0 items-center gap-2.5">
          <PersonAvatar name={person} seed={call.actorId} />
          <span className="min-w-0">
            <span
              className="block truncate font-main-ui-action text-content-primary"
              title={person}
            >
              {person}
            </span>
            {call.actorEmail && call.actorEmail !== person ? (
              <span className="block truncate font-secondary-body text-content-muted">
                {call.actorEmail}
              </span>
            ) : null}
          </span>
        </span>
      );
    },
  }),
  column.display({
    id: "app",
    header: function AppHeader() {
      const ui = useAppTranslation();
      return ui("Ứng dụng");
    },
    meta: { width: "w-56" },
    cell: ({ row }) => (
      <span className="flex min-w-0 items-center gap-2">
        <McpClientMark client={row.original.client} className="size-4 shrink-0" />
        <span className="truncate text-content-primary" title={row.original.clientName}>
          {row.original.clientName}
        </span>
      </span>
    ),
  }),
  column.accessor("tool", {
    header: function ToolHeader() {
      const ui = useAppTranslation();
      return ui("Công cụ");
    },
    cell: ({ row }) => (
      <span className="font-mono text-xs text-content-secondary">{row.original.tool}</span>
    ),
  }),
  column.accessor("outcome", {
    header: function OutcomeHeader() {
      const ui = useAppTranslation();
      return ui("Kết quả");
    },
    meta: { width: "w-36" },
    cell: function OutcomeCell({ row }) {
      const ui = useAppTranslation();
      const outcome = row.original.outcome;
      // Most calls succeed; only the ones that did not carry a mark.
      return outcome === "SUCCESS" ? (
        <span className="text-content-muted">{ui(outcomeLabels.SUCCESS)}</span>
      ) : (
        <StatusBadge tone={outcomeTones[outcome]} size="sm">
          {ui(outcomeLabels[outcome])}
        </StatusBadge>
      );
    },
  }),
]);

/**
 * MEM-209: each tool call through the endpoint, newest first, with who called, through which app, which tool and how
 * it ended; never the query or a document, as Glean logs its MCP servers.
 */
export function McpEndpointActivity() {
  const filters = endpointRoute.useSearch();
  // The period is fixed when the filters change, so later pages share the first page's bounds.
  const query = useMemo<NonNullable<ListMcpEndpointActivityData["query"]>>(
    () => ({
      from: activityPeriodStart(filters.period),
      client: filters.client,
      tool: filters.tool,
      outcome: filters.outcome,
      person: filters.person,
      size: PAGE_SIZE,
    }),
    [filters.period, filters.client, filters.tool, filters.outcome, filters.person],
  );
  return (
    <>
      <ActivityFilters filters={filters} />
      {/* The calls belong to the filters they were read with; new filters start again at the newest. */}
      <ActivityCalls key={JSON.stringify(query)} query={query} />
    </>
  );
}

function ActivityFilters({ filters }: { filters: McpEndpointAdminSearch }) {
  const ui = useAppTranslation();
  const navigate = endpointRoute.useNavigate();
  const setFilters = (next: Partial<McpEndpointAdminSearch>) =>
    void navigate({
      search: (current) => ({ ...current, ...next }),
      replace: true,
      resetScroll: false,
    });
  const [text, setText] = useState(filters.person ?? "");
  const [shownText, setShownText] = useState(filters.person);
  if (shownText !== filters.person) {
    // Back and forward change the searched person; the box follows it.
    setShownText(filters.person);
    if ((text.trim() || undefined) !== filters.person) setText(filters.person ?? "");
  }
  // The typed name reaches the address once typing pauses.
  const searched = useDebouncedValue(text.trim() || undefined, 300);
  const writeSearch = useEffectEvent((person: string | undefined) => {
    if (person !== filters.person) setFilters({ person });
  });
  useEffect(() => writeSearch(searched), [searched]);

  return (
    <div
      // Two filters a row on a phone; one line where they fit.
      className="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:items-center"
      role="group"
      aria-label={ui("Filters")}
    >
      <Select
        value={filters.period}
        onValueChange={(value) => setFilters({ period: value as ActivityPeriod })}
      >
        <SelectTrigger aria-label={ui("Period")} className="w-full sm:w-40">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            {periods.map((period) => (
              <SelectItem key={period} value={period}>
                {ui(activityPeriodLabels[period])}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
      <Select
        value={filters.client ?? ALL}
        onValueChange={(value) =>
          setFilters({ client: value === ALL ? undefined : (value as Client) })
        }
      >
        <SelectTrigger aria-label={ui("Ứng dụng")} className="w-full sm:w-40">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            <SelectItem value={ALL}>{ui("Mọi ứng dụng")}</SelectItem>
            {activityClients.map((value) => (
              <SelectItem key={value} value={value}>
                {ui(clientLabels[value])}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
      <Select
        value={filters.tool ?? ALL}
        onValueChange={(value) =>
          setFilters({
            tool: value === ALL ? undefined : (value as (typeof activityTools)[number]),
          })
        }
      >
        <SelectTrigger aria-label={ui("Công cụ")} className="w-full sm:w-48">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            <SelectItem value={ALL}>{ui("Mọi công cụ")}</SelectItem>
            {activityTools.map((value) => (
              <SelectItem key={value} value={value}>
                <span className="font-mono text-xs">{value}</span>
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
      <Select
        value={filters.outcome ?? ALL}
        onValueChange={(value) =>
          setFilters({ outcome: value === ALL ? undefined : (value as Outcome) })
        }
      >
        <SelectTrigger aria-label={ui("Kết quả")} className="w-full sm:w-40">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            <SelectItem value={ALL}>{ui("Mọi kết quả")}</SelectItem>
            {activityOutcomes.map((value) => (
              <SelectItem key={value} value={value}>
                {ui(outcomeLabels[value])}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
      <InputGroup className="col-span-2 sm:min-w-56 sm:flex-1">
        <InputGroupAddon>
          <Search aria-hidden="true" />
        </InputGroupAddon>
        <InputGroupInput
          value={text}
          maxLength={200}
          onChange={(event) => setText(event.target.value)}
          placeholder={ui("Tìm theo tên hoặc email")}
          aria-label={ui("Tìm theo tên hoặc email")}
        />
      </InputGroup>
    </div>
  );
}

/** One filter selection's calls, newest first, paged by the cursors the API returns. */
function ActivityCalls({ query }: { query: NonNullable<ListMcpEndpointActivityData["query"]> }) {
  const ui = useAppTranslation();
  const paging = useCursorPaging();
  const calls = useQuery({
    ...listMcpEndpointActivityOptions({ query: { ...query, cursor: paging.cursor } }),
    placeholderData: keepPreviousData,
  });
  const nextCursor = calls.data?.next;
  // The log only grows, so there is no total: the last page is known once it has no successor.
  const totalPages = calls.data && !nextCursor ? paging.page + 1 : undefined;
  const table = useTable({
    features,
    columns,
    data: calls.data?.calls ?? [],
    getRowId: (call) => call.id,
    ...cursorTablePaging(paging, PAGE_SIZE, nextCursor, totalPages),
  });

  if (calls.isPending)
    return (
      <div role="status" aria-label={ui("Đang tải hoạt động…")} className="flex flex-col gap-2">
        {Array.from({ length: 4 }, (_, index) => (
          <Skeleton key={index} className="h-16" />
        ))}
      </div>
    );
  if (calls.isError)
    return (
      <EmptyState
        role="alert"
        icon={<CircleAlert />}
        title={ui("Không tải được hoạt động.")}
        action={
          <Button prominence="secondary" onClick={() => void calls.refetch()}>
            {ui("Thử lại")}
          </Button>
        }
      />
    );
  if (calls.data.calls.length === 0 && !paging.hasPrevious)
    return <EmptyState icon={<Activity />} title={ui("Chưa có lượt gọi nào khớp bộ lọc.")} />;
  return (
    <DataTable
      table={table}
      label={ui("Hoạt động MemoryOS MCP")}
      className="min-w-192"
      footer={
        <TablePagination
          label={ui("Trang hoạt động")}
          page={paging.page}
          totalPages={totalPages}
          previousDisabled={calls.isPlaceholderData || !table.getCanPreviousPage()}
          nextDisabled={calls.isPlaceholderData || !table.getCanNextPage()}
          onPrevious={() => table.previousPage()}
          onNext={() => table.nextPage()}
        />
      }
    />
  );
}
