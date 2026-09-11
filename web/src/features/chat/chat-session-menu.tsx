import { ThreadListItemMorePrimitive as More } from "@assistant-ui/react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { useRef, useState } from "react";
import {
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
import { useApplicationSession } from "@/features/identity/application-session-context";
import { deleteChatSession } from "@/lib/hey-api/sdk.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { sameOriginMutationHeaders } from "@/lib/api";
import { chatSessionsKey } from "./chat-api";
import { ChatDialog } from "./chat-dialog";
import { SharingDialog } from "./chat-sharing-dialog";
import { chatActionError } from "./chat-action-utils";
import { loadProjects, moveConversation } from "./chat-workspace-api";

export function ChatSessionMenu({
  session,
  onRename,
  onConfigure,
  onChange,
  onDelete,
  busy = false,
}: {
  session: Pick<ChatSession, "id" | "title" | "projectId">;
  onRename?: () => void;
  onConfigure?: () => void;
  onChange?: () => Promise<void>;
  onDelete?: () => void;
  busy?: boolean;
}) {
  const [dialog, setDialog] = useState<"move" | "delete" | "share">();
  const [target, setTarget] = useState<string | null>();
  const trigger = useRef<HTMLButtonElement>(null);
  const cache = useQueryClient();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const { actorId, authorizationVersion } = useApplicationSession();
  const projects = useQuery({
    queryKey: ["chat-projects", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadProjects(signal),
    enabled: dialog === "move",
  });
  async function refresh(notify = true) {
    await Promise.all([
      cache.invalidateQueries({ queryKey: chatSessionsKey }),
      cache.invalidateQueries({ queryKey: ["chat-project-sessions"] }),
      cache.invalidateQueries({ queryKey: ["chat-session", session.id] }),
    ]);
    if (notify) await onChange?.();
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
            aria-label={`Thao tác hội thoại ${session.title}`}
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
            Chia sẻ
          </More.Item>
          {onRename && (
            <More.Item className={itemClass} onSelect={onRename}>
              <Pencil className="size-4" />
              Đổi tên
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
            Chuyển vào dự án
          </More.Item>
          {onConfigure && (
            <More.Item className={itemClass} disabled={busy} onSelect={onConfigure}>
              <Settings2 className="size-4" />
              Cấu hình hội thoại
            </More.Item>
          )}
          <More.Separator className="my-1 border-t border-border-subtle" />
          <More.Item
            className={`${itemClass} text-status-danger-content`}
            onSelect={() => setDialog("delete")}
          >
            <Trash2 className="size-4" />
            Xóa
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
        <ChatDialog
          open
          onOpenChange={(next) => {
            if (!next) {
              setDialog(undefined);
              trigger.current?.focus();
            }
          }}
          title="Chuyển hội thoại"
          description="Lịch sử hội thoại được giữ nguyên. Hướng dẫn dự án áp dụng cho lượt tiếp theo."
          submitLabel="Chuyển"
          submitDisabled={target === undefined || projects.isFetching || projects.isError}
          onSubmit={async () => {
            if (target === undefined) return;
            await moveConversation(session.id, target);
            await refresh();
          }}
        >
          {projects.isPending && <p role="status">Đang tải dự án…</p>}
          {projects.isError && (
            <p role="alert">
              Không tải được dự án.{" "}
              <Button type="button" onClick={() => void projects.refetch()}>
                Tải lại
              </Button>
            </p>
          )}
          <div
            role="radiogroup"
            aria-label="Dự án đích"
            className="max-h-64 space-y-1 overflow-y-auto"
          >
            {[{ id: null, name: "Ngoài dự án" }, ...(projects.data ?? [])].map((project) => (
              <label
                key={project.id ?? "none"}
                className="flex w-full cursor-pointer items-center gap-2 rounded-lg px-3 py-2 text-left hover:bg-surface-sunken has-checked:bg-surface-sunken has-disabled:opacity-40 has-focus-visible:ring-2 has-focus-visible:ring-ring"
              >
                <input
                  type="radio"
                  name="target-project"
                  className="size-4 shrink-0 accent-content-primary"
                  aria-label={project.name}
                  checked={target === project.id}
                  disabled={project.id === (session.projectId ?? null)}
                  onChange={() => setTarget(project.id)}
                />
                {project.id ? (
                  <FolderInput className="size-4" />
                ) : (
                  <FolderOutput className="size-4" />
                )}
                {project.name}
              </label>
            ))}
          </div>
        </ChatDialog>
      )}
      <ConfirmDialog
        open={dialog === "delete"}
        onOpenChange={(next) => {
          if (!next) setDialog(undefined);
        }}
        restoreFocusRef={trigger}
        title="Xóa hội thoại?"
        description={`“${session.title}” sẽ bị xóa khỏi lịch sử và liên kết chia sẻ. Câu trả lời đang chạy cũng sẽ dừng.`}
        confirmLabel="Xóa hội thoại"
        pendingLabel="Đang xóa…"
        errorMessage={chatActionError}
        onConfirm={async () => {
          await deleteChatSession({
            path: { sessionId: session.id },
            headers: sameOriginMutationHeaders,
            signal: AbortSignal.timeout(30000),
            throwOnError: true,
          });
          onDelete?.();
          if (pathname === `/chat/${session.id}`) await navigate({ to: "/" });
          await refresh(false);
        }}
      />
    </>
  );
}
