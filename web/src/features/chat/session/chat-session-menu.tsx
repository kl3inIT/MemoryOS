import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { useRef, useState } from "react";
import {
  Archive,
  ArchiveRestore,
  Check,
  FolderInput,
  FolderOutput,
  FolderPlus,
  MoreHorizontal,
  Paperclip,
  Pencil,
  RotateCw,
  Settings2,
  Share2,
  Trash2,
} from "lucide-react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  archiveChatSessionMutation,
  deleteChatSessionMutation,
  moveChatProjectMutation,
  unarchiveChatSessionMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { actionErrorText } from "@/lib/action-errors";
import { projectsOptions } from "@/features/chat/projects/chat-projects-api";
import { ProjectEditor } from "@/features/chat/projects/project-editor";
import { ProjectIcon } from "@/features/chat/projects/project-icon";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";
import { SharingDialog } from "./chat-sharing-dialog";

type MenuSession = Pick<ChatSession, "id" | "title" | "projectId" | "archivedAt">;

export function ChatSessionMenu({
  session,
  onRename,
  onConfigure,
  onChange,
  deleteSession,
  archiveSession,
  onDelete,
  onArchive,
  onShowFiles,
  busy = false,
}: {
  session: MenuSession;
  onRename?: () => void;
  onConfigure?: () => void;
  onChange?: () => Promise<void>;
  /** Thread-list deletion; defaults to the direct API call for lists outside the thread list. */
  deleteSession?: () => Promise<void>;
  /** Thread-list archiving, so the sidebar drops the row at once; otherwise the API is called directly. */
  archiveSession?: (archived: boolean) => Promise<void>;
  onDelete?: () => void;
  onArchive?: (archived: boolean) => void;
  /** Opens the conversation's files from this menu's button, where focus returns; a phone has no room for theirs. */
  onShowFiles?: (menuButton: HTMLElement | null) => void;
  busy?: boolean;
}) {
  const ui = useAppTranslation();
  const [dialog, setDialog] = useState<"project" | "delete" | "share">();
  const trigger = useRef<HTMLButtonElement>(null);
  const refreshSessions = useRefreshChatSessions();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const archive = useMutation(archiveChatSessionMutation());
  const unarchive = useMutation(unarchiveChatSessionMutation());
  const remove = useMutation(deleteChatSessionMutation());
  const move = useMutation(moveChatProjectMutation());
  const notify = useActionNotifications();
  const path = { sessionId: session.id };
  async function refresh(notify = true) {
    await refreshSessions(session.id);
    if (notify) await onChange?.();
  }
  const archived = session.archivedAt != null;
  /**
   * Archiving keeps the conversation and only takes it off the sidebar, so it asks for no confirmation; the
   * thread list is told first when it owns the row, because it has to stop listing it.
   */
  async function setArchived(next: boolean) {
    if (archiveSession) await archiveSession(next);
    else
      await (next ? archive : unarchive).mutateAsync({
        path,
        signal: AbortSignal.timeout(30000),
      });
    onArchive?.(next);
    await refresh();
  }
  /**
   * Moves the conversation into a Project, or out of every Project with `null`; its history is kept. The menu
   * has closed by the time this fails, so the failure is raised as a notification instead.
   */
  async function moveTo(projectId: string | null) {
    try {
      const moved = await move.mutateAsync({
        path,
        body: { projectId },
        signal: AbortSignal.timeout(30000),
      });
      // The returned session also reaches the open page and the thread list, which read the Project from it.
      await refreshSessions(moved);
      await onChange?.();
    } catch (failure) {
      notify({ title: actionErrorText(failure), tone: "error" });
    }
  }
  function closeDialog() {
    setDialog(undefined);
    trigger.current?.focus();
  }
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <IconButton
            ref={trigger}
            size="sm"
            prominence="internal"
            aria-label={ui("Thao tác hội thoại {{v1}}", { v1: session.title })}
          >
            <MoreHorizontal />
          </IconButton>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="end"
          sideOffset={5}
          className="min-w-52"
          onCloseAutoFocus={(event) => {
            if (dialog) event.preventDefault();
          }}
        >
          <DropdownMenuGroup>
            {onShowFiles && (
              <DropdownMenuItem onSelect={() => onShowFiles(trigger.current)}>
                <Paperclip />
                {ui("Tệp trong hội thoại")}
              </DropdownMenuItem>
            )}
            <DropdownMenuItem onSelect={() => setDialog("share")}>
              <Share2 />
              {ui("Chia sẻ")}
            </DropdownMenuItem>
            {onRename && (
              <DropdownMenuItem onSelect={onRename}>
                <Pencil />
                {ui("Đổi tên")}
              </DropdownMenuItem>
            )}
            <DropdownMenuSub>
              <DropdownMenuSubTrigger disabled={busy || move.isPending}>
                <FolderInput />
                {ui("Chuyển đến dự án")}
              </DropdownMenuSubTrigger>
              <DropdownMenuSubContent className="max-h-72 w-64 overflow-y-auto">
                <DropdownMenuItem onSelect={() => setDialog("project")}>
                  <FolderPlus />
                  {ui("Dự án mới")}
                </DropdownMenuItem>
                <ProjectChoices currentProjectId={session.projectId} onChoose={moveTo} />
              </DropdownMenuSubContent>
            </DropdownMenuSub>
            {session.projectId && (
              <DropdownMenuItem
                disabled={busy || move.isPending}
                onSelect={() => void moveTo(null)}
              >
                <FolderOutput />
                {ui("Gỡ khỏi dự án")}
              </DropdownMenuItem>
            )}
            {onConfigure && (
              <DropdownMenuItem disabled={busy} onSelect={onConfigure}>
                <Settings2 />
                {ui("Cấu hình hội thoại")}
              </DropdownMenuItem>
            )}
            <DropdownMenuItem disabled={busy} onSelect={() => void setArchived(!archived)}>
              {archived ? <ArchiveRestore /> : <Archive />}
              {archived ? ui("Bỏ lưu trữ") : ui("Lưu trữ")}
            </DropdownMenuItem>
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuItem variant="destructive" onSelect={() => setDialog("delete")}>
              <Trash2 />
              {ui("Xóa")}
            </DropdownMenuItem>
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      <SharingDialog
        sessionId={session.id}
        restoreFocusRef={trigger}
        open={dialog === "share"}
        onOpenChange={(next) => {
          if (!next) closeDialog();
        }}
      />
      {dialog === "project" && (
        <ProjectEditor onClose={closeDialog} onCreated={(project) => moveTo(project.id)} />
      )}
      <ConfirmDialog
        open={dialog === "delete"}
        onOpenChange={(next) => {
          if (!next) setDialog(undefined);
        }}
        restoreFocusRef={trigger}
        title={ui("Xóa hội thoại?")}
        description={ui(
          "“{{v1}}” sẽ bị xóa vĩnh viễn khỏi lịch sử và liên kết chia sẻ, không khôi phục được. Câu trả lời đang chạy cũng sẽ dừng.",
          { v1: session.title },
        )}
        confirmLabel={ui("Xóa hội thoại")}
        pendingLabel={ui("Đang xóa…")}
        errorMessage={actionErrorText}
        onConfirm={async () => {
          if (deleteSession) await deleteSession();
          else await remove.mutateAsync({ path, signal: AbortSignal.timeout(30000) });
          onDelete?.();
          if (pathname === `/chat/${session.id}`) await navigate({ to: "/" });
          await refresh(false);
        }}
      />
    </>
  );
}

