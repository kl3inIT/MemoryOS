import { useAppTranslation } from "@/i18n/use-app-translation";
import { useAuiState } from "@assistant-ui/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import {
  Bot,
  Compass,
  Folder,
  FileSearch,
  FolderOpen,
  GripVertical,
  PinOff,
  Plus,
} from "lucide-react";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { SortableList } from "@/components/composites/sortable-list";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { SidebarLink } from "@/components/app-shell/sidebar-link";
import { SidebarMenu } from "@/components/ui/sidebar";
import { ThreadList, groupThreadTitles } from "@/components/assistant-ui/elements/thread-list";
import type { ChatSession, PersonaView } from "@/lib/hey-api/types.gen";
import { ChatHistorySearch } from "./chat-history-search";
import { newChatSession } from "@/features/chat/chat-api";
import {
  listChatPersonaPinsOptions,
  listChatPersonaPinsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { usePinUpdates } from "@/features/agents/agent-pins";
import { AgentAvatar } from "@/features/agents/agent-avatar";
import { ChatProjectsSection } from "./chat-projects-section";
import { ChatSessionRow } from "./chat-session-row";
import { personaOf, type Persona } from "@/features/chat/chat-personas-api";
import { actionErrorText } from "@/lib/action-errors";
import {
  useChatThreads,
  useOptionalChatThreads,
} from "@/features/chat/runtime/chat-threads-context";
import { sessionFromThread } from "@/features/chat/runtime/chat-thread-list-adapter";
import { cn } from "@/lib/utils";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";

export function ChatNavigation({
  collapsed,
  onNavigate,
  meetingsTab,
}: {
  collapsed: boolean;
  onNavigate?: () => void;
  /** The Meetings entry, which the app shell supplies because meetings are not Chat's. */
  meetingsTab?: ReactNode;
}) {
  const ui = useAppTranslation();

  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const threads = useOptionalChatThreads();
  return (
    <div className="flex h-full min-h-0 flex-col gap-1">
      <SidebarMenu>
        <SidebarLink
          to="/"
          label={ui("Hội thoại mới")}
          icon={<Plus />}
          selected={pathname === "/"}
          onNavigate={onNavigate}
        />
        {/* Expanded sidebars show conversation search as an icon beside the collapse button. */}
        {collapsed ? <ChatHistorySearch variant="tab" onNavigate={onNavigate} /> : null}
        <SidebarLink
          to="/search"
          label={ui("Search documents")}
          icon={<FileSearch />}
          selected={pathname === "/search"}
          onNavigate={onNavigate}
        />
        <SidebarLink
          to="/agents"
          label={ui("Trợ lý")}
          icon={<Bot />}
          selected={pathname === "/agents"}
          onNavigate={onNavigate}
        />
        {meetingsTab}
        <SidebarLink
          to="/library"
          label={ui("Thư viện")}
          icon={<FolderOpen />}
          selected={pathname === "/library"}
          onNavigate={onNavigate}
        />
        {collapsed ? (
          <SidebarLink
            to="/projects"
            label={ui("Dự án")}
            icon={<Folder />}
            selected={pathname.startsWith("/projects")}
            onNavigate={onNavigate}
          />
        ) : null}
      </SidebarMenu>
      {collapsed ? null : (
        <div className="min-h-0 flex-1 overflow-y-auto pt-4">
          <PinnedAgents onNavigate={onNavigate} />
          <ChatProjectsSection onNavigate={onNavigate} />
          <h2 className="px-2 pt-6 pb-2 text-sm font-medium text-content-secondary">
            {ui("Hội thoại gần đây")}
          </h2>
          {threads && <ThreadListConversations onNavigate={onNavigate} />}
        </div>
      )}
    </div>
  );
}

/** Pinned agents start a new conversation with that agent; drag to reorder, unpin on hover (Onyx sidebar pins). */
function PinnedAgents({ onNavigate }: { onNavigate?: () => void }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const refreshSessions = useRefreshChatSessions();
  const navigate = useNavigate();
  const updatePins = usePinUpdates();
  const pins = useQuery({
    ...listChatPersonaPinsOptions(),
    select: (views) => views.map(personaOf),
  });
  const reorder = useMutation({
    mutationFn: ({ change }: { next: Persona[]; change: (current: string[]) => string[] }) =>
      updatePins(change),
    // Shows the new order at once; the cache keeps the views as the API sent them.
    onMutate: ({ next }) =>
      cache.setQueryData<PersonaView[]>(listChatPersonaPinsQueryKey(), (views) =>
        next.flatMap((agent) => views?.filter((view) => view.id === agent.id) ?? []),
      ),
    onError: () => cache.invalidateQueries({ queryKey: listChatPersonaPinsQueryKey() }),
  });
  const start = useMutation({
    mutationFn: (agent: Persona) =>
      newChatSession(agent.name, AbortSignal.timeout(30000), agent.id),
    onSuccess: async (session) => {
      await refreshSessions();
      await navigate({ to: "/chat/$sessionId", params: { sessionId: session.id } });
      onNavigate?.();
    },
  });
  if (!pins.data?.length) return null;
  const error = start.error ?? reorder.error;

  return (
    <section aria-labelledby="pinned-agents" className="mb-4">
      <h2 id="pinned-agents" className="px-2 pb-1 text-sm font-medium text-content-secondary">
        {ui("Trợ lý đã ghim")}
      </h2>
      <ul>
        <SortableList
          items={pins.data}
          getId={(agent) => agent.id}
          onReorder={(next) =>
            reorder.mutate({ next, change: () => next.map((agent) => agent.id) })
          }
        >
          {(agent, handle) => (
            <li
              ref={handle.setNodeRef}
              style={handle.style}
              className={cn(
                "group relative flex items-center rounded-lg hover:bg-surface-subtle",
                handle.dragging && "z-10 bg-surface-raised shadow-hover",
              )}
            >
              <button
                ref={handle.setHandleRef}
                type="button"
                aria-label={ui("Kéo để sắp xếp {{v1}}", { v1: agent.name })}
                className={cn(
                  "grid h-8 w-4 shrink-0 cursor-grab place-items-center text-content-muted outline-none focus-visible:opacity-100 active:cursor-grabbing",
                  hoverReveal,
                )}
                {...handle.attributes}
              >
                <GripVertical aria-hidden="true" className="size-3.5" />
              </button>
              <button
                type="button"
                disabled={start.isPending}
                className="flex min-w-0 flex-1 items-center gap-2 py-1.5 pr-8 text-left text-sm disabled:opacity-60"
                onClick={() => start.mutate(agent)}
              >
                <AgentAvatar agent={agent} size="sm" />
                <span className="min-w-0 flex-1 truncate">{agent.name}</span>
              </button>
              <span className={cn("absolute right-0.5", hoverReveal)}>
                <IconButton
                  size="sm"
                  prominence="internal"
                  aria-label={ui("Bỏ ghim {{v1}}", { v1: agent.name })}
                  onClick={() =>
                    reorder.mutate({
                      next: pins.data.filter((item) => item.id !== agent.id),
                      change: (current) => current.filter((id) => id !== agent.id),
                    })
                  }
                >
                  <PinOff />
                </IconButton>
              </span>
            </li>
          )}
        </SortableList>
      </ul>
      <Link
        to="/agents"
        onClick={onNavigate}
        className="mt-0.5 flex h-8 items-center gap-2 rounded-lg px-2 pl-6 text-sm text-content-muted outline-none hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring"
      >
        <Compass aria-hidden="true" className="size-4" />
        {ui("Khám phá trợ lý")}
      </Link>
      {error && (
        <p role="alert" className="px-2 text-xs text-status-danger-content">
          {actionErrorText(error)}
        </p>
      )}
    </section>
  );
}

/** Sidebar rows come from the assistant-ui remote thread list; day sections are kept from the old list. */
function ThreadListConversations({ onNavigate }: { onNavigate?: () => void }) {
  const ui = useAppTranslation();
  const { runtime } = useChatThreads();
  const isLoading = useAuiState((state) => state.threads.isLoading);
  const isLoadingMore = useAuiState((state) => state.threads.isLoadingMore);
  const hasMore = useAuiState((state) => state.threads.hasMore);
  const threadIds = useAuiState((state) => state.threads.threadIds);
  const items = useAuiState((state) => state.threads.threadItems);
  const byId = new Map(items.map((item) => [item.id, item]));
  const rows = (ids: readonly string[]) =>
    ids.flatMap((threadId) => {
      const item = byId.get(threadId);
      const session = item && sessionFromThread(item);
      return session ? [{ ...session, threadId }] : [];
    });
  const regular = rows(threadIds);
  const groups = groupThreadTitles(regular);
  const groupLabels = { today: ui("Hôm nay"), yesterday: ui("Hôm qua"), earlier: ui("Trước đó") };
  return (
    <>
      <ThreadList label={ui("Hội thoại gần đây")}>
        {groups.map((group) => (
          <section key={group.label} aria-label={groupLabels[group.label]}>
            <h3 className="px-3 pt-3 pb-1 text-xs font-medium text-content-muted">
              {groupLabels[group.label]}
            </h3>
            {group.items.map((session) => (
              <ChatThreadRow
                key={session.threadId}
                threadId={session.threadId}
                session={session}
                onNavigate={onNavigate}
              />
            ))}
          </section>
        ))}
      </ThreadList>
      {!isLoading && regular.length === 0 && (
        <p role="status" className="px-3 py-2 text-sm text-content-muted">
          {ui("Chưa có hội thoại.")}
        </p>
      )}
      {isLoading && regular.length === 0 && (
        <p role="status" className="px-3 text-sm text-content-muted">
          {ui("Đang tải hội thoại…")}
        </p>
      )}
      {hasMore && (
        <Button
          size="sm"
          prominence="internal"
          pending={isLoadingMore}
          onClick={() => void runtime.threads.loadMore()}
        >
          {ui("Xem thêm hội thoại")}
        </Button>
      )}
    </>
  );
}

function ChatThreadRow({
  threadId,
  session,
  onNavigate,
}: {
  threadId: string;
  session: ChatSession;
  onNavigate?: () => void;
}) {
  const { runtime } = useChatThreads();
  const item = () => runtime.threads.getItemById(threadId);
  return (
    <ChatSessionRow
      session={session}
      onNavigate={onNavigate}
      rename={(title) => item().rename(title)}
      deleteSession={() => item().delete()}
      archiveSession={(archived) => (archived ? item().archive() : item().unarchive())}
    />
  );
}
