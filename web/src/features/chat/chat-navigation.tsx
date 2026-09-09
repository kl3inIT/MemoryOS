import { useInfiniteQuery } from "@tanstack/react-query";
import { Link, useRouterState } from "@tanstack/react-router";
import { ChevronDown, MessageSquare, Plus, Search } from "lucide-react";
import { Popover } from "radix-ui";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { MenuItem } from "@/components/ui/menu-item";
import { SidebarTab } from "@/components/ui/sidebar-tab";
import { listChatSessions } from "@/lib/hey-api/sdk.gen";
import { chatSessionsKey } from "./chat-api";
import { cn } from "@/lib/utils";

export function ChatNavigation({
  collapsed,
  onNavigate,
}: {
  collapsed: boolean;
  onNavigate?: () => void;
}) {
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const sessions = useInfiniteQuery({
    queryKey: chatSessionsKey,
    initialPageParam: 0,
    queryFn: async ({ pageParam, signal }) =>
      (
        await listChatSessions({
          query: { offset: pageParam, limit: 30 },
          signal,
          throwOnError: true,
        })
      ).data,
    getNextPageParam: (last, pages) =>
      last.length === 30 && pages.length * 30 <= 10000 ? pages.length * 30 : undefined,
  });
  return (
    <div className="flex h-full min-h-0 flex-col gap-1">
      <SidebarTab
        to="/"
        icon={<Plus className="size-4" />}
        collapsed={collapsed}
        selected={pathname === "/"}
        onClick={onNavigate}
      >
        New chat
      </SidebarTab>
      <SidebarTab
        to="/search"
        icon={<Search className="size-4" />}
        collapsed={collapsed}
        selected={pathname === "/search"}
        onClick={onNavigate}
      >
        Search
      </SidebarTab>
      {!collapsed && (
        <div className="mt-5 min-h-0 flex-1 overflow-y-auto">
          <p className="px-2.5 pb-2 font-secondary-body text-content-muted">Conversations</p>
          {sessions.data?.pages.flat().map((session) => (
            <Link
              key={session.id}
              to="/chat/$sessionId"
              params={{ sessionId: session.id }}
              onClick={onNavigate}
              aria-current={pathname === `/chat/${session.id}` ? "page" : undefined}
              className={cn(
                "flex items-center gap-2 rounded-lg px-2.5 py-2 font-main-ui-body hover:bg-surface-sunken focus-visible:ring-2 focus-visible:ring-ring",
                pathname === `/chat/${session.id}` && "bg-surface-sunken",
              )}
            >
              <MessageSquare aria-hidden="true" className="size-4 shrink-0 text-content-muted" />
              <span className="truncate">{session.title}</span>
            </Link>
          ))}
          {sessions.isPending && (
            <p role="status" className="px-2.5 font-secondary-body text-content-muted">
              Loading conversations…
            </p>
          )}
          {sessions.isError && (
            <Button size="sm" prominence="internal" onClick={() => void sessions.refetch()}>
              Reload conversations
            </Button>
          )}
          {sessions.hasNextPage && (
            <Button
              size="sm"
              prominence="internal"
              pending={sessions.isFetchingNextPage}
              onClick={() => void sessions.fetchNextPage()}
            >
              Load more
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

export function ChatModeMenu({ mode }: { mode: "Chat" | "Search" }) {
  const [open, setOpen] = useState(false);
  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <Button prominence="internal" aria-label={`${mode}, switch mode`}>
          {mode}
          <ChevronDown className="size-4" />
        </Button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          align="start"
          sideOffset={6}
          className="z-50 w-44 rounded-xl border border-border-subtle bg-surface-base p-1.5 shadow-md"
        >
          <MenuItem to="/" icon={<MessageSquare />} onClick={() => setOpen(false)}>
            Chat
          </MenuItem>
          <MenuItem to="/search" icon={<Search />} onClick={() => setOpen(false)}>
            Search
          </MenuItem>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
