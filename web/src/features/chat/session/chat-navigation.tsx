import { useAppTranslation } from "@/i18n/use-app-translation";
import { useAuiState } from "@assistant-ui/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useMatch, useParams, useRouterState } from "@tanstack/react-router";
import {
  ChevronLeft,
  Compass,
  FileSearch,
  Folder,
  FolderOpen,
  GripVertical,
  Info,
  PinOff,
  Plus,
  SquarePen,
} from "lucide-react";
import { useState, type ReactNode } from "react";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { SortableList } from "@/components/composites/sortable-list";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { SidebarLink } from "@/components/app-shell/sidebar-link";
import { SidebarMenu, SidebarMenuButton, SidebarMenuItem } from "@/components/ui/sidebar";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { ThreadList, groupThreadTitles } from "@/components/assistant-ui/elements/thread-list";
import type { ChatAssistant, ChatSession } from "@/lib/hey-api/types.gen";
import {
  getChatSessionOptions,
  listChatAssistantsQueryKey,
  listChatSessionsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { usePinUpdates } from "@/features/agents/agent-pins";
import { AgentAvatar } from "@/features/agents/agent-avatar";
import { actionErrorText } from "@/lib/action-errors";
import {
  useChatThreads,
  useOptionalChatThreads,
} from "@/features/chat/runtime/chat-threads-context";
import { sessionFromThread } from "@/features/chat/runtime/chat-thread-list-adapter";
import { cn } from "@/lib/utils";
import { ChatHistorySearch } from "./chat-history-search";
import { ChatProjectsSection } from "./chat-projects-section";
import { ChatSessionRow } from "./chat-session-row";
import { useSidebarSectionOpen } from "./sidebar-section-open";
import {
  useAssistantFaces,
  useSidebarAssistants,
  useThreadGroupLabel,
  useTopicTime,
} from "./assistant-topics";

/**
 * A pin's drag handle and unpin button appear on hover. A touch screen cannot hover and the row has no room for them
 * beside its time, so there a pin is removed from the assistants page and the order is kept as it is.
 */
const pinControls = "no-hover:hidden";

/** The topics an assistant's sidebar lists before "Xem tất cả", as LobeHub's topic page size. */
const SIDEBAR_TOPICS = 20;
/** The latest topics across assistants under "Gần đây". */
const RECENT_TOPICS = 8;

/**
 * The application sidebar. Its home lists the tools, Projects, the person's assistants and their recent topics; an
 * open topic of an assistant, or a new one, turns it into that assistant's sidebar, as LobeHub's agent sidebar
 * replaces its home (`features/NavPanel`). Back returns to the home without leaving the page.
 */
export function ChatNavigation(props: NavigationProps) {
  // Isolated shells have no thread list; the conversation is then read from the cache alone.
  return useOptionalChatThreads() ? <ListedNavigation {...props} /> : <Navigation {...props} />;
}

type NavigationProps = {
  collapsed: boolean;
  onNavigate?: () => void;
  /** The Meetings entry, which the app shell supplies because meetings are not Chat's. */
  meetingsTab?: ReactNode;
};

/** Reads the open conversation's assistant from the thread list, which holds every row the sidebar opens. */
function ListedNavigation(props: NavigationProps) {
  const { sessionId } = useParams({ strict: false });
  // A string, so the selection is stable between renders.
  const listed = useAuiState((state) => {
    const item = sessionId
      ? state.threads.threadItems.find((thread) => thread.remoteId === sessionId)
      : undefined;
    const session = item && sessionFromThread(item);
    return session ? (session.projectId ? "" : session.personaId) : undefined;
  });
  return <Navigation {...props} listed={listed} />;
}

function Navigation({
  collapsed,
  onNavigate,
  meetingsTab,
  listed,
}: NavigationProps & {
  /** The open conversation's assistant as the thread list holds it; empty in a Project. */
  listed?: string;
}) {
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const assistantId = useOpenAssistant(listed);
  // Back shows the home on this page only; the next page decides again.
  const [homeOn, setHomeOn] = useState<string>();
  if (!collapsed && assistantId && homeOn !== pathname)
    return (
      <AssistantSidebar
        personaId={assistantId}
        onBack={() => setHomeOn(pathname)}
        onNavigate={onNavigate}
      />
    );
  return (
    <HomeSidebar
      collapsed={collapsed}
      openAssistant={assistantId}
      onNavigate={onNavigate}
      meetingsTab={meetingsTab}
    />
  );
}

/**
 * The assistant whose sidebar the page shows: the one of a new topic, or of the open conversation when it is not in a
 * Project. The conversation is read from what the runtime stored or listed, so this requests nothing itself.
 */
function useOpenAssistant(listed?: string) {
  const newTopic = useMatch({
    from: "/_authenticated/_chat/assistants/$personaId/new",
    shouldThrow: false,
  });
  const { sessionId } = useParams({ strict: false });
  const stored = useQuery({
    ...getChatSessionOptions({ path: { sessionId: sessionId ?? "" } }),
    enabled: false,
  });
  if (newTopic) return newTopic.params.personaId;
  if (!sessionId) return undefined;
  if (stored.data) return stored.data.projectId ? undefined : stored.data.personaId;
  return listed || undefined;
}

function HomeSidebar({
  collapsed,
  openAssistant,
  onNavigate,
  meetingsTab,
}: NavigationProps & { openAssistant?: string }) {
  const ui = useAppTranslation();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const threads = useOptionalChatThreads();
  return (
    <div className="flex h-full min-h-0 flex-col gap-1">
      <SidebarMenu>
        <ChatHistorySearch onNavigate={onNavigate} />
        <SidebarLink
          to="/search"
          label={ui("Search documents")}
          icon={<FileSearch />}
          selected={pathname === "/search"}
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
          <>
            <SidebarLink
              to="/projects"
              label={ui("Dự án")}
              icon={<Folder />}
              selected={pathname.startsWith("/projects")}
              onNavigate={onNavigate}
            />
            <RailAssistants onNavigate={onNavigate} />
          </>
        ) : null}
      </SidebarMenu>
      {collapsed ? null : (
        <div className="min-h-0 flex-1 overflow-y-auto pt-3">
          <ChatProjectsSection onNavigate={onNavigate} />
          <AssistantsSection openAssistant={openAssistant} onNavigate={onNavigate} />
          {threads && <RecentTopics onNavigate={onNavigate} />}
        </div>
      )}
    </div>
  );
}

