import { formatUiMoment } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useMutation } from "@tanstack/react-query";
import { Link, useRouterState } from "@tanstack/react-router";
import { ThreadListRow } from "@/components/assistant-ui/elements/thread-list";
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
  leading,
  trailing,
  rename,
  deleteSession,
  archiveSession,
}: {
  session: ChatSession;
  onNavigate?: () => void;
  showTime?: boolean;
  /** Drawn before the title, such as the face of the conversation's assistant. */
  leading?: ReactNode;
  /** Drawn after the title on the same line, such as a short time. */
  trailing?: ReactNode;
  /** Thread-list actions; rows outside the thread list use the session API directly. */
  rename?: (title: string) => Promise<void>;
  deleteSession?: () => Promise<void>;
  archiveSession?: (archived: boolean) => Promise<void>;
}) {
  const [renaming, setRenaming] = useState(false);
  const link = useRef<HTMLAnchorElement>(null);
  function closeEditor(restoreFocus = true) {
    setRenaming(false);
    if (restoreFocus) requestAnimationFrame(() => link.current?.focus());
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
            onChange={async () => {
              // Removing a Project row unmounts its menu button and drops focus to the page; the same
              // conversation stays listed under "Hội thoại gần đây", so focus continues there.
              requestAnimationFrame(() => {
                if (!link.current?.isConnected && document.activeElement === document.body)
                  document
                    .querySelector<HTMLAnchorElement>(`a[data-chat-session-id="${session.id}"]`)
                    ?.focus();
              });
            }}
          />
        )
      }
    >
      {renaming ? (
        <ChatSessionRenameForm session={session} rename={rename} onClose={closeEditor} />
      ) : (
        <Link
          ref={link}
          data-chat-session-id={session.id}
          to="/chat/$sessionId"
          params={{ sessionId: session.id }}
          // The row is what is dragged; a dragged link would carry its address as the picture.
          draggable={false}
          onClick={onNavigate}
          aria-current={selected ? "page" : undefined}
          className="flex min-w-0 items-center gap-2 rounded-lg px-2 py-2 outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {leading}
          <span className="flex min-w-0 flex-1 flex-col gap-1">
            <span className="truncate">{session.title}</span>
            {showTime && (
              <time dateTime={session.updatedAt} className="text-xs text-content-muted">
                {formatUiMoment(session.updatedAt)}
              </time>
            )}
          </span>
          {session.replyRunning && <ReplyRunning />}
          {trailing}
        </Link>
      )}
    </ThreadListRow>
  );
}

/** A turning ring while the conversation's answer is being written; it stands still when motion is reduced. */
export function ReplyRunning() {
  const ui = useAppTranslation();
  return (
    <span
      role="img"
      aria-label={ui("Đang trả lời…")}
      className="size-3 shrink-0 animate-spin rounded-full border-2 border-status-info-content border-r-transparent motion-reduce:animate-none"
    />
  );
}

/** Enter or leaving the name saves it; Escape returns to the row without saving. */
function ChatSessionRenameForm({
  session,
  rename,
  onClose,
}: {
  session: ChatSession;
  rename?: (title: string) => Promise<void>;
  onClose: (restoreFocus?: boolean) => void;
}) {
  const ui = useAppTranslation();
  const [title, setTitle] = useState(session.title);
  const input = useRef<HTMLInputElement>(null);
  // Set synchronously so Enter followed by the blur of the closing field writes once.
  const submitting = useRef(false);
  // Whether the save was made in the field (Enter) rather than by leaving it; read when the save ends.
  const returnFocus = useRef(false);
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
      // Leaving the field already chose where focus goes; only a save made in the field returns it to the row.
      // Refreshing the list can drop focus from the field to the page before this runs, so the choice made at
      // Enter decides, unless the person has since moved focus somewhere else.
      const focus = document.activeElement;
      onClose(
        returnFocus.current &&
          (focus === input.current || focus === null || focus === document.body),
      );
    },
    onSettled: () => {
      submitting.current = false;
    },
  });
  function commit(restoreFocus: boolean) {
    if (submitting.current) return;
    const next = title.trim();
    if (!next || next === session.title) {
      onClose(restoreFocus);
      return;
    }
    submitting.current = true;
    returnFocus.current = restoreFocus;
    save.mutate(next);
  }
  // The field replaces the row the person chose to rename, so it takes the focus with its text selected.
  useEffect(() => input.current?.select(), []);
  return (
    <div className="flex flex-col gap-1 py-1">
      <form
        className="flex items-center gap-1"
        onSubmit={(event) => {
          event.preventDefault();
          commit(true);
        }}
      >
        <Input
          ref={input}
          aria-label={ui("Tên hội thoại")}
          value={title}
          maxLength={200}
          // Read-only, not disabled: a disabled field would drop the focus the person is typing in.
          readOnly={save.isPending}
          aria-busy={save.isPending}
          onChange={(event) => setTitle(event.target.value)}
          onBlur={() => commit(false)}
          onKeyDown={(event) => {
            // Enter confirms an IME candidate; it must not save the half-composed name.
            if (event.key === "Enter" && event.nativeEvent.isComposing) event.preventDefault();
            if (event.key === "Escape" && !save.isPending) {
              event.preventDefault();
              // The field may blur as it unmounts; that must not save the discarded draft.
              submitting.current = true;
              onClose();
            }
          }}
          className="min-w-0 flex-1"
        />
      </form>
      {save.error && (
        <p role="alert" className="px-2 text-xs text-status-danger-content">
          {ui(actionErrorText(save.error))}
        </p>
      )}
    </div>
  );
}
