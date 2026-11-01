import { useEffect, useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { History, SearchX, WifiOff } from "lucide-react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { BrandLoader } from "@/components/brand-loader";
import { EmptyState } from "@/components/composites/empty-state";
import { SearchField } from "@/components/composites/search-field";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  listChatSessionsInfiniteOptions,
  searchChatSessionsInfiniteOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { AgentAvatar } from "@/features/agents/agent-avatar";
import { useAssistantFaces, useSidebarAssistants, useTopicTime } from "./assistant-topics";
import { ChatSessionMenu } from "./chat-session-menu";
import { ReplyRunning } from "./chat-session-row";

const PAGE_SIZE = 30;

type Topic = ChatSession & { snippet?: string | null };

/**
 * Every topic of the person, newest first (ChatGPT's chat history): a search over titles and messages and a filter
 * by assistant, both kept in the address. Archived topics stay on their own settings page.
 */
export function TopicHistoryPage() {
  const ui = useAppTranslation();
  const navigate = useNavigate({ from: "/history" });
  const { assistant, q = "" } = useSearch({ from: "/_authenticated/history" });
  const [text, setText] = useState(q);
  const [shownQ, setShownQ] = useState(q);
  // Back or a link changes the address; the box follows it.
  if (shownQ !== q) {
    setShownQ(q);
    if (text.trim() !== q) setText(q);
  }
  const settled = useDebouncedValue(text.trim(), 250);
  useEffect(() => {
    if (settled !== q)
      void navigate({
        search: (current) => ({ ...current, q: settled || undefined }),
        replace: true,
        resetScroll: false,
      });
    // Only a settled change of the box writes the address; a change of the address sets the box above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settled]);

  const assistants = useSidebarAssistants();
  const faceOf = useAssistantFaces();
  const topicTime = useTopicTime();
  const searching = q !== "";
  const found = useInfiniteQuery({
    ...searchChatSessionsInfiniteOptions({
      query: { query: q, personaId: assistant, limit: PAGE_SIZE },
    }),
    initialPageParam: 0,
    getNextPageParam: (last, pages) =>
      last.hasMore && pages.length * PAGE_SIZE <= 10000 ? pages.length * PAGE_SIZE : undefined,
    enabled: searching,
  });
  const listed = useInfiniteQuery({
    ...listChatSessionsInfiniteOptions({
      query: { personaId: assistant, archived: false, limit: PAGE_SIZE },
    }),
    initialPageParam: 0,
    getNextPageParam: (last, pages) =>
      last.length === PAGE_SIZE && pages.length * PAGE_SIZE <= 10000
        ? pages.length * PAGE_SIZE
        : undefined,
    enabled: !searching,
  });
  const results = searching ? found : listed;
  const topics: Topic[] = searching
    ? (found.data?.pages.flatMap((page) =>
        page.items.map((item) => ({ ...item.session, snippet: item.snippet })),
      ) ?? [])
    : (listed.data?.pages.flat() ?? []);
  const unique = [...new Map(topics.map((topic) => [topic.id, topic])).values()];
  const filtered = searching || assistant !== undefined;
  // The chosen assistant stays in the list even when it is not on the sidebar.
  const choices = assistants.data?.map((row) => ({ id: row.id, name: row.name })) ?? [];
  if (assistant && !choices.some((choice) => choice.id === assistant))
    choices.push({ id: assistant, name: faceOf(assistant).name || ui("Trợ lý khác") });

  return (
    <>
      <AppShellHeader title={ui("Lịch sử hội thoại")} pageHeader />
      <SettingsLayout className="gap-6">
        <PageHeader icon={<History />} title={ui("Lịch sử hội thoại")} />
        <div className="flex flex-col gap-2 sm:flex-row">
          <SearchField
            className="min-w-0 flex-1"
            value={text}
            onChange={setText}
            label={ui("Tìm trong tiêu đề và tin nhắn")}
          />
          <div className="shrink-0 sm:w-60">
            <NativeSelect
              aria-label={ui("Lọc theo trợ lý")}
              value={assistant ?? ""}
              onChange={(event) =>
                void navigate({
                  search: (current) => ({ ...current, assistant: event.target.value || undefined }),
                  replace: true,
                })
              }
            >
              <option value="">{ui("Mọi trợ lý")}</option>
              {choices.map((choice) => (
                <option key={choice.id} value={choice.id}>
                  {choice.name}
                </option>
              ))}
            </NativeSelect>
          </div>
        </div>
        {results.isPending && (
          <div role="status" className="flex justify-center py-16">
            <BrandLoader label={ui("Đang tải chủ đề…")} />
          </div>
        )}
        {results.isError && (
          <EmptyState
            role="alert"
            icon={<WifiOff />}
            title={ui("Không tải được chủ đề.")}
            action={
              <Button size="sm" prominence="secondary" onClick={() => void results.refetch()}>
                {ui("Tải lại")}
              </Button>
            }
          />
        )}
        {results.isSuccess && unique.length === 0 && (
          <EmptyState
            icon={filtered ? <SearchX /> : <History />}
            title={filtered ? ui("Không có chủ đề nào khớp") : ui("Chưa có chủ đề.")}
            action={
              filtered ? (
                <Button
                  size="sm"
                  prominence="secondary"
                  onClick={() => {
                    setText("");
                    void navigate({ search: {}, replace: true });
                  }}
                >
                  {ui("Xóa bộ lọc")}
                </Button>
              ) : undefined
            }
          />
        )}
        {unique.length > 0 && (
          <ul className="flex flex-col divide-y divide-border-subtle overflow-hidden rounded-xl border border-border-subtle">
            {unique.map((topic) => {
              const face = faceOf(topic.personaId);
              return (
                <li
                  key={topic.id}
                  className="group flex min-w-0 items-center gap-1 pr-2 hover:bg-surface-subtle"
                >
                  <Link
                    to="/chat/$sessionId"
                    params={{ sessionId: topic.id }}
                    className="flex min-w-0 flex-1 items-center gap-3 px-3 py-2.5 outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                  >
                    <AgentAvatar agent={face} size="row" />
                    <span className="flex min-w-0 flex-1 flex-col">
                      <span className="truncate font-medium text-content-primary">
                        {topic.title}
                      </span>
                      <span className="truncate text-xs text-content-muted">
                        {[face.name, topic.snippet?.replace(/[]/g, "")]
                          .filter(Boolean)
                          .join(" · ")}
                      </span>
                    </span>
                    {topic.replyRunning && <ReplyRunning />}
                    <time
                      dateTime={topic.updatedAt}
                      className="shrink-0 text-xs text-content-muted tabular-nums"
                    >
                      {topicTime(topic.updatedAt)}
                    </time>
                  </Link>
                  <ChatSessionMenu session={topic} />
                </li>
              );
            })}
          </ul>
        )}
        {results.hasNextPage && (
          <div className="flex justify-center">
            <Button
              size="sm"
              prominence="secondary"
              pending={results.isFetchingNextPage}
              onClick={() => void results.fetchNextPage()}
            >
              {ui("Xem thêm chủ đề")}
            </Button>
          </div>
        )}
      </SettingsLayout>
    </>
  );
}
