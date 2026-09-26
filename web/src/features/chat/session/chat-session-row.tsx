import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useEffect, useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Link, useRouterState } from "@tanstack/react-router";
import { Check, X } from "lucide-react";
import { ThreadListRow } from "@/components/assistant-ui/elements/thread-list";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { renameChatSessionMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { ChatSessionMenu } from "./chat-session-menu";
import { actionErrorText } from "@/lib/action-errors";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";

export const CHAT_DRAG_TYPE = "application/x-memoryos-chat";

export function ChatSessionRow({
  session,
  onNavigate,
  showTime = false,
  rename,
  deleteSession,
  archiveSession,
}: {
  session: ChatSession;
  onNavigate?: () => void;
  showTime?: boolean;
  /** Thread-list actions; rows outside the thread list use the session API directly. */
  rename?: (title: string) => Promise<void>;
  deleteSession?: () => Promise<void>;
  archiveSession?: (archived: boolean) => Promise<void>;
}) {
  const [renaming, setRenaming] = useState(false);
  const link = useRef<HTMLAnchorElement>(null);
  function closeEditor() {
    setRenaming(false);
    requestAnimationFrame(() => link.current?.focus());
  }
  const selected = useRouterState({
    select: (state) => state.location.pathname === `/chat/${session.id}`,
  });
  return (
    <ThreadListRow
      selected={selected}
      onDragStart={
        renaming
          ? undefined
          : (event) => {
              event.dataTransfer.setData(CHAT_DRAG_TYPE, session.id);
              event.dataTransfer.effectAllowed = "move";
            }
      }
      actions={
        renaming ? undefined : (
          <ChatSessionMenu
            session={session}
            deleteSession={deleteSession}
            archiveSession={archiveSession}
            onRename={() => setRenaming(true)}
          />
        )
      }
    >
      {renaming ? (
        <ChatSessionRenameForm session={session} rename={rename} onClose={closeEditor} />
      ) : (
        <Link
          ref={link}
          to="/chat/$sessionId"
          params={{ sessionId: session.id }}
          onClick={onNavigate}
          aria-current={selected ? "page" : undefined}
          className="flex min-w-0 flex-col gap-1 rounded-lg px-2 py-2 outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <span className="truncate">{session.title}</span>
          {showTime && (
            <time dateTime={session.updatedAt} className="text-xs text-content-muted">
              {new Date(session.updatedAt).toLocaleString(uiLocale(), {
                dateStyle: "medium",
                timeStyle: "short",
              })}
            </time>
          )}
        </Link>
      )}
    </ThreadListRow>
  );
}

/** Renames a conversation in place of its row; Escape or cancel returns to the row. */
function ChatSessionRenameForm({
  session,
  rename,
  onClose,
}: {
  session: ChatSession;
  rename?: (title: string) => Promise<void>;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const [title, setTitle] = useState(session.title);
  const input = useRef<HTMLInputElement>(null);
  const refreshSessions = useRefreshChatSessions();
  const renameSession = useMutation(renameChatSessionMutation());
  // The thread list renames its own rows, so the sidebar updates at once; other lists call the API.
  const save = useMutation({
    mutationFn: async (next: string) => {
      if (rename) await rename(next);
      else
        await renameSession.mutateAsync({
          path: { sessionId: session.id },
          body: { title: next },
          signal: AbortSignal.timeout(30000),
        });
    },
    onSuccess: async () => {
      await refreshSessions(session.id);
      onClose();
    },
  });
  // The field replaces the row the person chose to rename, so it takes the focus with its text selected.
  useEffect(() => input.current?.select(), []);
  return (
    <div className="flex flex-col gap-1 py-1">
      <form
        className="flex items-center gap-1"
        onSubmit={(event) => {
          event.preventDefault();
          if (save.isPending || !title.trim()) return;
          save.mutate(title.trim());
        }}
      >
        <Input
          ref={input}
          aria-label={ui("Tên hội thoại")}
          value={title}
          required
          maxLength={200}
          disabled={save.isPending}
          onChange={(event) => setTitle(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Escape" && !save.isPending) {
              event.preventDefault();
              onClose();
            }
          }}
          className="min-w-0"
        />
        <IconButton
          type="submit"
          size="sm"
          prominence="internal"
          aria-label={ui("Lưu tên hội thoại")}
          disabled={save.isPending || !title.trim()}
        >
          <Check />
        </IconButton>
        <IconButton
          type="button"
          size="sm"
          prominence="internal"
          aria-label={ui("Hủy đổi tên")}
          disabled={save.isPending}
          onClick={onClose}
        >
          <X />
        </IconButton>
      </form>
      {save.error && (
        <p role="alert" className="px-2 text-xs text-status-danger-content">
          {ui(actionErrorText(save.error))}
        </p>
      )}
    </div>
  );
}
