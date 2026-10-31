import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  infiniteQueryOptions,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { Link, useNavigate, useParams, useRouterState } from "@tanstack/react-router";
import {
  FolderOpen,
  GripVertical,
  MoreHorizontal,
  Pencil,
  Plus,
  RotateCcw,
  Trash2,
} from "lucide-react";
import { memo, useCallback, useRef, useState } from "react";
import { ThreadList } from "@/components/assistant-ui/elements/thread-list";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { SortableList, type SortableDrag } from "@/components/composites/sortable-list";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { Skeleton } from "@/components/ui/skeleton";
import { projectsOptions, type Project } from "@/features/chat/projects/chat-projects-api";
import { ProjectDeleteDialog } from "@/features/chat/projects/project-delete-dialog";
import { ProjectEditor } from "@/features/chat/projects/project-editor";
import { ProjectIcon } from "@/features/chat/projects/project-icon";
import { useReorderProjects } from "@/features/chat/projects/use-reorder-projects";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";
import { actionErrorText } from "@/lib/action-errors";
import {
  getChatSessionOptions,
  listProjectChatSessionsInfiniteOptions,
  moveChatProjectMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { cn } from "@/lib/utils";
import { CHAT_DRAG_TYPE, ChatSessionRow } from "./chat-session-row";
import { useSidebarSectionOpen } from "./sidebar-preferences";

/** The sidebar lists this many projects, and this many conversations of an open project. */
const SECTION_PROJECTS = 3;
const PROJECT_SESSIONS = 5;
const SECTION_STORAGE_KEY = "memoryos:chat-projects-section";

const skeletonRows = [0, 1, 2];
// An open project may hold no conversation at all, so it reserves one row and grows when rows arrive.
const projectSkeletonRows = [0];

/** The first conversations of a project, read when it opens and prefetched when its row is pointed at. */
function projectSessionsOptions(projectId: string) {
  return infiniteQueryOptions({
    ...listProjectChatSessionsInfiniteOptions({
      path: { projectId },
      query: { limit: PROJECT_SESSIONS },
    }),
    initialPageParam: 0,
    getNextPageParam: (last, pages) =>
      last.length === PROJECT_SESSIONS && pages.length * PROJECT_SESSIONS <= 10000
        ? pages.length * PROJECT_SESSIONS
        : undefined,
  });
}

/** Placeholder rows while a list loads; assistive technology hears the label once instead of each bar. */
function LoadingRows({ label, rows = skeletonRows }: { label: string; rows?: number[] }) {
  return (
    <div role="status" className="flex flex-col gap-1 px-2 py-1">
      <span className="sr-only">{label}</span>
      {rows.map((row) => (
        <Skeleton key={row} aria-hidden="true" className="h-7" />
      ))}
    </div>
  );
}

/**
 * The project being viewed: the Projects page of that project, or the project of the open conversation.
 * Both reach the list even when the project sits beyond its first rows.
 */
function useViewedProject() {
  const { sessionId, projectId } = useParams({ strict: false });
  // Reads the conversation the runtime stored when it created or loaded it. It requests nothing itself: a request
  // here would add a read to every promotion and every invalidation of the open conversation.
  const session = useQuery({
    ...getChatSessionOptions({ path: { sessionId: sessionId ?? "" } }),
    enabled: false,
  });
  return {
    sessionId,
    projectId: sessionId ? session.data?.projectId : projectId,
  };
}

/** Projects of the chat sidebar: they open and close, and each one opens to its recent conversations. */
export function ChatProjectsSection({ onNavigate }: { onNavigate?: () => void }) {
  const ui = useAppTranslation();
  const projects = useQuery(projectsOptions());
  const [creating, setCreating] = useState(false);
  const [open, setOpen] = useSidebarSectionOpen(SECTION_STORAGE_KEY);
  const [openProjects, setOpenProjects] = useState<ReadonlySet<string>>(new Set());
  const viewed = useViewedProject();
  const reorderProjects = useReorderProjects();

  // The project of the open conversation opens once per conversation and project, so a person can close it again.
  const [revealed, setRevealed] = useState<string>();
  const reveal = viewed.sessionId && viewed.projectId && `${viewed.sessionId}:${viewed.projectId}`;
  if (reveal && reveal !== revealed && viewed.projectId) {
    const projectId = viewed.projectId;
    setRevealed(reveal);
    setOpenProjects((current) => new Set(current).add(projectId));
  }

  const toggleProject = useCallback((projectId: string, next: boolean) => {
    setOpenProjects((current) => {
      const projectsOpen = new Set(current);
      if (next) projectsOpen.add(projectId);
      else projectsOpen.delete(projectId);
      return projectsOpen;
    });
  }, []);

  const all = projects.data ?? [];
  const shown = all.slice(0, SECTION_PROJECTS);
  const viewedProject = all
    .slice(SECTION_PROJECTS)
    .find((project) => project.id === viewed.projectId);
  const listed = viewedProject ? [...shown, viewedProject] : shown;

  return (
    <Collapsible className="group/project-section" open={open} onOpenChange={setOpen}>
      <div className="mb-1 flex items-center justify-between rounded-lg pr-0.5 pl-2 hover:bg-surface-sunken">
        <CollapsibleTrigger asChild>
          <button
            type="button"
            className="flex h-8 min-w-0 flex-1 items-center rounded-lg text-left text-sm font-medium text-content-secondary outline-none hover:text-content-primary"
          >
            {ui("Dự án")}
          </button>
        </CollapsibleTrigger>
        <IconButton
          size="sm"
          prominence="internal"
          aria-label={ui("Tạo dự án")}
          onClick={() => setCreating(true)}
        >
          <Plus />
        </IconButton>
      </div>
      <CollapsibleContent>
        <SortableList items={listed} getId={(project) => project.id} onReorder={reorderProjects}>
          {(project, handle) => (
            <div
              ref={handle.setNodeRef}
              style={handle.style}
              data-project-id={project.id}
              className={cn("relative rounded-lg", handle.dragging && "z-10 bg-surface-sunken")}
            >
              <ProjectFolder
                project={project}
                open={openProjects.has(project.id)}
                onOpenChange={toggleProject}
                onNavigate={onNavigate}
                drag={handle.drag}
              />
            </div>
          )}
        </SortableList>
        {/* As ChatGPT's "See more", the link appears once some project is left out. */}
        {all.length > SECTION_PROJECTS && (
          <Link
            to="/projects"
            onClick={onNavigate}
            className="flex h-8 items-center rounded-lg px-2 text-sm text-content-muted outline-none hover:bg-surface-subtle hover:text-content-primary"
          >
            {ui("Xem tất cả dự án")}
          </Link>
        )}
        {projects.isPending && <LoadingRows label={ui("Đang tải dự án…")} />}
        {projects.isError && (
          <Button size="sm" prominence="internal" onClick={() => void projects.refetch()}>
            {ui("Tải lại dự án")}
          </Button>
        )}
      </CollapsibleContent>
      {creating && (
        <ProjectEditor
          onClose={() => {
            setCreating(false);
            onNavigate?.();
          }}
        />
      )}
    </Collapsible>
  );
}

/** Memoized: dragging another project re-renders the list, and a folder that did not change keeps its output. */
const ProjectFolder = memo(function ProjectFolder({
  project,
  drag: { handleNode, attributes, rowProps },
  open,
  onOpenChange,
  onNavigate,
}: {
  project: Project;
  /** The drag handle and pointer listeners, which the sortable list owns; a pointer drags the row from anywhere. */
  drag: SortableDrag;
  open: boolean;
  onOpenChange: (projectId: string, open: boolean) => void;
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();

  const [over, setOver] = useState(false);
  const refreshSessions = useRefreshChatSessions();
  const move = useMutation({
    ...moveChatProjectMutation(),
    // The returned session also reaches the open page and the thread list, which read the Project from it.
    onSuccess: (moved) => refreshSessions(moved),
  });
  const selected = useRouterState({
    select: (state) => state.location.pathname === `/projects/${project.id}`,
  });
  const navigate = useNavigate();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const menuTrigger = useRef<HTMLButtonElement>(null);
  const cache = useQueryClient();
  // A fresh entry is not read again, so repeated pointing costs nothing within the stale time.
  function prefetchSessions() {
    if (!open) void cache.prefetchInfiniteQuery(projectSessionsOptions(project.id));
  }
  /** The Project's page holds an unsent conversation in it; the first send creates the session. */
  function openProject() {
    onNavigate?.();
    void navigate({ to: "/projects/$projectId", params: { projectId: project.id } });
  }
  return (
    <Collapsible
      open={open}
      onOpenChange={(next) => onOpenChange(project.id, next)}
      onDragOver={(event) => {
        if (!move.isPending && event.dataTransfer.types.includes(CHAT_DRAG_TYPE)) {
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
        if (!sessionId || move.isPending) return;
        move.mutate({
          path: { sessionId },
          body: { projectId: project.id },
          signal: AbortSignal.timeout(30000),
        });
      }}
    >
      <div
        {...rowProps}
        className={cn(
          "group flex touch-none items-center rounded-lg hover:bg-surface-sunken active:cursor-grabbing",
          (selected || over) && "bg-surface-sunken",
        )}
      >
        <button
          ref={handleNode}
          type="button"
          aria-label={ui("Kéo để sắp xếp {{v1}}", { v1: project.name })}
          className="sr-only"
          {...attributes}
        >
          <GripVertical aria-hidden="true" className="size-3.5" />
        </button>
        <CollapsibleTrigger asChild>
          <button
            type="button"
            aria-label={ui("Hội thoại trong {{v1}}", { v1: project.name })}
            aria-current={selected ? "page" : undefined}
            // Reading ahead lets the folder open once to its final height instead of growing after a skeleton.
            onPointerEnter={prefetchSessions}
            onFocus={prefetchSessions}
            className="group/project-row flex min-w-0 flex-1 items-center gap-2 rounded-lg py-2 pr-1 pl-3 text-left text-sm outline-none"
          >
            <ProjectIcon
              iconName={project.iconName}
              className="size-4 shrink-0 text-content-muted"
            />
            <span className="truncate">{project.name}</span>
          </button>
        </CollapsibleTrigger>
        <DropdownMenu>
          <span className={cn("flex pr-0.5", hoverReveal, "has-data-[state=open]:opacity-100")}>
            <DropdownMenuTrigger asChild>
              <IconButton
                ref={menuTrigger}
                size="sm"
                prominence="internal"
                aria-label={ui("Thao tác với {{name}}", { name: project.name })}
              >
                <MoreHorizontal />
              </IconButton>
            </DropdownMenuTrigger>
          </span>
          <DropdownMenuContent
            align="end"
            className="w-auto min-w-44"
            onCloseAutoFocus={(event) => {
              if (editing || deleting) event.preventDefault();
            }}
          >
            <DropdownMenuGroup>
              <DropdownMenuItem className="cursor-pointer" onSelect={openProject}>
                <Plus /> {ui("Hội thoại mới")}
              </DropdownMenuItem>
              <DropdownMenuItem className="cursor-pointer" onSelect={openProject}>
                <FolderOpen /> {ui("Xem dự án")}
              </DropdownMenuItem>
              <DropdownMenuItem className="cursor-pointer" onSelect={() => setEditing(true)}>
                <Pencil /> {ui("Sửa")}
              </DropdownMenuItem>
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuItem
                className="cursor-pointer"
                variant="destructive"
                onSelect={() => setDeleting(true)}
              >
                <Trash2 /> {ui("Xóa")}
              </DropdownMenuItem>
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      <ProjectDeleteDialog
        project={project}
        open={deleting}
        onOpenChange={setDeleting}
        restoreFocusRef={menuTrigger}
      />
      {editing && <ProjectEditor project={project} onClose={() => setEditing(false)} />}
      <CollapsibleContent>
        <ProjectConversations project={project} onNavigate={onNavigate} />
      </CollapsibleContent>
      {move.isPending && (
        <p role="status" className="px-3 text-xs">
          {ui("Đang chuyển hội thoại…")}
        </p>
      )}
      {move.isError && (
        <p role="alert" className="px-3 text-xs">
          {ui(actionErrorText(move.error))}
        </p>
      )}
    </Collapsible>
  );
});

/** The most recent conversations of an open project; mounted, and so fetched, only while it is open. */
function ProjectConversations({
  project,
  onNavigate,
}: {
  project: Project;
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();
  const sessions = useInfiniteQuery(projectSessionsOptions(project.id));
  const rows = sessions.data?.pages.flat();
  return (
    <div className="pl-6">
      {sessions.isPending && (
        <LoadingRows label={ui("Đang tải hội thoại…")} rows={projectSkeletonRows} />
      )}
      {sessions.isError && (
        <div role="alert" className="flex h-8 items-center gap-1 pl-2 text-xs text-content-muted">
          <span className="min-w-0 flex-1 truncate">{ui("Không tải được hội thoại.")}</span>
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Tải lại")}
            pending={sessions.isFetching}
            onClick={() => void sessions.refetch()}
          >
            <RotateCcw />
          </IconButton>
        </div>
      )}
      {rows?.length === 0 && (
        <p className="px-2 py-1 text-xs text-content-muted">{ui("Chưa có hội thoại.")}</p>
      )}
      {rows && rows.length > 0 && (
        <ThreadList label={ui("Hội thoại trong {{v1}}", { v1: project.name })}>
          {rows.map((session) => (
            <ChatSessionRow key={session.id} session={session} onNavigate={onNavigate} />
          ))}
        </ThreadList>
      )}
      {sessions.hasNextPage && (
        <button
          type="button"
          disabled={sessions.isFetchingNextPage}
          onClick={() => void sessions.fetchNextPage()}
          className="flex h-8 w-full cursor-pointer items-center rounded-lg px-2 text-left text-xs text-content-muted outline-none hover:bg-surface-subtle hover:text-content-primary disabled:cursor-default disabled:opacity-60"
        >
          {ui("Xem thêm")}
        </button>
      )}
    </div>
  );
}
