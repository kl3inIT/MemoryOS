import { useState } from "react";
import { useInfiniteQuery, useMutation, useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Archive, ArchiveRestore, Search, Trash2 } from "lucide-react";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
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
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Item, ItemActions, ItemContent, ItemDescription } from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { actionErrorText } from "@/lib/action-errors";
import {
  deleteChatSessionMutation,
  listChatSessionsInfiniteOptions,
  searchChatSessionsOptions,
  unarchiveChatSessionMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";

const PAGE = 30;

/**
 * The conversations the owner archived (MEM-153): out of the sidebar, still theirs. Following ChatGPT's archived
 * chats, each row only offers what an archived conversation can do — open it, put it back, or delete it — and a
 * search asks the server rather than filtering the page that happens to be loaded.
 */
export function ChatArchivedSessionsPage() {
  const ui = useAppTranslation();
  const refreshSessions = useRefreshChatSessions();
  const [search, setSearch] = useState("");
  const query = useDebouncedValue(search.trim(), 300);

  const pages = useInfiniteQuery({
    ...listChatSessionsInfiniteOptions({ query: { archived: true, limit: PAGE } }),
    initialPageParam: 0,
    getNextPageParam: (last, all) => (last.length === PAGE ? all.length * PAGE : undefined),
    enabled: query.length === 0,
  });
  const matches = useQuery({
    ...searchChatSessionsOptions({ query: { query, limit: PAGE } }),
    // A search reads every conversation the owner has; this page shows the archived ones.
    select: (found) =>
      found.items.filter((item) => item.session.archivedAt).map((item) => item.session),
    enabled: query.length > 0,
  });
  const unarchive = useMutation({
    ...unarchiveChatSessionMutation(),
    onSuccess: (_session, { path }) => refreshSessions(path.sessionId),
  });
  const remove = useMutation({
    ...deleteChatSessionMutation(),
    onSuccess: (_answer, { path }) => refreshSessions(path.sessionId),
  });

  const sessions: ChatSession[] =
    query.length > 0 ? (matches.data ?? []) : (pages.data?.pages.flat() ?? []);
  const loading = query.length > 0 ? matches.isPending : pages.isPending;
  const failed = query.length > 0 ? matches.isError : pages.isError;

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
        <InputGroup>
          <InputGroupAddon>
            <Search aria-hidden="true" />
          </InputGroupAddon>
          <InputGroupInput
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder={ui("Tìm trong hội thoại đã lưu trữ")}
            aria-label={ui("Tìm trong hội thoại đã lưu trữ")}
            maxLength={200}
          />
        </InputGroup>
        {unarchive.isError && (
          <Alert variant="destructive">
            <AlertTitle>{actionErrorText(unarchive.error)}</AlertTitle>
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
              <Skeleton key={row} className="h-14 w-full" />
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
          <ul className="flex flex-col">
            {sessions.map((session) => (
              <ArchivedSessionRow
                key={session.id}
                session={session}
                restoring={
                  unarchive.isPending && unarchive.variables?.path.sessionId === session.id
                }
                onRestore={() => {
                  remove.reset();
                  unarchive.mutate({
                    path: { sessionId: session.id },
                    signal: AbortSignal.timeout(30000),
                  });
                }}
                onDelete={async () => {
                  unarchive.reset();
                  await remove.mutateAsync({
                    path: { sessionId: session.id },
                    signal: AbortSignal.timeout(30000),
                  });
                }}
              />
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

function ArchivedSessionRow({
  session,
  restoring,
  onRestore,
  onDelete,
}: {
  session: ChatSession;
  restoring: boolean;
  onRestore: () => void;
  onDelete: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  return (
    <li className="group border-b border-border-subtle last:border-b-0">
      <Item variant="default">
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
        <ItemActions>
          <div className={cn("flex items-center gap-1", hoverReveal)}>
            <Button size="sm" prominence="internal" pending={restoring} onClick={onRestore}>
              <ArchiveRestore data-icon="inline-start" aria-hidden="true" />
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
              errorMessage={actionErrorText}
              onConfirm={onDelete}
            />
          </div>
        </ItemActions>
      </Item>
    </li>
  );
}
