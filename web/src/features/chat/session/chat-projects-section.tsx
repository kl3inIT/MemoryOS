import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams, useRouterState } from "@tanstack/react-router";
import { ChevronRight, GripVertical, Plus } from "lucide-react";
import { useState, type ReactNode } from "react";
import { ThreadList } from "@/components/assistant-ui/elements/thread-list";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { SortableList } from "@/components/composites/sortable-list";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { IconButton } from "@/components/ui/icon-button";
import { Skeleton } from "@/components/ui/skeleton";
import { projectsOptions, type Project } from "@/features/chat/projects/chat-projects-api";
import { ProjectEditor } from "@/features/chat/projects/project-editor";
import { ProjectIcon } from "@/features/chat/projects/project-icon";
import { useReorderProjects } from "@/features/chat/projects/use-reorder-projects";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";
import { actionErrorText } from "@/lib/action-errors";
import {
  getChatSessionOptions,
  getChatSessionQueryKey,
  listProjectChatSessionsOptions,
  moveChatProjectMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { cn } from "@/lib/utils";
import { CHAT_DRAG_TYPE, ChatSessionRow } from "./chat-session-row";

/** The sidebar lists this many projects, and this many conversations of an open project. */
const SECTION_PROJECTS = 5;
const PROJECT_SESSIONS = 5;
const SECTION_STORAGE_KEY = "memoryos:chat-projects-section";

const skeletonRows = [0, 1, 2];

function readSectionOpen() {
  try {
    return window.localStorage.getItem(SECTION_STORAGE_KEY) !== "closed";
  } catch {
    return true;
  }
}

function storeSectionOpen(open: boolean) {
  try {
    window.localStorage.setItem(SECTION_STORAGE_KEY, open ? "open" : "closed");
  } catch {
    // Storage may be blocked; the state still holds for this page.
  }
}

/** Placeholder rows while a list loads; assistive technology hears the label once instead of each bar. */
function LoadingRows({ label }: { label: string }) {
  return (
    <div role="status" className="flex flex-col gap-1 px-2 py-1">
      <span className="sr-only">{label}</span>
      {skeletonRows.map((row) => (
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
  const [open, setOpen] = useState(readSectionOpen);
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

  function toggleProject(projectId: string, next: boolean) {
    setOpenProjects((current) => {
      const projectsOpen = new Set(current);
      if (next) projectsOpen.add(projectId);
      else projectsOpen.delete(projectId);
      return projectsOpen;
    });
  }

  const all = projects.data ?? [];
  const shown = all.slice(0, SECTION_PROJECTS);
  const viewedProject = all
    .slice(SECTION_PROJECTS)
    .find((project) => project.id === viewed.projectId);
  const listed = viewedProject ? [...shown, viewedProject] : shown;

  return (
    <Collapsible
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        storeSectionOpen(next);
      }}
    >
      <div className="mb-2 flex items-center justify-between px-2">
        <div className="flex items-center gap-1">
          <Link
            to="/projects"
            onClick={onNavigate}
            className="rounded text-sm font-medium text-content-secondary hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring"
          >
            {ui("Dự án")}
          </Link>
          <CollapsibleTrigger asChild>
            <IconButton size="sm" prominence="internal" aria-label={ui("Danh sách dự án")}>
              <ChevronRight className="transition-transform group-data-[state=open]/icon-button:rotate-90 motion-reduce:transition-none" />
            </IconButton>
          </CollapsibleTrigger>
        </div>
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
              className={cn(
                "relative rounded-lg",
                handle.dragging && "z-10 bg-surface-raised shadow-hover",
              )}
            >
              <ProjectFolder
                project={project}
                open={openProjects.has(project.id)}
                onOpenChange={(next) => toggleProject(project.id, next)}
                onNavigate={onNavigate}
                grip={
                  <button
                    ref={handle.setHandleRef}
                    type="button"
                    aria-label={ui("Kéo để sắp xếp {{v1}}", { v1: project.name })}
                    className={cn(
                      "grid h-8 w-4 shrink-0 cursor-grab place-items-center text-content-muted outline-none focus-visible:opacity-100 active:cursor-grabbing",
                      hoverReveal,
                    )}
                    {...handle.attributes}
                  >
                    <GripVertical aria-hidden="true" className="size-3.5" />
                  </button>
                }
              />
            </div>
          )}
        </SortableList>
        {all.length > SECTION_PROJECTS && (
          <Link
            to="/projects"
            onClick={onNavigate}
            className="flex h-8 items-center rounded-lg px-2 text-sm text-content-muted outline-none hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring"
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

function ProjectFolder({
  project,
  grip,
  open,
  onOpenChange,
  onNavigate,
}: {
  project: Project;
  /** The drag handle, which the sortable list owns and the row shows at its start. */
  grip: ReactNode;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();

  const [over, setOver] = useState(false);
  const refreshSessions = useRefreshChatSessions();
  const cache = useQueryClient();
  const move = useMutation({
    ...moveChatProjectMutation(),
    // The section reads the open conversation's Project from this entry, so the move is stored at once.
    onSuccess: (moved, { path }) => {
      cache.setQueryData(getChatSessionQueryKey({ path }), moved);
      return refreshSessions(path.sessionId);
    },
  });
  const selected = useRouterState({
    select: (state) => state.location.pathname === `/projects/${project.id}`,
  });
  return (
    <Collapsible
      open={open}
      onOpenChange={onOpenChange}
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
        className={cn(
          "group flex items-center rounded-lg hover:bg-surface-sunken",
          (selected || over) && "bg-surface-sunken",
          over && "ring-2 ring-ring",
        )}
      >
        {grip}
        <CollapsibleTrigger asChild>
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Hội thoại trong {{v1}}", { v1: project.name })}
          >
            <ChevronRight className="transition-transform group-data-[state=open]/icon-button:rotate-90 motion-reduce:transition-none" />
          </IconButton>
        </CollapsibleTrigger>
        <Link
          to="/projects/$projectId"
          params={{ projectId: project.id }}
          onClick={onNavigate}
          aria-current={selected ? "page" : undefined}
          className="flex min-w-0 flex-1 items-center gap-2 rounded-lg py-2 pr-2 text-sm outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40"
        >
          <ProjectIcon iconName={project.iconName} className="size-4 shrink-0 text-content-muted" />
          <span className="truncate">{project.name}</span>
        </Link>
      </div>
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
}

/** The most recent conversations of an open project; mounted, and so fetched, only while it is open. */
function ProjectConversations({
  project,
  onNavigate,
}: {
  project: Project;
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();
  const sessions = useQuery(
    listProjectChatSessionsOptions({
      path: { projectId: project.id },
      query: { limit: PROJECT_SESSIONS },
    }),
  );
  return (
    <div className="pl-4">
      {sessions.isPending && <LoadingRows label={ui("Đang tải hội thoại…")} />}
      {sessions.isError && (
        <div role="alert" className="flex items-center gap-2 px-2 py-1 text-xs">
          <span>{ui("Không tải được hội thoại.")}</span>
          <Button size="sm" prominence="internal" onClick={() => void sessions.refetch()}>
            {ui("Tải lại")}
          </Button>
        </div>
      )}
      {sessions.data?.length === 0 && (
        <p className="px-2 py-1 text-xs text-content-muted">{ui("Chưa có hội thoại.")}</p>
      )}
      {sessions.data && sessions.data.length > 0 && (
        <ThreadList label={ui("Hội thoại trong {{v1}}", { v1: project.name })}>
          {sessions.data.map((session) => (
            <ChatSessionRow key={session.id} session={session} onNavigate={onNavigate} />
          ))}
        </ThreadList>
      )}
      {sessions.data && sessions.data.length >= PROJECT_SESSIONS && (
        <Link
          to="/projects/$projectId"
          params={{ projectId: project.id }}
          onClick={onNavigate}
          className="flex h-8 items-center rounded-lg px-2 text-xs text-content-muted outline-none hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring"
        >
          {ui("Xem thêm")}
        </Link>
      )}
    </div>
  );
}
