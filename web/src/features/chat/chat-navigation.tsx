import { useAppTranslation } from "@/i18n/use-app-translation";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useRouterState } from "@tanstack/react-router";
import { Bot, Folder, ChevronDown, ChevronRight, MessageSquare, Plus, Search } from "lucide-react";
import { Popover } from "radix-ui";
import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { MenuItem } from "@/components/ui/menu-item";
import { SidebarTab } from "@/components/ui/sidebar-tab";
import { ThreadList } from "@/components/assistant-ui/elements/thread-list";
import { listChatSessions } from "@/lib/hey-api/sdk.gen";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { chatSessionsKey } from "./chat-api";
import { ChatSessionRow, CHAT_DRAG_TYPE } from "./chat-session-row";
import { ProjectEditor, ProjectConversationList } from "./chat-projects-page";
import { loadProjects, moveConversation, type Project } from "./chat-workspace-api";
import { chatActionError } from "./chat-action-utils";
import { cn } from "@/lib/utils";

export function ChatNavigation({
  collapsed,
  onNavigate,
}: {
  collapsed: boolean;
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();

  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const { actorId, authorizationVersion } = useApplicationSession();
  const [creating, setCreating] = useState(false);
  const projects = useQuery({
    queryKey: ["chat-projects", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadProjects(signal),
  });
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
        {ui("Hội thoại mới")}
      </SidebarTab>
      <SidebarTab
        to="/search"
        icon={<Search className="size-4" />}
        collapsed={collapsed}
        selected={pathname === "/search"}
        onClick={onNavigate}
      >
        {ui("Search")}
      </SidebarTab>
      <SidebarTab
        to="/assistants"
        icon={<Bot className="size-4" />}
        collapsed={collapsed}
        selected={pathname === "/assistants"}
        onClick={onNavigate}
      >
        {ui("Trợ lý")}
      </SidebarTab>
      {collapsed ? (
        <SidebarTab
          to="/projects"
          icon={<Folder className="size-4" />}
          collapsed
          selected={pathname.startsWith("/projects")}
          onClick={onNavigate}
        >
          {ui("Dự án")}
        </SidebarTab>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto pt-4">
          <div className="mb-2 flex items-center justify-between px-2">
            <Link
              to="/projects"
              onClick={onNavigate}
              className="rounded text-sm font-medium text-content-secondary hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring"
            >
              {ui("Dự án")}
            </Link>
            <IconButton
              size="sm"
              prominence="internal"
              aria-label={ui("Tạo dự án")}
              onClick={() => setCreating(true)}
            >
              <Plus />
            </IconButton>
          </div>
          {projects.data?.map((project) => (
            <ProjectFolder key={project.id} project={project} onNavigate={onNavigate} />
          ))}
          {projects.isPending && (
            <p role="status" className="px-3 text-sm text-content-muted">
              {ui("Đang tải dự án…")}
            </p>
          )}
          {projects.isError && (
            <Button size="sm" prominence="internal" onClick={() => void projects.refetch()}>
              {ui("Tải lại dự án")}
            </Button>
          )}
          {projects.data?.length === 0 && (
            <Button
              size="sm"
              prominence="internal"
              className="w-full justify-start"
              onClick={() => setCreating(true)}
            >
              <Folder className="size-4" />
              {ui("Tạo dự án mới")}
            </Button>
          )}
          <h2 className="px-2 pb-2 pt-6 text-sm font-medium text-content-secondary">
            {ui("Hội thoại gần đây")}
          </h2>
          <ThreadList label={ui("Hội thoại gần đây")}>
            {sessions.data?.pages.flat().map((session) => (
              <ChatSessionRow key={session.id} session={session} onNavigate={onNavigate} />
            ))}
          </ThreadList>
          {sessions.isPending && (
            <p role="status" className="px-3 text-sm text-content-muted">
              {ui("Đang tải hội thoại…")}
            </p>
          )}
          {sessions.isError && (
            <Button size="sm" prominence="internal" onClick={() => void sessions.refetch()}>
              {ui("Tải lại hội thoại")}
            </Button>
          )}
          {sessions.hasNextPage && (
            <Button
              size="sm"
              prominence="internal"
              pending={sessions.isFetchingNextPage}
              onClick={() => void sessions.fetchNextPage()}
            >
              {ui("Xem thêm hội thoại")}
            </Button>
          )}
        </div>
      )}
      {creating && (
        <ProjectEditor
          onClose={() => {
            setCreating(false);
            onNavigate?.();
          }}
        />
      )}
    </div>
  );
}

function ProjectFolder({ project, onNavigate }: { project: Project; onNavigate?: () => void }) {
  const ui = useAppTranslation();

  const [expanded, setExpanded] = useState(false);
  const [over, setOver] = useState(false);
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  const [error, setError] = useState<string>();
  const cache = useQueryClient();
  const selected = useRouterState({
    select: (state) => state.location.pathname === `/projects/${project.id}`,
  });
  return (
    <div
      data-project-id={project.id}
      onDragOver={(event) => {
        if (!busy.current && event.dataTransfer.types.includes(CHAT_DRAG_TYPE)) {
          event.preventDefault();
          event.dataTransfer.dropEffect = "move";
          setOver(true);
        }
      }}
      onDragLeave={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOver(false);
      }}
      onDrop={(event) => {
        event.preventDefault();
        setOver(false);
        const sessionId = event.dataTransfer.getData(CHAT_DRAG_TYPE);
        if (!sessionId || busy.current) return;
        busy.current = true;
        setPending(true);
        setError(undefined);
        void moveConversation(sessionId, project.id)
          .then(async () => {
            await Promise.all([
              cache.invalidateQueries({ queryKey: chatSessionsKey }),
              cache.invalidateQueries({ queryKey: ["chat-project-sessions"] }),
              cache.invalidateQueries({ queryKey: ["chat-session", sessionId] }),
            ]);
            setExpanded(true);
          })
          .catch((cause: unknown) => setError(chatActionError(cause)))
          .finally(() => {
            busy.current = false;
            setPending(false);
          });
      }}
    >
      <div
        className={cn(
          "flex items-center rounded-lg hover:bg-surface-sunken",
          (selected || over) && "bg-surface-sunken",
          over && "ring-2 ring-ring",
        )}
      >
        <IconButton
          size="sm"
          prominence="internal"
          aria-label={ui("{{v1}} dự án {{v2}}", {
            v1: expanded ? ui("Thu gọn") : ui("Mở rộng"),
            v2: project.name,
          })}
          aria-expanded={expanded}
          onClick={() => setExpanded(!expanded)}
        >
          {expanded ? <ChevronDown /> : <ChevronRight />}
        </IconButton>
        <Link
          to="/projects/$projectId"
          params={{ projectId: project.id }}
          onClick={onNavigate}
          aria-current={selected ? "page" : undefined}
          className="flex min-w-0 flex-1 items-center gap-2 rounded-lg py-2 pr-2 text-sm focus-visible:ring-2 focus-visible:ring-ring"
        >
          <Folder className="size-4 shrink-0 text-content-muted" />
          <span className="truncate">{project.name}</span>
        </Link>
      </div>
      {pending && (
        <p role="status" className="px-3 text-xs">
          {ui("Đang chuyển hội thoại…")}
        </p>
      )}
      {error && (
        <p role="alert" className="px-3 text-xs">
          {ui(error)}
        </p>
      )}
      {expanded && (
        <ProjectConversationList projectId={project.id} compact onNavigate={onNavigate} />
      )}
    </div>
  );
}

export function ChatModeMenu({ mode }: { mode: "Chat" | "Search" }) {
  const ui = useAppTranslation();

  const [open, setOpen] = useState(false);
  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <Button prominence="internal" aria-label={ui("{{v1}}, switch mode", { v1: ui(mode) })}>
          {ui(mode)}
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
            {ui("Chat")}
          </MenuItem>
          <MenuItem to="/search" icon={<Search />} onClick={() => setOpen(false)}>
            {ui("Search")}
          </MenuItem>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