/**
 * A section of the home sidebar whose heading folds it, remembered per browser as LobeHub's sidebar remembers each
 * section, so a long list never pushes the next section out of reach.
 */
function SidebarSection({
  id,
  title,
  storageKey,
  action,
  className,
  children,
}: {
  id: string;
  title: string;
  storageKey: string;
  action?: ReactNode;
  className?: string;
  children: ReactNode;
}) {
  const [open, setOpen] = useSidebarSectionOpen(storageKey);
  return (
    <Collapsible asChild open={open} onOpenChange={setOpen}>
      <section aria-labelledby={id} className={className}>
        <div className="mb-1 flex h-8 items-center justify-between rounded-lg pr-0.5 pl-2 hover:bg-surface-sunken">
          <h2 id={id} className="min-w-0 flex-1 text-sm font-medium text-content-secondary">
            <CollapsibleTrigger asChild>
              <button
                type="button"
                className="flex h-8 w-full items-center rounded-lg text-left outline-none hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring"
              >
                {title}
              </button>
            </CollapsibleTrigger>
          </h2>
          {action}
        </div>
        <CollapsibleContent>{children}</CollapsibleContent>
      </section>
    </Collapsible>
  );
}

/** Where an assistant's row leads: its latest topic, or a new one. */
function assistantLink(assistant: ChatAssistant) {
  return assistant.latestTopic
    ? ({ to: "/chat/$sessionId", params: { sessionId: assistant.latestTopic.sessionId } } as const)
    : ({ to: "/assistants/$personaId/new", params: { personaId: assistant.id } } as const);
}

/**
 * The person's assistants: MemoryOS first, then the pins in their order. Each row opens its latest topic and shows
 * when it was written, its last line and a turning ring while an answer runs (Rakazo, Hermes Bot Chats). A pin whose
 * assistant is gone stays, marked, until it is unpinned.
 */
