import { useAppTranslation } from "@/i18n/use-app-translation";
import { ThreadListItemMorePrimitive as More } from "@assistant-ui/react";
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
import { IconButton } from "@/components/ui/icon-button";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { archiveChatSession, deleteChatSession, unarchiveChatSession } from "@/lib/hey-api/sdk.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { FormDialog } from "@/components/composites/form-dialog";
import { SharingDialog } from "./chat-sharing-dialog";
import { actionErrorText } from "@/lib/action-errors";
import { projectsOptions } from "@/features/chat/projects/chat-projects-api";
import { moveChatProjectMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";

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
  session: Pick<ChatSession, "id" | "title" | "projectId" | "archivedAt">;
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
  const [target, setTarget] = useState<string | null>();
  const trigger = useRef<HTMLButtonElement>(null);
  const refreshSessions = useRefreshChatSessions();
  const move = useMutation(moveChatProjectMutation());
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const projects = useQuery({
    ...projectsOptions(),
    enabled: dialog === "move",
  });
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
    else {
      const call = next ? archiveChatSession : unarchiveChatSession;
      await call({
        path: { sessionId: session.id },
        signal: AbortSignal.timeout(30000),
      });
    }
    onArchive?.(next);
    await refresh();
  }
  const itemClass =
    "flex cursor-default items-center gap-2 rounded-lg px-3 py-2 text-sm outline-none data-[highlighted]:bg-surface-sunken data-[disabled]:opacity-40";
  return (
    <>
      <More.Root>
        <More.Trigger asChild>
          <IconButton
            ref={trigger}
            size="sm"
            prominence="internal"
            aria-label={ui("Thao tác hội thoại {{v1}}", { v1: session.title })}
          >
            <MoreHorizontal />
          </IconButton>
        </More.Trigger>
        <More.Content
          align="end"
          sideOffset={5}
          className="z-50 min-w-52 rounded-xl border border-border-subtle bg-surface-overlay p-1.5 shadow-md"
          onCloseAutoFocus={(event) => {
            if (dialog) event.preventDefault();
          }}
        >
          <More.Item className={itemClass} onSelect={() => setDialog("share")}>
            <Share2 className="size-4" />
            {ui("Chia sẻ")}
          </More.Item>
          {onRename && (
            <More.Item className={itemClass} onSelect={onRename}>
              <Pencil className="size-4" />
              {ui("Đổi tên")}
            </More.Item>
          )}
          <More.Item
            className={itemClass}
            disabled={busy}
            onSelect={() => {
              setTarget(undefined);
              setDialog("move");
            }}
          >
            <FolderInput className="size-4" />
            {ui("Chuyển vào dự án")}
          </More.Item>
          {onConfigure && (
            <More.Item className={itemClass} disabled={busy} onSelect={onConfigure}>
              <Settings2 className="size-4" />
              {ui("Cấu hình hội thoại")}
            </More.Item>
          )}
          <More.Item
            className={itemClass}
            disabled={busy}
            onSelect={() => void setArchived(!archived)}
          >
            {archived ? <ArchiveRestore className="size-4" /> : <Archive className="size-4" />}
            {archived ? ui("Bỏ lưu trữ") : ui("Lưu trữ")}
          </More.Item>
          <More.Separator className="my-1 border-t border-border-subtle" />
          <More.Item
            className={`${itemClass} text-status-danger-content`}
            onSelect={() => setDialog("delete")}
          >
            <Trash2 className="size-4" />
            {ui("Xóa")}
          </More.Item>
        </More.Content>
      </More.Root>
      <SharingDialog
        sessionId={session.id}
        open={dialog === "share"}
        onOpenChange={(next) => {
          if (!next) {
            setDialog(undefined);
            trigger.current?.focus();
          }
        }}
      />
      {dialog === "move" && (
        <FormDialog
          open
          onOpenChange={(next) => {
            if (!next) {
              setDialog(undefined);
              trigger.current?.focus();
            }
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
            });
            await refresh();
          }}
        >
          {projects.isPending && <p role="status">{ui("Đang tải dự án…")}</p>}
          {projects.isError && (
            <p role="alert">
              {ui("Không tải được dự án.")}{" "}
              <Button type="button" onClick={() => void projects.refetch()}>
                {ui("Tải lại")}
              </Button>
            </p>
          )}
          <RadioGroup
            aria-label={ui("Dự án đích")}
            className="max-h-64 space-y-1 overflow-y-auto"
            value={target === undefined ? "" : (target ?? "none")}
            onValueChange={(next) => setTarget(next === "none" ? null : next)}
          >
            {[{ id: null, name: ui("Ngoài dự án") }, ...(projects.data ?? [])].map((project) => (
              <label
                key={project.id ?? "none"}
                className="flex w-full cursor-pointer items-center gap-2 rounded-lg px-3 py-2 text-left hover:bg-surface-sunken has-checked:bg-surface-sunken has-disabled:opacity-40 has-focus-visible:ring-2 has-focus-visible:ring-ring"
              >
                <RadioGroupItem
                  value={project.id ?? "none"}
                  aria-label={project.name}
                  disabled={project.id === (session.projectId ?? null)}
                />
                {project.id ? (
                  <FolderInput className="size-4" />
                ) : (
                  <FolderOutput className="size-4" />
                )}
                {project.name}
              </label>
            ))}
          </RadioGroup>
        </FormDialog>
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
          else
            await deleteChatSession({
              path: { sessionId: session.id },
              signal: AbortSignal.timeout(30000),
            });
          onDelete?.();
          if (pathname === `/chat/${session.id}`) await navigate({ to: "/" });
          await refresh(false);
        }}
      />
    </>
  );
}
