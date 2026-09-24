import { useEffect, useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { MessageSquare, Search, SquarePen, X } from "lucide-react";
import { Dialog } from "radix-ui";
import { groupThreadTitles } from "@/components/assistant-ui/elements/thread-list";
import {
  Command,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { SidebarTab } from "@/components/ui/sidebar-tab";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { searchChatSessions } from "@/lib/hey-api/sdk.gen";

const PAGE_SIZE = 20;
// ChatSessionMatch wraps matched tokens in U+E000/U+E001; private-use characters never render.
const MATCH_DELIMITER = new RegExp(
  `[${String.fromCharCode(0xe000)}${String.fromCharCode(0xe001)}]`,
);

/**
 * Server search over the whole history in a command palette (ChatGPT search layout): a blank query lists New chat and
 * recent conversations by day; a query lists matches with the matched message fragment. No second thread store.
 */
export function ChatHistorySearch({
  variant,
  onNavigate,
}: {
  /** `icon` sits beside the sidebar collapse button; `tab` is the collapsed rail entry. */
  variant: "icon" | "tab";
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();
  const navigate = useNavigate();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const shortcut = (event: KeyboardEvent) => {
      // The mobile drawer can mount a second sidebar; the first handler wins.
      if (event.defaultPrevented || event.key.toLowerCase() !== "k") return;
      if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey) return;
      event.preventDefault();
      setOpen(true);
    };
    window.addEventListener("keydown", shortcut);
    return () => window.removeEventListener("keydown", shortcut);
  }, []);
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedQuery(query.trim()), 250);
    return () => clearTimeout(timer);
  }, [query]);
  const waiting = query.trim() !== debouncedQuery;
  const results = useInfiniteQuery({
    queryKey: ["chat-history-search", actorId, authorizationVersion, debouncedQuery],
    enabled: open && !waiting,
    initialPageParam: 0,
    queryFn: async ({ pageParam, signal }) =>
      (
        await searchChatSessions({
          query: { query: debouncedQuery, offset: pageParam, limit: PAGE_SIZE },
          signal,
        })
      ).data,
    getNextPageParam: (last, pages) =>
      last.hasMore && pages.length * PAGE_SIZE <= 10000 ? pages.length * PAGE_SIZE : undefined,
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  const items = waiting ? [] : (results.data?.pages.flatMap((page) => page.items) ?? []);
  // Live pagination may shift when another tab renames or updates a session.
  const unique = [
    ...new Map(
      items.map((item) => [item.session.id, { ...item.session, snippet: item.snippet }]),
    ).values(),
  ];
  const groups = groupThreadTitles(unique);
  const groupLabels = { today: ui("Hôm nay"), yesterday: ui("Hôm qua"), earlier: ui("Trước đó") };
  const blank = query.trim() === "";
  const label = ui("Tìm hội thoại");

  function go(to: () => Promise<void>) {
    setOpen(false);
    void to();
    onNavigate?.();
  }

  return (
    <Dialog.Root
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setQuery("");
      }}
    >
      <Dialog.Trigger asChild>
        {variant === "icon" ? (
          <IconButton
            prominence="internal"
            size="sm"
            aria-label={label}
            title={label}
            aria-keyshortcuts="Control+K Meta+K"
            className="text-content-secondary"
          >
            <Search />
          </IconButton>
        ) : (
          <SidebarTab
            icon={<Search className="size-4" />}
            collapsed
            aria-keyshortcuts="Control+K Meta+K"
          >
            {label}
          </SidebarTab>
        )}
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-surface-scrim backdrop-blur-[2px] data-[state=open]:animate-in data-[state=open]:fade-in motion-reduce:animate-none" />
        <Dialog.Content className="fixed top-[max(1rem,12dvh)] left-1/2 z-50 flex max-h-[min(36rem,calc(100dvh-2rem))] w-[min(40rem,calc(100vw-2rem))] -translate-x-1/2 flex-col overflow-hidden rounded-2xl border border-border-default bg-surface-overlay shadow-lg outline-none data-[state=open]:animate-in data-[state=open]:fade-in data-[state=open]:zoom-in-95 motion-reduce:animate-none">
          <Dialog.Title className="sr-only">{label}</Dialog.Title>
          <Dialog.Description className="sr-only">
            {ui(
              "Tìm tiêu đề và nội dung mọi phiên bản đã lưu. Kết quả mở ở nhánh hiện tại của hội thoại.",
            )}
          </Dialog.Description>
          <Command
            shouldFilter={false}
            label={label}
            className="min-h-0 flex-1 rounded-none bg-transparent"
          >
            <CommandInput
              value={query}
              onValueChange={setQuery}
              maxLength={200}
              autoFocus
              aria-label={ui("Tìm trong toàn bộ lịch sử")}
              placeholder={ui("Tìm trong toàn bộ lịch sử…")}
              className="h-13 pr-10 font-main-ui-body"
            />
            <CommandList className="max-h-none min-h-0 flex-1 p-2">
              {blank ? (
                <CommandItem
                  value="new-chat"
                  className="gap-3 px-3 py-2.5 font-main-ui-body"
                  onSelect={() => go(() => navigate({ to: "/" }))}
                >
                  <SquarePen
                    className="size-4 shrink-0 text-content-secondary"
                    aria-hidden="true"
                  />
                  {ui("Hội thoại mới")}
                </CommandItem>
              ) : null}
              {waiting || results.isPending ? (
                <p role="status" className="px-3 py-4 text-sm text-content-muted">
                  {blank ? ui("Đang tải hội thoại…") : ui("Đang tìm hội thoại…")}
                </p>
              ) : results.isError ? (
                <div role="alert" className="space-y-2 px-3 py-4">
                  <p>{ui("Không tìm được hội thoại. Hãy thử lại.")}</p>
                  <Button size="sm" onClick={() => void results.refetch()}>
                    {ui("Thử lại")}
                  </Button>
                </div>
              ) : unique.length === 0 ? (
                <p role="status" className="px-3 py-4 text-sm text-content-muted">
                  {blank ? ui("Chưa có hội thoại.") : ui("Không có hội thoại phù hợp.")}
                </p>
              ) : (
                groups.map((group) => (
                  <CommandGroup key={group.label} heading={groupLabels[group.label]}>
                    {group.items.map((session) => (
                      <CommandItem
                        key={session.id}
                        value={session.id}
                        className="items-start gap-3 px-3 py-2.5"
                        onSelect={() =>
                          go(() =>
                            navigate({
                              to: "/chat/$sessionId",
                              params: { sessionId: session.id },
                            }),
                          )
                        }
                      >
                        <MessageSquare
                          className="mt-0.5 size-4 shrink-0 text-content-muted"
                          aria-hidden="true"
                        />
                        <span className="min-w-0 flex-1">
                          <span className="flex min-w-0 items-center gap-1.5">
                            <span
                              className="min-w-0 truncate font-main-ui-body text-content-primary"
                              title={session.title}
                            >
                              {session.title}
                            </span>
                            {session.archivedAt ? (
                              <Badge variant="secondary" className="shrink-0">
                                {ui("Đã lưu trữ")}
                              </Badge>
                            ) : null}
                          </span>
                          {session.snippet ? (
                            <span className="mt-0.5 line-clamp-2 text-xs text-content-secondary">
                              <MatchSnippet text={session.snippet} />
                            </span>
                          ) : null}
                        </span>
                        <time
                          dateTime={session.updatedAt}
                          className="shrink-0 pt-0.5 text-xs whitespace-nowrap text-content-muted tabular-nums"
                        >
                          {sessionTime(session.updatedAt)}
                        </time>
                      </CommandItem>
                    ))}
                  </CommandGroup>
                ))
              )}
              {!waiting && results.hasNextPage ? (
                <div className="px-1 pt-1">
                  <Button
                    size="sm"
                    prominence="internal"
                    pending={results.isFetchingNextPage}
                    onClick={() => void results.fetchNextPage()}
                  >
                    {ui("Xem thêm kết quả")}
                  </Button>
                </div>
              ) : null}
              {!waiting && !results.hasNextPage && results.data?.pages.at(-1)?.hasMore ? (
                <p className="px-3 pt-1 text-xs text-content-muted">
                  {ui("Có thêm kết quả. Hãy nhập cụ thể hơn.")}
                </p>
              ) : null}
            </CommandList>
          </Command>
          <Dialog.Close asChild>
            <IconButton
              prominence="internal"
              size="sm"
              aria-label={ui("Close")}
              className="absolute top-2.5 right-2.5 text-content-secondary"
            >
              <X />
            </IconButton>
          </Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function MatchSnippet({ text }: { text: string }) {
  // Odd parts are the tokens the server matched; everything is rendered as text, never HTML.
  return text
    .replace(/\s+/g, " ")
    .split(MATCH_DELIMITER)
    .map((part, index) =>
      index % 2 === 1 ? (
        <mark
          key={index}
          className="rounded-[3px] bg-evidence-highlight-surface font-medium text-content-primary [box-decoration-break:clone]"
        >
          {part}
        </mark>
      ) : (
        part
      ),
    );
}

function sessionTime(value: string, now = new Date()) {
  const date = new Date(value);
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  // Today and Yesterday groups already name the day, so their rows show the time.
  if (date >= yesterday) {
    return formatUiDate(date, { hour: "2-digit", minute: "2-digit" });
  }
  return formatUiDate(date, {
    day: "numeric",
    month: "short",
    ...(date.getFullYear() === now.getFullYear() ? {} : { year: "numeric" }),
  });
}
