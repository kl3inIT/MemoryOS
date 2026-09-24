import { useInfiniteQuery } from "@tanstack/react-query";
import { CircleAlert, Download, MessagesSquare, Search, ThumbsDown, ThumbsUp } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { PersonAvatar } from "@/components/composites/person-avatar";
import { StatStrip, StatTile } from "@/components/composites/stat-strip";
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
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { TablePagination } from "@/components/ui/table-pagination";
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
  text: string;
  feedback: ChatHistoryEntry["feedback"] | typeof ALL;
};

/**
 * Admin › Monitoring › Conversation history (Onyx query history): what the organization asks, how the answers were
 * rated, and one conversation read in full. Every transcript opened is itself recorded in the audit log.
 */
export function ChatHistoryPage() {
  const ui = useAppTranslation();
  const [filters, setFilters] = useState<Filters>({ period: "7d", text: "", feedback: ALL });
  const [text, setText] = useState("");
  const [open, setOpen] = useState<ChatHistoryEntry | null>(null);
  useEffect(() => {
    const timer = setTimeout(
      () =>
        setFilters((current) =>
          current.text === text.trim() ? current : { ...current, text: text.trim() },
        ),
      300,
    );
    return () => clearTimeout(timer);
  }, [text]);

  // The period is fixed when the filters change, so later pages share the first page's bounds.
  const query = useMemo<NonNullable<ListChatHistoryData["query"]>>(
    () => ({
      from: periodStart(filters.period),
      q: filters.text || undefined,
      feedback: filters.feedback === ALL ? undefined : filters.feedback,
      size: PAGE_SIZE,
    }),
    [filters],
  );
  const history = useInfiniteQuery({
    ...listChatHistoryInfiniteOptions({ query }),
    initialPageParam: undefined as unknown as string,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const [paging, setPaging] = useState({ query, page: 0 });
  const page = paging.query === query ? paging.page : 0;
  const setPage = (next: number) => setPaging({ query, page: next });
  const pages = history.data?.pages ?? [];
  const current = pages[Math.min(page, Math.max(pages.length - 1, 0))];
  const rows = current?.items ?? [];
  const totals = pages[0];
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
              <Download aria-hidden="true" />
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
            {periods.map((period) => (
              <SelectItem key={period} value={period}>
                {ui(periodLabels[period])}
              </SelectItem>
            ))}
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
            <SelectItem value={ALL}>{ui("Any feedback")}</SelectItem>
            {feedbacks.map((feedback) => (
              <SelectItem key={feedback} value={feedback}>
                {ui(feedbackLabels[feedback])}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <span className="relative min-w-56 flex-1">
          <Search
            aria-hidden="true"
            className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
          />
          <Input
            value={text}
            onChange={(event) => setText(event.target.value)}
            placeholder={ui("Search a person or a title")}
            aria-label={ui("Search a person or a title")}
            className="pl-9"
          />
        </span>
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
      ) : rows.length === 0 ? (
        <EmptyState
          icon={<MessagesSquare />}
          title={ui("No conversation in this period.")}
          detail={ui("Widen the period or clear the filters.")}
        />
      ) : (
        <div className="overflow-hidden rounded-md border border-border-subtle">
          <Table className="w-full min-w-[52rem] table-fixed border-collapse">
            <TableCaption className="sr-only">{ui("Conversation history")}</TableCaption>
            <colgroup>
              <col className="w-[9.5rem]" />
              <col className="w-[26%]" />
              <col />
              <col className="w-[9rem]" />
            </colgroup>
            <TableHeader className="border-b border-border-subtle bg-surface-subtle text-left">
              <TableRow>
                <TableHead scope="col" className="h-11 px-4">
                  {ui("Time")}
                </TableHead>
                <TableHead scope="col" className="h-11 px-4">
                  {ui("Asked by")}
                </TableHead>
                <TableHead scope="col" className="h-11 px-4">
                  {ui("Question")}
                </TableHead>
                <TableHead scope="col" className="h-11 px-4">
                  {ui("Feedback")}
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody className="divide-y divide-border-subtle">
              {rows.map((entry) => {
                const person = ui(askerName(entry));
                return (
                  <TableRow
                    key={entry.id}
                    className="cursor-pointer bg-surface-raised align-middle transition-colors hover:bg-surface-subtle"
                    onClick={() => setOpen(entry)}
                  >
                    <TableCell className="h-16 px-4 py-3 tabular-nums">
                      <span className="block text-content-primary">
                        {formatUiDate(entry.updatedAt, {
                          day: "2-digit",
                          month: "2-digit",
                          year: "numeric",
                        })}
                      </span>
                      <span className="block font-secondary-body text-content-muted">
                        {formatUiDate(entry.updatedAt, { hour: "2-digit", minute: "2-digit" })}
                      </span>
                    </TableCell>
                    <TableCell className="px-4 py-3">
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
                    </TableCell>
                    <TableCell className="px-4 py-3">
                      <button
                        type="button"
                        className="block max-w-full truncate rounded-sm text-left text-content-secondary focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
                        onClick={(click) => {
                          click.stopPropagation();
                          setOpen(entry);
                        }}
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
                    </TableCell>
                    <TableCell className="px-4 py-3">
                      {entry.feedback === "NONE" ? (
                        <span className="font-secondary-body text-content-muted">—</span>
                      ) : (
                        <StatusBadge tone={feedbackTones[entry.feedback]} size="sm">
                          {ui(feedbackLabels[entry.feedback])}
                        </StatusBadge>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
          <div className="border-t border-border-subtle bg-surface-raised px-4 py-2">
            <TablePagination
              label={ui("Conversation history")}
              page={page}
              totalPages={undefined}
              previousDisabled={page === 0}
              nextDisabled={!current?.nextCursor && !history.hasNextPage}
              onPrevious={() => setPage(Math.max(page - 1, 0))}
              onNext={() => {
                if (page + 1 < pages.length) setPage(page + 1);
                else void history.fetchNextPage().then(() => setPage(page + 1));
              }}
            />
          </div>
        </div>
      )}

      <ChatHistoryDialog entry={open} onClose={() => setOpen(null)} />
    </SettingsLayout>
  );
}
