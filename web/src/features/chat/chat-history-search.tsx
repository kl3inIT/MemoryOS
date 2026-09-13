import { useEffect, useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { MessageSquare, Search } from "lucide-react";
import { Command, CommandInput, CommandItem, CommandList } from "@/components/ui/command";
import { SidebarTab } from "@/components/ui/sidebar-tab";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { searchChatSessions } from "@/lib/hey-api/sdk.gen";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChatDialog } from "./chat-dialog";

/** Server search uses the shared command/dialog behavior, not a second thread store. */
export function ChatHistorySearch({
  collapsed = false,
  onNavigate,
}: {
  collapsed?: boolean;
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
          query: { query: debouncedQuery, offset: pageParam, limit: 20 },
          signal,
          throwOnError: true,
        })
      ).data,
    getNextPageParam: (last, pages) =>
      last.hasMore && pages.length * 20 <= 10000 ? pages.length * 20 : undefined,
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  const items = waiting ? [] : (results.data?.pages.flatMap((page) => page.items) ?? []);
  // Live pagination may shift when another tab renames or updates a session.
  const unique = [...new Map(items.map((item) => [item.id, item])).values()];
  return (
    <ChatDialog
      title={ui("Tìm hội thoại")}
      description={ui(
        "Tìm tiêu đề và nội dung mọi phiên bản đã lưu. Kết quả mở ở nhánh hiện tại của hội thoại.",
      )}
      open={open}
      onOpenChange={setOpen}
      trigger={
        <SidebarTab
          icon={<Search className="size-4" />}
          collapsed={collapsed}
          aria-keyshortcuts="Control+K Meta+K"
        >
          {ui("Tìm hội thoại")}
        </SidebarTab>
      }
    >
      <Command shouldFilter={false} label={ui("Tìm hội thoại")}>
        <CommandInput
          value={query}
          onValueChange={setQuery}
          maxLength={200}
          autoFocus
          aria-label={ui("Tìm trong toàn bộ lịch sử")}
          placeholder={ui("Tìm trong toàn bộ lịch sử…")}
        />
        <CommandList>
          {waiting || results.isPending ? (
            <p role="status" className="p-4 text-sm text-content-muted">
              {ui("Đang tìm hội thoại…")}
            </p>
          ) : results.isError ? (
            <div role="alert" className="space-y-2 p-4">
              <p>{ui("Không tìm được hội thoại. Hãy thử lại.")}</p>
              <Button size="sm" onClick={() => void results.refetch()}>
                {ui("Thử lại")}
              </Button>
            </div>
          ) : unique.length === 0 ? (
            <p role="status" className="p-4 text-sm text-content-muted">
              {ui("Không có hội thoại phù hợp.")}
            </p>
          ) : (
            unique.map((session) => (
              <CommandItem
                key={session.id}
                value={session.id}
                onSelect={() => {
                  setOpen(false);
                  void navigate({ to: "/chat/$sessionId", params: { sessionId: session.id } });
                  onNavigate?.();
                }}
              >
                <MessageSquare className="size-4 shrink-0 text-content-muted" aria-hidden="true" />
                <span className="min-w-0 flex-1 truncate" title={session.title}>
                  {session.title}
                </span>
              </CommandItem>
            ))
          )}
        </CommandList>
      </Command>
      {!waiting && results.hasNextPage && (
        <Button
          size="sm"
          prominence="internal"
          pending={results.isFetchingNextPage}
          onClick={() => void results.fetchNextPage()}
        >
          {ui("Xem thêm kết quả")}
        </Button>
      )}
      {!waiting && !results.hasNextPage && results.data?.pages.at(-1)?.hasMore && (
        <p className="text-xs text-content-muted">{ui("Có thêm kết quả. Hãy nhập cụ thể hơn.")}</p>
      )}
    </ChatDialog>
  );
}
