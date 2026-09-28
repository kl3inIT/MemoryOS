import { useInfiniteQuery } from "@tanstack/react-query";
import {
  createColumnHelper,
  rowPaginationFeature,
  tableFeatures,
  useTable,
  type PaginationState,
} from "@tanstack/react-table";
import { CircleAlert, Download, MessagesSquare, Search, ThumbsDown, ThumbsUp } from "lucide-react";
import { createContext, use, useMemo, useState } from "react";
import { DataTable, type DataTableColumnMeta } from "@/components/data-table/data-table";
import { EmptyState } from "@/components/composites/empty-state";
import { PersonAvatar } from "@/components/composites/person-avatar";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { StatStrip, StatTile } from "@/components/composites/stat-strip";
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
import { StatusBadge } from "@/components/ui/status-badge";
import { TablePagination } from "@/components/ui/table-pagination";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { appText } from "@/i18n/app-text";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listChatHistoryInfiniteOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatHistoryEntry, ListChatHistoryData } from "@/lib/hey-api/types.gen";
import { ChatHistoryDialog } from "./chat-history-dialog";
import {
  askerName,
  feedbackLabels,
  feedbackTones,
  periodLabels,
  periodStart,
  type HistoryPeriod,
} from "./chat-history";

const ALL = "all";
const PAGE_SIZE = 30;
const periods: HistoryPeriod[] = ["1d", "7d", "30d", "90d"];
const feedbacks = Object.keys(feedbackLabels) as ChatHistoryEntry["feedback"][];

type Filters = {
  period: HistoryPeriod;
  feedback: ChatHistoryEntry["feedback"] | typeof ALL;
};

/** Opens a conversation's transcript; the question cell reads it. */
const OpenTranscript = createContext<(entry: ChatHistoryEntry) => void>(() => {});

const features = tableFeatures({ rowPaginationFeature, columnMeta: {} as DataTableColumnMeta });
const column = createColumnHelper<typeof features, ChatHistoryEntry>();

/*
 * Columns live at module scope: a cell renderer is a component, so a column list rebuilt during render
 * would remount every cell.
 */
const columns = column.columns([
  column.accessor("updatedAt", {
    header: function TimeHeader() {
      const ui = useAppTranslation();
      return ui("Time");
    },
    meta: { width: "w-38" },
    cell: ({ row }) => (
      <>
        <span className="block text-content-primary">
          {formatUiDate(row.original.updatedAt, {
            day: "2-digit",
            month: "2-digit",
            year: "numeric",
          })}
        </span>
        <span className="block font-secondary-body text-content-muted">
          {formatUiDate(row.original.updatedAt, { hour: "2-digit", minute: "2-digit" })}
        </span>
      </>
    ),
  }),
  column.display({
    id: "asker",
    header: function AskerHeader() {
      const ui = useAppTranslation();
      return ui("Asked by");
    },
    meta: { width: "w-1/4" },
    cell: function AskerCell({ row }) {
      const ui = useAppTranslation();
      const entry = row.original;
      const person = ui(askerName(entry));
      return (
        <span className="flex min-w-0 items-center gap-2.5">
          <PersonAvatar name={person} seed={entry.actorId ?? entry.id} />
          <span className="min-w-0">
            <span className="block truncate font-main-ui-action text-content-primary">
              {person}
            </span>
            {entry.email && entry.email !== entry.person ? (
              <span className="block truncate font-secondary-body text-content-muted">
                {entry.email}
              </span>
            ) : null}
          </span>
        </span>
      );
    },
  }),
  column.display({
    id: "question",
    header: function QuestionHeader() {
      const ui = useAppTranslation();
      return ui("Question");
    },
    cell: function QuestionCell({ row }) {
      const ui = useAppTranslation();
      const open = use(OpenTranscript);
      const entry = row.original;
      return (
        <>
          <button
            type="button"
            className="block max-w-full truncate rounded-sm text-left text-content-secondary focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
            onClick={() => open(entry)}
          >
            {entry.question ?? entry.title}
          </button>
          <span className="mt-1 flex items-center gap-2">
            <span className="font-secondary-body text-content-muted">
              {ui(appText("{{count}} messages", { count: entry.messages }))}
            </span>
            {/* The owner deleted this conversation; it is kept until retention removes it. */}
            {entry.deleted ? (
              <StatusBadge tone="neutral" size="sm">
                {ui("Deleted")}
              </StatusBadge>
            ) : null}
          </span>
        </>
      );
    },
  }),
  column.accessor("feedback", {
    header: function FeedbackHeader() {
      const ui = useAppTranslation();
      return ui("Feedback");
    },
    meta: { width: "w-36" },
    cell: function FeedbackCell({ row }) {
      const ui = useAppTranslation();
      const { feedback } = row.original;
      return feedback === "NONE" ? (
        <span className="font-secondary-body text-content-muted">—</span>
      ) : (
        <StatusBadge tone={feedbackTones[feedback]} size="sm">
          {ui(feedbackLabels[feedback])}
        </StatusBadge>
      );
    },
  }),
]);

