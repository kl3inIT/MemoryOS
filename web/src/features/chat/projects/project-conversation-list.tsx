import { useAppTranslation } from "@/i18n/use-app-translation";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { ThreadList } from "@/components/assistant-ui/elements/thread-list";
import { listProjectChatSessionsInfiniteOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { ChatSessionRow } from "@/features/chat/session/chat-session-row";

/** The project page shows a handful of recent conversations; "Xem thêm" loads the next five. */
const PROJECT_SESSIONS_PAGE = 5;

export function ProjectConversationList({ projectId }: { projectId: string }) {
  const ui = useAppTranslation();
  const sessions = useInfiniteQuery({
    ...listProjectChatSessionsInfiniteOptions({
      path: { projectId },
      query: { limit: PROJECT_SESSIONS_PAGE },
    }),
    initialPageParam: 0,
    getNextPageParam: (last, pages) =>
      last.length === PROJECT_SESSIONS_PAGE && pages.length * PROJECT_SESSIONS_PAGE <= 10000
        ? pages.length * PROJECT_SESSIONS_PAGE
        : undefined,
  });
  return (
    <section className="mt-6" aria-label={ui("Hội thoại trong dự án")}>
      <h2 className="mb-3 text-sm font-medium text-content-secondary">{ui("Hội thoại gần đây")}</h2>
      {sessions.isPending && (
        <p role="status" className="px-2 py-2 text-sm text-content-muted">
          {ui("Đang tải hội thoại…")}
        </p>
      )}
      {sessions.isError && (
        <p role="alert" className="text-sm">
          {ui("Không tải được hội thoại.")}{" "}
          <Button size="sm" onClick={() => void sessions.refetch()}>
            {ui("Tải lại")}
          </Button>
        </p>
      )}
      <ThreadList label={ui("Hội thoại dự án")}>
        {sessions.data?.pages.flat().map((session) => (
          <ChatSessionRow key={session.id} session={session} showTime />
        ))}
      </ThreadList>
      {sessions.data?.pages[0]?.length === 0 && (
        <p className="px-2 py-3 text-sm text-content-muted">
          {ui("Chưa có hội thoại. Gửi câu hỏi ở trên để bắt đầu.")}
        </p>
      )}
      {sessions.hasNextPage && (
        <Button
          size="sm"
          prominence="internal"
          pending={sessions.isFetchingNextPage}
          onClick={() => void sessions.fetchNextPage()}
        >
          {ui("Xem thêm hội thoại")}
        </Button>
      )}
    </section>
  );
}
