import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { useRef, useState } from "react";
import {
  Archive,
  ArchiveRestore,
  FolderInput,
  FolderOutput,
  MoreHorizontal,
  Pencil,
  Settings2,
  Share2,
  Trash2,
} from "lucide-react";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Field, FieldLabel } from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import {
  archiveChatSessionMutation,
  deleteChatSessionMutation,
  moveChatProjectMutation,
  unarchiveChatSessionMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { FormDialog } from "@/components/composites/form-dialog";
import { actionErrorText } from "@/lib/action-errors";
import { projectsOptions } from "@/features/chat/projects/chat-projects-api";
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
  busy?: boolean;
}) {
  const ui = useAppTranslation();
  const [dialog, setDialog] = useState<"move" | "delete" | "share">();
  const trigger = useRef<HTMLButtonElement>(null);
  const refreshSessions = useRefreshChatSessions();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const archive = useMutation(archiveChatSessionMutation());
  const unarchive = useMutation(unarchiveChatSessionMutation());
  const remove = useMutation(deleteChatSessionMutation());
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
            <DropdownMenuItem disabled={busy} onSelect={() => setDialog("move")}>
              <FolderInput />
              {ui("Chuyển vào dự án")}
            </DropdownMenuItem>
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
        open={dialog === "share"}
        onOpenChange={(next) => {
          if (!next) closeDialog();
        }}
      />
      {dialog === "move" && (
        <ChatMoveDialog session={session} onMoved={() => refresh()} onClose={closeDialog} />
      )}
      <ConfirmDialog
        open={dialog === "delete"}
        onOpenChange={(next) => {
          if (!next) setDialog(undefined);
        }}
        restoreFocusRef={trigger}
        title={ui("Xóa hội thoại?")}
        description={ui(
          "“{{v1}}” sẽ bị xóa khỏi lịch sử và liên kết chia sẻ. Câu trả lời đang chạy cũng sẽ dừng.",
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

/** Moves a conversation into a Project, or out of every Project; its history is kept. */
function ChatMoveDialog({
  session,
  onMoved,
  onClose,
}: {
  session: MenuSession;
  onMoved: () => Promise<void>;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const [target, setTarget] = useState<string | null>();
  const projects = useQuery(projectsOptions());
  const move = useMutation(moveChatProjectMutation());
  return (
    <FormDialog
      open
      onOpenChange={(next) => {
        if (!next) onClose();
      }}
      title={ui("Chuyển hội thoại")}
      description={ui(
        "Lịch sử hội thoại được giữ nguyên. Hướng dẫn dự án áp dụng cho lượt tiếp theo.",
      )}
      submitLabel={ui("Chuyển")}
      submitDisabled={target === undefined || projects.isFetching || projects.isError}
      onSubmit={async () => {
        if (target === undefined) return;
        await move.mutateAsync({
          path: { sessionId: session.id },
          body: { projectId: target },
          signal: AbortSignal.timeout(30000),
        });
        await onMoved();
      }}
    >
      {projects.isPending && <p role="status">{ui("Đang tải dự án…")}</p>}
      {projects.isError && (
        <Alert variant="destructive">
          <AlertDescription>{ui("Không tải được dự án.")}</AlertDescription>
          <AlertAction>
            <Button type="button" size="sm" onClick={() => void projects.refetch()}>
              {ui("Tải lại")}
            </Button>
          </AlertAction>
        </Alert>
      )}
      <RadioGroup
        aria-label={ui("Dự án đích")}
        className="max-h-64 overflow-y-auto"
        value={target === undefined ? "" : (target ?? "none")}
        onValueChange={(next) => setTarget(next === "none" ? null : next)}
      >
        {[{ id: null, name: ui("Ngoài dự án") }, ...(projects.data ?? [])].map((project) => {
          const value = project.id ?? "none";
          return (
            <FieldLabel key={value} htmlFor={`chat-move-${value}`}>
              <Field orientation="horizontal">
                <RadioGroupItem
                  id={`chat-move-${value}`}
                  value={value}
                  aria-label={project.name}
                  disabled={project.id === (session.projectId ?? null)}
                />
                {project.id ? (
                  <FolderInput aria-hidden="true" />
                ) : (
                  <FolderOutput aria-hidden="true" />
                )}
                {project.name}
              </Field>
            </FieldLabel>
          );
        })}
      </RadioGroup>
    </FormDialog>
  );
}