function AssistantsSection({
  openAssistant,
  onNavigate,
}: {
  openAssistant?: string;
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const assistants = useSidebarAssistants();
  const updatePins = usePinUpdates();
  const reorder = useMutation({
    mutationFn: ({ change }: { next: ChatAssistant[]; change: (current: string[]) => string[] }) =>
      updatePins(change),
    // Shows the new order at once; the built-in assistant keeps the first row.
    onMutate: ({ next }) =>
      cache.setQueryData<ChatAssistant[]>(listChatAssistantsQueryKey(), (rows) => [
        ...(rows ?? []).filter((row) => row.builtin),
        ...next,
      ]),
    onError: () => cache.invalidateQueries({ queryKey: listChatAssistantsQueryKey() }),
  });
  const rows = assistants.data ?? [];
  const builtin = rows.filter((row) => row.builtin);
  const pinned = rows.filter((row) => !row.builtin);
  const unpin = (assistant: ChatAssistant) =>
    reorder.mutate({
      next: pinned.filter((item) => item.id !== assistant.id),
      change: (current) => current.filter((id) => id !== assistant.id),
    });

  return (
    <SidebarSection
      id="sidebar-assistants"
      title={ui("Trợ lý")}
      storageKey="memoryos:chat-assistants-section"
      className="my-3"
      action={
        <IconButton size="sm" prominence="internal" aria-label={ui("Thêm trợ lý")} asChild>
          <Link to="/agents" onClick={onNavigate}>
            <Plus />
          </Link>
        </IconButton>
      }
    >
      {assistants.isPending && (
        <p role="status" className="px-2 py-1 text-sm text-content-muted">
          {ui("Đang tải trợ lý…")}
        </p>
      )}
      {assistants.isError && (
        <Button size="sm" prominence="internal" onClick={() => void assistants.refetch()}>
          {ui("Tải lại trợ lý")}
        </Button>
      )}
      {/* Rows, not list items: the drag context puts its announcements beside them. */}
      <div className="flex flex-col gap-0.5">
        {builtin.map((assistant) => (
          <div key={assistant.id} className="group relative rounded-lg">
            <AssistantRow
              assistant={assistant}
              selected={openAssistant === assistant.id}
              onNavigate={onNavigate}
            />
          </div>
        ))}
        <SortableList
          items={pinned}
          getId={(assistant) => assistant.id}
          onReorder={(next) =>
            reorder.mutate({ next, change: () => next.map((assistant) => assistant.id) })
          }
        >
          {(assistant, handle) => (
            <div
              ref={handle.setNodeRef}
              style={handle.style}
              className={cn(
                "group relative rounded-lg",
                handle.dragging && "z-10 bg-surface-sunken",
              )}
            >
              <button
                ref={handle.setHandleRef}
                type="button"
                aria-label={ui("Kéo để sắp xếp {{v1}}", { v1: assistant.name })}
                className={cn(
                  "absolute top-1/2 -left-1 grid h-8 w-4 -translate-y-1/2 cursor-grab place-items-center text-content-muted outline-none focus-visible:opacity-100 active:cursor-grabbing",
                  hoverReveal,
                  pinControls,
                )}
                {...handle.attributes}
              >
                <GripVertical aria-hidden="true" className="size-3.5" />
              </button>
              <AssistantRow
                assistant={assistant}
                selected={openAssistant === assistant.id}
                onNavigate={onNavigate}
              />
              <span
                className={cn(
                  "absolute top-1/2 right-0.5 -translate-y-1/2",
                  hoverReveal,
                  pinControls,
                )}
              >
                <IconButton
                  size="sm"
                  prominence="internal"
                  aria-label={ui("Bỏ ghim {{v1}}", { v1: assistant.name })}
                  onClick={() => unpin(assistant)}
                >
                  <PinOff />
                </IconButton>
              </span>
            </div>
          )}
        </SortableList>
      </div>
      <Link
        to="/agents"
        onClick={onNavigate}
        className="mt-0.5 flex h-8 items-center gap-2 rounded-lg px-2 text-sm text-content-muted outline-none hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring"
      >
        <Compass aria-hidden="true" className="size-4" />
        {ui("Khám phá trợ lý")}
      </Link>
      {reorder.error && (
        <p role="alert" className="px-2 text-xs text-status-danger-content">
          {actionErrorText(reorder.error)}
        </p>
      )}
    </SidebarSection>
  );
}

/** One assistant: its face, name and last time above the last line of its latest topic. */
function AssistantRow({
  assistant,
  selected,
  onNavigate,
}: {
  assistant: ChatAssistant;
  selected: boolean;
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();
  const topicTime = useTopicTime();
  const topic = assistant.latestTopic;
  const running = topic?.replyRunning === true;
  const line = !assistant.usable
    ? ui("Không còn dùng được")
    : running
      ? ui("Đang trả lời…")
      : topic?.preview || (topic ? topic.title : ui("Chưa có chủ đề"));
  const content = (
    <>
      <span className="relative shrink-0">
        <AgentAvatar
          agent={assistant}
          size="row"
          className={cn(!assistant.usable && "opacity-60 grayscale")}
        />
        {running && (
          <span
            aria-hidden="true"
            // A square ring breathes rather than spins: a turning square swings its corners out.
            className="absolute -inset-1 animate-pulse rounded-xl border-2 border-status-info-content motion-reduce:animate-none"
          />
        )}
      </span>
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="flex min-w-0 items-baseline gap-2">
          <span className="min-w-0 flex-1 truncate font-medium text-content-primary">
            {assistant.name}
          </span>
          {topic && (
            <time
              dateTime={topic.lastMessageAt}
              className="shrink-0 text-xs text-content-muted tabular-nums group-focus-within:invisible group-hover:invisible no-hover:visible"
            >
              {topicTime(topic.lastMessageAt)}
            </time>
          )}
        </span>
        <span
          className={cn(
            "truncate text-xs",
            running ? "font-medium text-status-info-content" : "text-content-muted",
          )}
        >
          {line}
        </span>
      </span>
    </>
  );
  const className = cn(
    "flex min-w-0 items-center gap-2.5 rounded-lg px-2 py-1 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring",
    selected ? "bg-surface-sunken" : "hover:bg-surface-subtle",
  );
  // A gone assistant without a topic has nothing to open.
  if (!assistant.usable && !topic) return <div className={cn(className, "pr-8")}>{content}</div>;
  return (
    <Link
      {...assistantLink(assistant)}
      onClick={onNavigate}
      aria-current={selected ? "true" : undefined}
      className={className}
    >
      {content}
    </Link>
  );
}

/** The assistants as faces on the folded rail; each opens its latest topic. */
function RailAssistants({ onNavigate }: { onNavigate?: () => void }) {
  const assistants = useSidebarAssistants();
  return (assistants.data ?? []).map((assistant) => (
    <SidebarMenuItem key={assistant.id}>
      <SidebarMenuButton asChild tooltip={assistant.name}>
        <Link {...assistantLink(assistant)} onClick={onNavigate}>
          <AgentAvatar agent={assistant} size="sm" className="-m-1" />
          <span>{assistant.name}</span>
        </Link>
      </SidebarMenuButton>
    </SidebarMenuItem>
  ));
}

/**
 * The latest topics across assistants, each with its assistant's face (ChatGPT's recent chats); the history page
 * holds the rest. Rows come from the assistant-ui thread list, which the open conversation shares.
 */
function RecentTopics({ onNavigate }: { onNavigate?: () => void }) {
  const ui = useAppTranslation();
  const isLoading = useAuiState((state) => state.threads.isLoading);
  const threadIds = useAuiState((state) => state.threads.threadIds);
  const items = useAuiState((state) => state.threads.threadItems);
  const faceOf = useAssistantFaces();
  const topicTime = useTopicTime();
  const byId = new Map(items.map((item) => [item.id, item]));
  const recent = threadIds.flatMap((threadId) => {
    const item = byId.get(threadId);
    const session = item && sessionFromThread(item);
    return session ? [{ ...session, threadId }] : [];
  });
  const shown = recent
    .toSorted((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
    .slice(0, RECENT_TOPICS);
  return (
    <SidebarSection
      id="sidebar-recent"
      title={ui("Gần đây")}
      storageKey="memoryos:chat-recent-section"
    >
      <ThreadList label={ui("Gần đây")}>
        {shown.map((session) => (
          <ChatThreadRow
            key={session.threadId}
            threadId={session.threadId}
            session={session}
            onNavigate={onNavigate}
            leading={<AgentAvatar agent={faceOf(session.personaId)} size="sm" />}
            trailing={
              <time
                dateTime={session.updatedAt}
                className="shrink-0 text-xs text-content-muted tabular-nums"
              >
                {topicTime(session.updatedAt)}
              </time>
            }
          />
        ))}
      </ThreadList>
      {!isLoading && shown.length === 0 && (
        <p role="status" className="px-2 py-2 text-sm text-content-muted">
          {ui("Chưa có chủ đề.")}
        </p>
      )}
      {isLoading && shown.length === 0 && (
        <p role="status" className="px-2 text-sm text-content-muted">
          {ui("Đang tải chủ đề…")}
        </p>
      )}
      {shown.length > 0 && (
        <Link
          to="/history"
          onClick={onNavigate}
          className="mt-0.5 flex h-8 items-center rounded-lg px-2 text-sm text-content-muted outline-none hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring"
        >
          {ui("Xem tất cả")}
        </Link>
      )}
    </SidebarSection>
  );
}

function ChatThreadRow({
  threadId,
  session,
  onNavigate,
  leading,
  trailing,
}: {
  threadId: string;
  session: ChatSession;
  onNavigate?: () => void;
  leading?: ReactNode;
  trailing?: ReactNode;
}) {
  const { runtime } = useChatThreads();
  const item = () => runtime.threads.getItemById(threadId);
  return (
    <ChatSessionRow
      session={session}
      onNavigate={onNavigate}
      leading={leading}
      trailing={trailing}
      rename={(title) => item().rename(title)}
      deleteSession={() => item().delete()}
      archiveSession={(archived) => (archived ? item().archive() : item().unarchive())}
    />
  );
}

/**
 * An assistant's sidebar, after LobeHub's `AgentSidebar`: back to the home, the assistant, "Chủ đề mới", search within
 * its topics and its details, then its topics by time; twenty of them, and the history page for the rest.
 */
function AssistantSidebar({
  personaId,
  onBack,
  onNavigate,
}: {
  personaId: string;
  onBack: () => void;
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const assistants = useSidebarAssistants();
  const faceOf = useAssistantFaces();
  const groupLabel = useThreadGroupLabel();
  const listed = assistants.data?.find((assistant) => assistant.id === personaId);
  const face = faceOf(personaId);
  const name = listed?.name ?? face.name;
  const usable = listed?.usable ?? true;
  const owner = listed ? (listed.builtin ? ui("MemoryOS") : listed.ownerName) : null;
  const topics = useQuery({
    ...listChatSessionsOptions({
      query: { personaId, archived: false, limit: SIDEBAR_TOPICS + 1 },
    }),
    // A running answer is followed until it ends; nothing is polled otherwise.
    refetchInterval: (query) =>
      query.state.data?.some((session) => session.replyRunning) ? 4000 : false,
  });
  const shown = (topics.data ?? []).slice(0, SIDEBAR_TOPICS);
  const newTopicPath = `/assistants/${personaId}/new`;
  return (
    <div className="flex h-full min-h-0 flex-col gap-1">
      <div className="flex min-w-0 items-center gap-2 px-1 pb-2">
        <IconButton
          size="sm"
          prominence="internal"
          aria-label={ui("Về danh sách trợ lý")}
          onClick={onBack}
        >
          <ChevronLeft />
        </IconButton>
        <AgentAvatar agent={listed ?? face} size="row" />
        <div className="flex min-w-0 flex-1 flex-col">
          <span className="truncate text-sm font-medium text-content-primary">{name}</span>
          {(owner || !usable) && (
            <span className="truncate text-xs text-content-muted">
              {usable ? owner : ui("Không còn dùng được")}
            </span>
          )}
        </div>
      </div>
      <SidebarMenu>
        {usable && (
          <SidebarLink
            to="/assistants/$personaId/new"
            params={{ personaId }}
            label={ui("Chủ đề mới")}
            icon={<SquarePen />}
            selected={pathname === newTopicPath}
            onNavigate={onNavigate}
          />
        )}
        <ChatHistorySearch assistant={{ id: personaId, name }} onNavigate={onNavigate} />
        {usable && (
          <SidebarMenuItem>
            <SidebarMenuButton asChild tooltip={ui("Thông tin trợ lý")}>
              <Link to="/agents" search={{ agent: personaId }} onClick={onNavigate}>
                <Info />
                <span>{ui("Thông tin trợ lý")}</span>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        )}
      </SidebarMenu>
      <div className="min-h-0 flex-1 overflow-y-auto pt-2">
        <ThreadList label={ui("Chủ đề của {{v1}}", { v1: name })}>
          {groupThreadTitles(shown).map((group) => (
            <section key={group.key} aria-label={groupLabel(group)}>
              <h3 className="px-3 pt-3 pb-1 text-xs font-medium text-content-muted">
                {groupLabel(group)}
              </h3>
              {group.items.map((session) => (
                <ChatSessionRow key={session.id} session={session} onNavigate={onNavigate} />
              ))}
            </section>
          ))}
        </ThreadList>
        {topics.isPending && (
          <p role="status" className="px-3 py-2 text-sm text-content-muted">
            {ui("Đang tải chủ đề…")}
          </p>
        )}
        {topics.isError && (
          <Button size="sm" prominence="internal" onClick={() => void topics.refetch()}>
            {ui("Tải lại chủ đề")}
          </Button>
        )}
        {topics.isSuccess && shown.length === 0 && pathname !== newTopicPath && (
          <p role="status" className="px-3 py-2 text-sm text-content-muted">
            {ui("Chưa có chủ đề.")}
          </p>
        )}
        {(topics.data?.length ?? 0) > SIDEBAR_TOPICS && (
          <Link
            to="/history"
            search={{ assistant: personaId }}
            onClick={onNavigate}
            className="mt-1 flex h-8 items-center rounded-lg px-3 text-sm text-content-muted outline-none hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring"
          >
            {ui("Xem tất cả")}
          </Link>
        )}
      </div>
    </div>
  );
}