/** The conversation history for the filters, read one cursor page after another. */
function useHistory(query: NonNullable<ListChatHistoryData["query"]>) {
  return useInfiniteQuery({
    ...listChatHistoryInfiniteOptions({ query }),
    initialPageParam: undefined as unknown as string,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
}

/**
 * Admin › Monitoring › Conversation history (Onyx query history): what the organization asks, how the answers were
 * rated, and one conversation read in full. Every transcript opened is itself recorded in the audit log.
 */
export function ChatHistoryPage() {
  const ui = useAppTranslation();
  const [filters, setFilters] = useState<Filters>({ period: "7d", feedback: ALL });
  const [text, setText] = useState("");
  const typed = useDebouncedValue(text.trim(), 300);
  const [open, setOpen] = useState<ChatHistoryEntry | null>(null);

  // The period is fixed when the filters change, so later pages share the first page's bounds.
  const query = useMemo<NonNullable<ListChatHistoryData["query"]>>(
    () => ({
      from: periodStart(filters.period),
      q: typed || undefined,
      feedback: filters.feedback === ALL ? undefined : filters.feedback,
      size: PAGE_SIZE,
    }),
    [filters, typed],
  );
  const history = useHistory(query);
  const totals = history.data?.pages[0];
  const exportHref = `/api/chat/history/export?${new URLSearchParams(
    Object.entries({ ...query, size: undefined }).filter(
      (entry): entry is [string, string] => typeof entry[1] === "string",
    ),
  ).toString()}`;

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("Conversation history")}
        icon={<MessagesSquare />}
        description={ui(
          "What people asked the assistant and how the answers were rated. Opening a conversation is recorded in the audit log.",
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

      <StatStrip columns={3}>
        <StatTile
          icon={<MessagesSquare />}
          label={ui("Conversations")}
          value={String(totals?.conversations ?? 0)}
          loading={history.isPending}
        />
        <StatTile
          icon={<ThumbsUp />}
          label={ui("Marked good")}
          value={String(totals?.positive ?? 0)}
          loading={history.isPending}
        />
        <StatTile
          icon={<ThumbsDown />}
          label={ui("Marked bad")}
          value={String(totals?.negative ?? 0)}
          loading={history.isPending}
        />
      </StatStrip>

      <div className="flex flex-wrap items-center gap-2" role="group" aria-label={ui("Filters")}>
        <Select
          value={filters.period}
          onValueChange={(value) => setFilters({ ...filters, period: value as HistoryPeriod })}
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
          value={filters.feedback}
          onValueChange={(value) =>
            setFilters({ ...filters, feedback: value as Filters["feedback"] })
          }
        >
          <SelectTrigger aria-label={ui("Feedback")} className="w-44">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              <SelectItem value={ALL}>{ui("Any feedback")}</SelectItem>
              {feedbacks.map((feedback) => (
                <SelectItem key={feedback} value={feedback}>
                  {ui(feedbackLabels[feedback])}
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
            placeholder={ui("Search a person or a title")}
            aria-label={ui("Search a person or a title")}
          />
        </InputGroup>
      </div>

      {history.isError ? (
        <EmptyState
          role="alert"
          icon={<CircleAlert />}
          title={ui("Conversation history could not be loaded.")}
          action={
            <Button prominence="secondary" onClick={() => void history.refetch()}>
              {ui("Try again")}
            </Button>
          }
        />
      ) : history.isPending ? (
        <p role="status" className="font-secondary-body text-content-muted">
          {ui("Loading conversation history…")}
        </p>
      ) : history.data.pages[0]?.items.length === 0 ? (
        <EmptyState
          icon={<MessagesSquare />}
          title={ui("No conversation in this period.")}
          detail={ui("Widen the period or clear the filters.")}
        />
      ) : (
        <OpenTranscript value={setOpen}>
          {/* Keyed on the filters, so a new query starts on its first page. */}
          <HistoryTable key={JSON.stringify(query)} history={history} />
        </OpenTranscript>
      )}

      <ChatHistoryDialog entry={open} onClose={() => setOpen(null)} />
    </SettingsLayout>
  );
}

/** The conversations one cursor page at a time; the pages read so far stay in the query. */
function HistoryTable({ history }: { history: ReturnType<typeof useHistory> }) {
  const ui = useAppTranslation();
  const open = use(OpenTranscript);
  const [pagination, setPagination] = useState<PaginationState>({
    pageIndex: 0,
    pageSize: PAGE_SIZE,
  });
  const pages = history.data?.pages ?? [];
  const rows = pages[Math.min(pagination.pageIndex, Math.max(pages.length - 1, 0))]?.items ?? [];
  const table = useTable({
    features,
    columns,
    data: rows,
    getRowId: (entry) => entry.id,
    manualPagination: true,
    pageCount: pages.length + (history.hasNextPage ? 1 : 0),
    state: { pagination },
    onPaginationChange: (updater) =>
      setPagination(typeof updater === "function" ? updater(pagination) : updater),
  });
  return (
    <DataTable
      table={table}
      label={ui("Conversation history")}
      className="min-w-208"
      // The row is a larger pointer target for the question's button, which stays the keyboard's way in.
      rowProps={(row) => ({
        onClick: (event) => {
          if (!(event.target as Element).closest("button, a")) open(row.original);
        },
      })}
      footer={
        <TablePagination
          label={ui("Conversation history")}
          page={pagination.pageIndex}
          totalPages={undefined}
          previousDisabled={!table.getCanPreviousPage()}
          nextDisabled={history.isFetchingNextPage || !table.getCanNextPage()}
          onPrevious={() => table.previousPage()}
          onNext={() => {
            if (pagination.pageIndex + 1 < pages.length) table.nextPage();
            else void history.fetchNextPage().then(() => table.nextPage());
          }}
        />
      }
    />
  );
}
