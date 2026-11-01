import { useEffect, useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { History, Search, SquarePen, X } from "lucide-react";
import { groupThreadTitles } from "@/components/assistant-ui/elements/thread-list";
import {
  Command,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { SidebarMenuButton, SidebarMenuItem } from "@/components/ui/sidebar";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  listChatPersonasOptions,
  searchChatSessionsInfiniteOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatSession } from "@/lib/hey-api/types.gen";
import { AgentAvatar } from "@/features/agents/agent-avatar";
import { personaOf } from "@/features/chat/chat-personas-api";
import { projectsOptions } from "@/features/chat/projects/chat-projects-api";
import { ProjectIcon } from "@/features/chat/projects/project-icon";
import { matchingProjects } from "@/features/chat/projects/projects-filter";
import { useAssistantFaces, useThreadGroupLabel, type AssistantFace } from "./assistant-topics";

const PAGE_SIZE = 20;
/** How many assistants, topics and projects a query shows; the history page holds the rest of the topics. */
const GROUP_SIZE = 5;
/** The palette's shortcut as the keyboard in front of the person labels it. */
const SHORTCUT_HINT = /Mac|iPhone|iPad/.test(navigator.platform) ? "⌘K" : "Ctrl K";
// ChatSessionMatch wraps matched tokens in U+E000/U+E001; private-use characters never render.
const MATCH_DELIMITER = new RegExp(
  `[${String.fromCharCode(0xe000)}${String.fromCharCode(0xe001)}]`,
);

type FoundSession = ChatSession & { snippet?: string | null };

/**
 * Search in a command palette, as LobeHub's command menu and ChatGPT's chat search: a blank query lists a new topic
 * and the recent topics by time; a query lists the matching assistants, topics with their matched fragment, and
 * projects, five of each. Opened from an assistant's sidebar it starts within that assistant's topics, a filter the
 * person can remove, as LobeHub passes the active agent. No second thread store.
 */
export function ChatHistorySearch({
  assistant,
  onNavigate,
}: {
  /** The assistant whose sidebar the search is opened from. */
  assistant?: { id: string; name: string };
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [scoped, setScoped] = useState(true);
  // Ctrl/Cmd+K is a window shortcut, so the palette listens on the window while mounted.
  useEffect(() => {
    const shortcut = (event: KeyboardEvent) => {
      // The mobile drawer can mount a second sidebar; the first handler wins.
      if (event.defaultPrevented || event.key.toLowerCase() !== "k") return;
      if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey) return;
      event.preventDefault();
      setOpen(true);
    };
    window.addEventListener("keydown", shortcut);
    return () => window.removeEventListener("keydown", shortcut);
  }, []);
  const [query, setQuery] = useState("");
  const debouncedQuery = useDebouncedValue(query.trim(), 250);
  const waiting = query.trim() !== debouncedQuery;
  const blank = query.trim() === "";
  const within = scoped ? assistant : undefined;
  const results = useInfiniteQuery({
    ...searchChatSessionsInfiniteOptions({
      query: {
        query: debouncedQuery,
        personaId: within?.id,
        limit: debouncedQuery ? GROUP_SIZE : PAGE_SIZE,
      },
    }),
    enabled: open && !waiting,
    initialPageParam: 0,
    getNextPageParam: (last, pages) =>
      last.hasMore && pages.length * PAGE_SIZE <= 10000 ? pages.length * PAGE_SIZE : undefined,
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  const assistants = useQuery({
    ...listChatPersonasOptions({ query: { q: debouncedQuery, limit: GROUP_SIZE } }),
    enabled: open && !waiting && !blank && !within,
    select: (views) => views.map(personaOf),
    retry: false,
  });
  const projects = useQuery({ ...projectsOptions(), enabled: open && !within });
  const faceOf = useAssistantFaces();
  const items = waiting ? [] : (results.data?.pages.flatMap((page) => page.items) ?? []);
  // Live pagination may shift when another tab renames or updates a session.
  const unique: FoundSession[] = [
    ...new Map(
      items.map((item) => [item.session.id, { ...item.session, snippet: item.snippet }]),
    ).values(),
  ];
  const searching = !blank && !waiting;
  const foundAssistants = searching && !within ? (assistants.data ?? []) : [];
  const foundProjects =
    searching && !within
      ? matchingProjects(projects.data ?? [], debouncedQuery).slice(0, GROUP_SIZE)
      : [];
  const nothing =
    searching &&
    !results.isPending &&
    !results.isError &&
    !assistants.isFetching &&
    unique.length === 0 &&
    foundAssistants.length === 0 &&
    foundProjects.length === 0;
  const label = ui("Tìm kiếm");

  function go(to: () => Promise<void>) {
    setOpen(false);
    void to();
    onNavigate?.();
  }
  const openTopic = (sessionId: string) =>
    go(() => navigate({ to: "/chat/$sessionId", params: { sessionId } }));

  return (
    <SidebarMenuItem>
      <Dialog
        open={open}
        onOpenChange={(next) => {
          setOpen(next);
          if (!next) {
            setQuery("");
            setScoped(true);
          }
        }}
      >
        <DialogTrigger asChild>
          <SidebarMenuButton tooltip={label} aria-keyshortcuts="Control+K Meta+K">
            <Search />
            {/* The last span is the row's name; folded to the rail only the icon stays. */}
            <span className="flex min-w-0 flex-1 items-center gap-2">
              <span className="min-w-0 flex-1 truncate">{label}</span>
              {/* aria-keyshortcuts already tells a screen reader; a touch screen has no keyboard to press it on. */}
              <kbd
                aria-hidden="true"
                className="font-sans text-xs text-content-muted pointer-coarse:hidden"
              >
                {SHORTCUT_HINT}
              </kbd>
            </span>
          </SidebarMenuButton>
        </DialogTrigger>
        <DialogContent className="top-[max(1rem,12dvh)] flex max-h-[min(36rem,calc(100dvh-2rem))] translate-y-0 flex-col overflow-hidden sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{label}</DialogTitle>
            <DialogDescription className="sr-only">
              {ui(
                "Tìm trợ lý, dự án, tiêu đề và nội dung mọi phiên bản đã lưu. Kết quả mở ở nhánh hiện tại của hội thoại.",
              )}
            </DialogDescription>
          </DialogHeader>
          <Command shouldFilter={false} label={label} className="min-h-0 flex-1">
            <CommandInput
              value={query}
              onValueChange={setQuery}
              maxLength={200}
              aria-label={within ? ui("Tìm chủ đề") : ui("Tìm trợ lý, chủ đề và dự án")}
              placeholder={within ? ui("Tìm chủ đề…") : ui("Tìm trợ lý, chủ đề và dự án…")}
            />
            {within && (
              <div className="flex px-1 pt-2">
                <Button size="sm" prominence="secondary" onClick={() => setScoped(false)}>
                  {ui("Trong: {{v1}}", { v1: within.name })}
                  <X data-icon="inline-end" aria-hidden="true" />
                  <span className="sr-only">{ui("Tìm ở mọi trợ lý")}</span>
                </Button>
              </div>
            )}
            <CommandList className="max-h-none min-h-0 flex-1">
              {blank ? (
                <CommandItem
                  value="new-topic"
                  onSelect={() =>
                    go(() =>
                      within
                        ? navigate({
                            to: "/assistants/$personaId/new",
                            params: { personaId: within.id },
                          })
                        : navigate({ to: "/" }),
                    )
                  }
                >
                  <SquarePen className="shrink-0 text-content-secondary" aria-hidden="true" />
                  {ui("Chủ đề mới")}
                </CommandItem>
              ) : null}
              {foundAssistants.length > 0 && (
                <CommandGroup heading={ui("Trợ lý")}>
                  {foundAssistants.map((agent) => (
                    <CommandItem
                      key={agent.id}
                      value={`assistant:${agent.id}`}
                      onSelect={() =>
                        go(() =>
                          navigate({
                            to: "/assistants/$personaId",
                            params: { personaId: agent.id },
                          }),
                        )
                      }
                    >
                      <AgentAvatar agent={agent} size="sm" />
                      <span className="min-w-0 flex-1 truncate">{agent.name}</span>
                    </CommandItem>
                  ))}
                </CommandGroup>
              )}
              {waiting || results.isPending ? (
                <p role="status" className="px-3 py-4 text-sm text-content-muted">
                  {blank ? ui("Đang tải chủ đề…") : ui("Đang tìm…")}
                </p>
              ) : results.isError ? (
                <div className="flex flex-col gap-2 px-1 py-2">
                  <Alert variant="destructive">
                    <AlertDescription>{ui("Không tìm được chủ đề. Hãy thử lại.")}</AlertDescription>
                  </Alert>
                  <div>
                    <Button size="sm" onClick={() => void results.refetch()}>
                      {ui("Thử lại")}
                    </Button>
                  </div>
                </div>
              ) : blank ? (
                unique.length === 0 ? (
                  <p role="status" className="px-3 py-4 text-sm text-content-muted">
                    {ui("Chưa có chủ đề.")}
                  </p>
                ) : (
                  <RecentTopics sessions={unique} faceOf={faceOf} onOpen={openTopic} />
                )
              ) : unique.length > 0 ? (
                <CommandGroup heading={ui("Chủ đề")}>
                  {unique.slice(0, GROUP_SIZE).map((session) => (
                    <FoundTopic
                      key={session.id}
                      session={session}
                      face={faceOf(session.personaId)}
                      onOpen={() => openTopic(session.id)}
                    />
                  ))}
                  {results.data?.pages.at(-1)?.hasMore && (
                    <CommandItem
                      value="all-topics"
                      onSelect={() =>
                        go(() =>
                          navigate({
                            to: "/history",
                            search: { q: debouncedQuery, assistant: within?.id },
                          }),
                        )
                      }
                    >
                      <History className="shrink-0 text-content-secondary" aria-hidden="true" />
                      {ui("Xem tất cả chủ đề khớp")}
                    </CommandItem>
                  )}
                </CommandGroup>
              ) : null}
              {foundProjects.length > 0 && (
                <CommandGroup heading={ui("Dự án")}>
                  {foundProjects.map((project) => (
                    <CommandItem
                      key={project.id}
                      value={`project:${project.id}`}
                      onSelect={() =>
                        go(() =>
                          navigate({
                            to: "/projects/$projectId",
                            params: { projectId: project.id },
                          }),
                        )
                      }
                    >
                      <ProjectIcon iconName={project.iconName} />
                      <span className="min-w-0 flex-1 truncate">{project.name}</span>
                    </CommandItem>
                  ))}
                </CommandGroup>
              )}
              {nothing && (
                <p role="status" className="px-3 py-4 text-sm text-content-muted">
                  {ui("Không có kết quả phù hợp.")}
                </p>
              )}
              {blank && !waiting && results.hasNextPage ? (
                <div className="px-1 pt-1">
                  <Button
                    size="sm"
                    prominence="internal"
                    pending={results.isFetchingNextPage}
                    onClick={() => void results.fetchNextPage()}
                  >
                    {ui("Xem thêm chủ đề")}
                  </Button>
                </div>
              ) : null}
            </CommandList>
          </Command>
        </DialogContent>
      </Dialog>
    </SidebarMenuItem>
  );
}

/** The recent topics under their time bucket. */
function RecentTopics({
  sessions,
  faceOf,
  onOpen,
}: {
  sessions: FoundSession[];
  faceOf: (personaId: string) => AssistantFace;
  onOpen: (sessionId: string) => void;
}) {
  const groupLabel = useThreadGroupLabel();
  return groupThreadTitles(sessions).map((group) => (
    <CommandGroup key={group.key} heading={groupLabel(group)}>
      {group.items.map((session) => (
        <FoundTopic
          key={session.id}
          session={session}
          face={faceOf(session.personaId)}
          onOpen={() => onOpen(session.id)}
        />
      ))}
    </CommandGroup>
  ));
}

/** A topic with its assistant's face, the matched fragment and its time. */
function FoundTopic({
  session,
  face,
  onOpen,
}: {
  session: FoundSession;
  face: AssistantFace;
  onOpen: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <CommandItem value={session.id} className="items-start" onSelect={onOpen}>
      <AgentAvatar agent={face} size="sm" />
      <span className="min-w-0 flex-1">
        <span className="flex min-w-0 items-center gap-1.5">
          <span
            className="min-w-0 truncate font-main-ui-body text-content-primary"
            title={session.title}
          >
            {session.title}
          </span>
          {session.archivedAt ? (
            <Badge variant="secondary" className="shrink-0">
              {ui("Đã lưu trữ")}
            </Badge>
          ) : null}
        </span>
        {session.snippet ? (
          <span className="mt-0.5 line-clamp-2 text-xs text-content-secondary">
            <MatchSnippet text={session.snippet} />
          </span>
        ) : null}
      </span>
      <time
        dateTime={session.updatedAt}
        className="shrink-0 pt-0.5 text-xs whitespace-nowrap text-content-muted tabular-nums"
      >
        {sessionTime(session.updatedAt)}
      </time>
    </CommandItem>
  );
}

function MatchSnippet({ text }: { text: string }) {
  // Odd parts are the tokens the server matched; everything is rendered as text, never HTML.
  return text
    .replace(/\s+/g, " ")
    .split(MATCH_DELIMITER)
    .map((part, index) =>
      index % 2 === 1 ? (
        <mark
          key={index}
          className="rounded-sm bg-evidence-highlight-surface box-decoration-clone font-medium text-content-primary"
        >
          {part}
        </mark>
      ) : (
        part
      ),
    );
}

function sessionTime(value: string, now = new Date()) {
  const date = new Date(value);
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  // Today and Yesterday groups already name the day, so their rows show the time.
  if (date >= yesterday) {
    return formatUiDate(date, { hour: "2-digit", minute: "2-digit" });
  }
  return formatUiDate(date, {
    day: "numeric",
    month: "short",
    ...(date.getFullYear() === now.getFullYear() ? {} : { year: "numeric" }),
  });
}
