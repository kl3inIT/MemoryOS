import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, useState } from "react";
import { Link, useRouterState } from "@tanstack/react-router";
import { useQueryClient } from "@tanstack/react-query";
import { Check, X } from "lucide-react";
import { ThreadListRow } from "@/components/assistant-ui/elements/thread-list";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { sameOriginMutationHeaders } from "@/lib/api";
import { renameChatSession } from "@/lib/hey-api/sdk.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { ChatSessionMenu } from "./chat-session-menu";
import { chatSessionsKey } from "./chat-api";
import { chatActionError } from "./chat-action-utils";

export const CHAT_DRAG_TYPE = "application/x-memoryos-chat";

export function ChatSessionRow({
  session,
  onNavigate,
  showTime = false,
}: {
  session: ChatSession;
  onNavigate?: () => void;
  showTime?: boolean;
}) {
  const ui = useAppTranslation();

  const [renaming, setRenaming] = useState(false);
  const [title, setTitle] = useState(session.title);
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  const [error, setError] = useState<string>();
  const cache = useQueryClient();
  const link = useRef<HTMLAnchorElement>(null);
  function closeEditor() {
    setRenaming(false);
    setError(undefined);
    requestAnimationFrame(() => link.current?.focus());
  }
  const selected = useRouterState({
    select: (state) => state.location.pathname === `/chat/${session.id}`,
  });
  return (
    <div>
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
              onRename={() => {
                setTitle(session.title);
                setError(undefined);
                setRenaming(true);
              }}
            />
          )
        }
      >
        {renaming ? (
          <form
            className="flex items-center gap-1 py-1"
            onSubmit={(event) => {
              event.preventDefault();
              if (busy.current || !title.trim()) return;
              busy.current = true;
              setPending(true);
              setError(undefined);
              void renameChatSession({
                path: { sessionId: session.id },
                body: { title: title.trim() },
                headers: sameOriginMutationHeaders,
                signal: AbortSignal.timeout(30000),
                throwOnError: true,
              })
                .then(async () => {
                  await Promise.all([
                    cache.invalidateQueries({ queryKey: chatSessionsKey }),
                    cache.invalidateQueries({ queryKey: ["chat-project-sessions"] }),
                    cache.invalidateQueries({ queryKey: ["chat-session", session.id] }),
                  ]);
                  closeEditor();
                })
                .catch((cause: unknown) => setError(chatActionError(cause)))
                .finally(() => {
                  busy.current = false;
                  setPending(false);
                });
            }}
          >
            <Input
              autoFocus
              aria-label={ui("Tên hội thoại")}
              value={title}
              required
              maxLength={200}
              disabled={pending}
              onFocus={(event) => event.target.select()}
              onChange={(event) => setTitle(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Escape" && !busy.current) {
                  event.preventDefault();
                  closeEditor();
                }
              }}
              className="min-w-0"
            />
            <IconButton
              type="submit"
              size="sm"
              prominence="internal"
              aria-label={ui("Lưu tên hội thoại")}
              disabled={pending || !title.trim()}
            >
              <Check />
            </IconButton>
            <IconButton
              type="button"
              size="sm"
              prominence="internal"
              aria-label={ui("Hủy đổi tên")}
              disabled={pending}
              onClick={() => {
                closeEditor();
              }}
            >
              <X />
            </IconButton>
          </form>
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
      {error && (
        <p role="alert" className="px-3 py-1 text-xs text-status-danger-content">
          {ui(error)}
        </p>
      )}
    </div>
  );
}