/** The person's Projects as menu items, read when the submenu opens; the current one is marked and disabled. */
function ProjectChoices({
  currentProjectId,
  onChoose,
}: {
  currentProjectId?: string | null;
  onChoose: (projectId: string) => Promise<void>;
}) {
  const ui = useAppTranslation();
  const projects = useQuery(projectsOptions());
  if (projects.isPending || (projects.isError && projects.isFetching))
    return (
      <>
        <DropdownMenuSeparator />
        <div role="status" aria-label={ui("Đang tải dự án…")} className="flex flex-col gap-1 p-1.5">
          <Skeleton className="h-5" />
          <Skeleton className="h-5" />
          <Skeleton className="h-5" />
        </div>
      </>
    );
  if (projects.isError)
    return (
      <>
        <DropdownMenuSeparator />
        <DropdownMenuLabel role="alert">{ui("Không tải được dự án.")}</DropdownMenuLabel>
        <DropdownMenuItem
          onSelect={(event) => {
            // Stay open so the list can appear where the failure was.
            event.preventDefault();
            void projects.refetch();
          }}
        >
          <RotateCw />
          {ui("Tải lại")}
        </DropdownMenuItem>
      </>
    );
  if (projects.data.length === 0) return null;
  return (
    <>
      <DropdownMenuSeparator />
      {projects.data.map((project) => (
        <DropdownMenuItem
          key={project.id}
          disabled={project.id === currentProjectId}
          onSelect={() => void onChoose(project.id)}
        >
          <ProjectIcon iconName={project.iconName} />
          <span className="min-w-0 flex-1 truncate">{project.name}</span>
          {project.id === currentProjectId && <Check aria-hidden="true" />}
        </DropdownMenuItem>
      ))}
    </>
  );
}
