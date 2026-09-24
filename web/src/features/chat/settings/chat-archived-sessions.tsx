import { useDeferredValue, useState } from "react";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Archive, ArchiveRestore, Search, Trash2 } from "lucide-react";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Item, ItemActions, ItemContent, ItemDescription } from "@/components/ui/item";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Skeleton } from "@/components/ui/skeleton";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  deleteChatSession,
  listChatSessions,
  searchChatSessions,
  unarchiveChatSession,
} from "@/lib/hey-api/sdk.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { chatActionError } from "@/features/chat/chat-action-utils";
import { chatSessionsKey } from "@/features/chat/chat-api";

const PAGE = 30;

/**
 * The conversations the owner archived (MEM-153): out of the sidebar, still theirs. Following ChatGPT's archived
 * chats, each row only offers what an archived conversation can do — open it, put it back, or delete it — and a
 * search asks the server rather than filtering the page that happens to be loaded.
 */
export function ChatArchivedSessionsPage() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [search, setSearch] = useState("");
  const query = useDeferredValue(search.trim());
  const [failure, setFailure] = useState<string>();

  const pages = useInfiniteQuery({
    queryKey: [...chatSessionsKey, actorId, authorizationVersion, "archived"],
    queryFn: ({ pageParam, signal }) =>
      listChatSessions({
        query: { archived: true, offset: pageParam, limit: PAGE },
        signal,
      }).then((answer) => answer.data),
    initialPageParam: 0,
    getNextPageParam: (last, all) => (last.length === PAGE ? all.length * PAGE : undefined),
    enabled: query.length === 0,
  });
  const matches = useQuery({
    queryKey: [...chatSessionsKey, actorId, authorizationVersion, "archived-search", query],
    queryFn: ({ signal }) =>
      searchChatSessions({ query: { query, limit: PAGE }, signal }).then(
        // A search reads every conversation the owner has; this page shows the archived ones.
        (answer) =>
          answer.data.items.filter((item) => item.session.archivedAt).map((item) => item.session),
      ),
    enabled: query.length > 0,
  });

  const sessions: ChatSession[] =
    query.length > 0 ? (matches.data ?? []) : (pages.data?.pages.flat() ?? []);
  const loading = query.length > 0 ? matches.isPending : pages.isPending;
  const failed = query.length > 0 ? matches.isError : pages.isError;

  const act = async (run: () => Promise<unknown>) => {
    setFailure(undefined);
    try {
      await run();
      await cache.invalidateQueries({ queryKey: chatSessionsKey });
    } catch (cause) {
      setFailure(chatActionError(cause));
    }
  };

  return (
    <SettingsLayout>
      <PageHeader
        icon={<Archive />}
        title={ui("Hội thoại đã lưu trữ")}
        description={ui(
          "Hội thoại đã lưu trữ không còn trên thanh bên, nhưng vẫn mở được và vẫn tìm được.",
        )}
      />
      <div className="flex max-w-3xl flex-col gap-4">
        <div className="relative">
          <Search
            className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
            aria-hidden="true"
          />
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder={ui("Tìm trong hội thoại đã lưu trữ")}
            aria-label={ui("Tìm trong hội thoại đã lưu trữ")}
            maxLength={200}
            className="pl-9"
          />
        </div>
        {failure && (
          <Alert variant="destructive">
            <AlertTitle>{failure}</AlertTitle>
          </Alert>
        )}
        {failed && (
          <Alert variant="destructive">
            <AlertTitle>{ui("Không tải được hội thoại đã lưu trữ.")}</AlertTitle>
          </Alert>
        )}
        {loading && (
          <div className="flex flex-col gap-2" aria-hidden="true">
            {[0, 1, 2].map((row) => (
              <Skeleton key={row} className="h-14 w-full rounded-lg" />
            ))}
          </div>
        )}
        {!loading && !failed && sessions.length === 0 && (
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <Archive />
              </EmptyMedia>
              <EmptyTitle>
                {query.length > 0
                  ? ui("Không có hội thoại nào khớp")
                  : ui("Chưa lưu trữ hội thoại nào")}
              </EmptyTitle>
              <EmptyDescription>
                {query.length > 0
                  ? ui("Hãy thử từ khoá khác.")
                  : ui("Lưu trữ một hội thoại để dọn thanh bên mà vẫn giữ lại nó.")}
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        )}
        {sessions.length > 0 && (
          <ul role="list" className="flex flex-col">
            {sessions.map((session) => (
              <li key={session.id}>
                <Item
                  variant="default"
                  className="border-b border-border-subtle last:border-b-0 hover:bg-surface-subtle"
                >
                  <ItemContent className="min-w-0">
                    <Link
                      to="/chat/$sessionId"
                      params={{ sessionId: session.id }}
                      className="min-w-0 truncate font-main-ui-action hover:underline"
                    >
                      {session.title}
                    </Link>
                    <ItemDescription>
                      {session.archivedAt
                        ? ui("Đã lưu trữ {{date}}", {
                            date: new Date(session.archivedAt).toLocaleString(uiLocale(), {
                              dateStyle: "medium",
                              timeStyle: "short",
                            }),
                          })
                        : ""}
                    </ItemDescription>
                  </ItemContent>
                  <ItemActions className="opacity-100 transition-opacity md:opacity-0 md:group-hover/item:opacity-100 md:group-focus-within/item:opacity-100">
                    <Button
                      size="sm"
                      prominence="internal"
                      onClick={() =>
                        void act(() =>
                          unarchiveChatSession({
                            path: { sessionId: session.id },
                            signal: AbortSignal.timeout(30000),
                          }),
                        )
                      }
                    >
                      <ArchiveRestore className="size-4" aria-hidden="true" />
                      {ui("Bỏ lưu trữ")}
                    </Button>
                    <ConfirmDialog
                      trigger={
                        <IconButton
                          size="sm"
                          prominence="internal"
                          aria-label={ui("Xoá hội thoại {{name}}", { name: session.title })}
                        >
                          <Trash2 />
                        </IconButton>
                      }
                      title={ui("Xóa hội thoại?")}
                      description={ui(
                        "“{{v1}}” sẽ bị xóa khỏi lịch sử và liên kết chia sẻ. Câu trả lời đang chạy cũng sẽ dừng.",
                        { v1: session.title },
                      )}
                      confirmLabel={ui("Xóa hội thoại")}
                      pendingLabel={ui("Đang xóa…")}
                      confirmTone="danger"
                      errorMessage={chatActionError}
                      onConfirm={() =>
                        act(() =>
                          deleteChatSession({
                            path: { sessionId: session.id },
                            signal: AbortSignal.timeout(30000),
                          }),
                        )
                      }
                    />
                  </ItemActions>
                </Item>
              </li>
            ))}
          </ul>
        )}
        {query.length === 0 && pages.hasNextPage && (
          <Button
            prominence="secondary"
            className="self-start"
            pending={pages.isFetchingNextPage}
            onClick={() => void pages.fetchNextPage()}
          >
            {ui("Tải thêm")}
          </Button>
        )}
      </div>
    </SettingsLayout>
  );
}
