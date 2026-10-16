import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Compass, GripVertical, MoreHorizontal, PinOff, Plus } from "lucide-react";
import { useState, type ReactNode } from "react";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { SortableList } from "@/components/composites/sortable-list";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { AgentAvatar } from "@/features/agents/agent-avatar";
import { usePinUpdates } from "@/features/agents/agent-pins";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import { actionErrorText } from "@/lib/action-errors";
import { listChatAssistantsQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatAssistant } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { assistantLink, useSidebarAssistants, useTopicTime } from "./assistant-topics";
import { SidebarSection } from "./sidebar-section";
import {
  ASSISTANT_ROW_LIMITS,
  useAssistantRowLimit,
  type AssistantRowLimit,
} from "./sidebar-preferences";

/**
 * A pin's drag handle and unpin button appear on hover. A touch screen cannot hover and the row has no room for them
 * beside its time, so there a pin is removed from the assistants page and the order is kept as it is.
 */
const pinControls = "no-hover:hidden";

/**
 * The sidebar shows only some pins, so a reorder of the shown ones puts them into the places they held among all
 * pins and leaves the others where they are, as the Projects section does.
 */
function placeShown<T>(all: readonly T[], shown: readonly T[], id: (item: T) => string) {
  const moved = new Set(shown.map(id));
  let taken = 0;
  return all.map((item) => (moved.has(id(item)) ? (shown[taken++] ?? item) : item));
}

/**
 * The person's assistants: MemoryOS first, then the pins in their order. Each row opens its latest topic and shows
 * when it was written, its last line and a turning ring while an answer runs (Rakazo, Hermes Bot Chats). A pin whose
 * assistant is gone stays, marked, until it is unpinned.
 */
export function AssistantsSection({
  openAssistant,
  onNavigate,
}: {
  openAssistant?: string;
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const assistants = useSidebarAssistants();
  const canCreate = useGlobalCapability("AGENTS_CREATE");
  const updatePins = usePinUpdates();
  const reorder = useMutation({
    mutationFn: ({ change }: { next: ChatAssistant[]; change: (current: string[]) => string[] }) =>
      updatePins(change),
    // Shows the new order at once; the built-in assistant keeps the first row.
    onMutate: ({ next }) =>
      cache.setQueryData<ChatAssistant[]>(listChatAssistantsQueryKey(), (rows) => [
        ...(rows ?? []).filter((row) => row.builtin),
        ...next,
      ]),
    onError: () => cache.invalidateQueries({ queryKey: listChatAssistantsQueryKey() }),
  });
  const rows = assistants.data ?? [];
  const builtin = rows.filter((row) => row.builtin);
  const pinned = rows.filter((row) => !row.builtin);
  // LobeHub lists five agents, then more on request; the open assistant and one answering always keep their row.
  const [limit, setLimit] = useAssistantRowLimit("memoryos:chat-assistants-rows");
  const [expanded, setExpanded] = useState(false);
  const collapsed = pinned.filter(
    (assistant, index) =>
      index < limit - builtin.length ||
      assistant.id === openAssistant ||
      assistant.latestTopic?.replyRunning === true,
  );
  const hidden = pinned.length - collapsed.length;
  const shownPins = expanded ? pinned : collapsed;
  const reorderShown = (next: ChatAssistant[]) =>
    reorder.mutate({
      next: placeShown(pinned, next, (assistant) => assistant.id),
      change: (current) =>
        placeShown(
          current,
          next.map((assistant) => assistant.id),
          (id) => id,
        ),
    });
  const unpin = (assistant: ChatAssistant) =>
    reorder.mutate({
      next: pinned.filter((item) => item.id !== assistant.id),
      change: (current) => current.filter((id) => id !== assistant.id),
    });

  return (
    <SidebarSection
      id="sidebar-assistants"
      title={ui("Trợ lý")}
      storageKey="memoryos:chat-assistants-section"
      className="my-3"
      action={
        // LobeHub's agent section header: its menu, "view all agents" and create, each named by a tooltip.
        <TooltipProvider>
          <span className="flex items-center">
            <DropdownMenu>
              <HeaderTip label={ui("Tùy chọn danh sách trợ lý")}>
                <DropdownMenuTrigger asChild>
                  <IconButton
                    size="sm"
                    prominence="internal"
                    aria-label={ui("Tùy chọn danh sách trợ lý")}
                  >
                    <MoreHorizontal />
                  </IconButton>
                </DropdownMenuTrigger>
              </HeaderTip>
              <DropdownMenuContent align="end" className="w-auto min-w-36">
                <DropdownMenuLabel>{ui("Hiển thị")}</DropdownMenuLabel>
                <DropdownMenuRadioGroup
                  value={`${limit}`}
                  onValueChange={(value) => setLimit(Number(value) as AssistantRowLimit)}
                >
                  {ASSISTANT_ROW_LIMITS.map((count) => (
                    <DropdownMenuRadioItem
                      key={count}
                      value={`${count}`}
                      className="cursor-pointer"
                    >
                      {ui("{{v1}} trợ lý", { v1: count })}
                    </DropdownMenuRadioItem>
                  ))}
                </DropdownMenuRadioGroup>
              </DropdownMenuContent>
            </DropdownMenu>
            <HeaderTip label={ui("Khám phá trợ lý")}>
              <IconButton
                size="sm"
                prominence="internal"
                aria-label={ui("Khám phá trợ lý")}
                asChild
              >
                <Link to="/agents" onClick={onNavigate}>
                  <Compass />
                </Link>
              </IconButton>
            </HeaderTip>
            {canCreate && (
              <HeaderTip label={ui("Tạo trợ lý")}>
                <IconButton size="sm" prominence="internal" aria-label={ui("Tạo trợ lý")} asChild>
                  <Link to="/agents/create" onClick={onNavigate}>
                    <Plus />
                  </Link>
                </IconButton>
              </HeaderTip>
            )}
          </span>
        </TooltipProvider>
      }
    >
      {assistants.isPending && (
        <p role="status" className="px-2 py-1 text-sm text-content-muted">
          {ui("Đang tải trợ lý…")}
        </p>
      )}
      {assistants.isError && (
        <Button size="sm" prominence="internal" onClick={() => void assistants.refetch()}>
          {ui("Tải lại trợ lý")}
        </Button>
      )}
      {/* Rows, not list items: the drag context puts its announcements beside them. */}
      <div className="flex flex-col gap-0.5">
        {builtin.map((assistant) => (
          <div key={assistant.id} className="group relative rounded-lg">
            <AssistantRow
              assistant={assistant}
              selected={openAssistant === assistant.id}
              onNavigate={onNavigate}
            />
          </div>
        ))}
        <SortableList
          items={shownPins}
          getId={(assistant) => assistant.id}
          onReorder={reorderShown}
        >
          {(assistant, handle) => (
            <div
              ref={handle.setNodeRef}
              style={handle.style}
              data-assistant-id={assistant.id}
              className={cn(
                "group relative rounded-lg",
                handle.dragging && "z-10 bg-surface-sunken",
              )}
            >
              <button
                ref={handle.setHandleRef}
                type="button"
                aria-label={ui("Kéo để sắp xếp {{v1}}", { v1: assistant.name })}
                className={cn(
                  "absolute top-1/2 -left-1 grid h-8 w-4 -translate-y-1/2 cursor-grab place-items-center text-content-muted outline-none focus-visible:opacity-100 active:cursor-grabbing",
                  hoverReveal,
                  pinControls,
                )}
                {...handle.attributes}
              >
                <GripVertical aria-hidden="true" className="size-3.5" />
              </button>
              <AssistantRow
                assistant={assistant}
                selected={openAssistant === assistant.id}
                onNavigate={onNavigate}
              />
              <span
                className={cn(
                  "absolute top-1/2 right-0.5 -translate-y-1/2",
                  hoverReveal,
                  pinControls,
                )}
              >
                <IconButton
                  size="sm"
                  prominence="internal"
                  aria-label={ui("Bỏ ghim {{v1}}", { v1: assistant.name })}
                  onClick={() => unpin(assistant)}
                >
                  <PinOff />
                </IconButton>
              </span>
            </div>
          )}
        </SortableList>
      </div>
      {hidden > 0 && (
        <button
          type="button"
          aria-expanded={expanded}
          onClick={() => setExpanded(!expanded)}
          className="mt-0.5 flex h-8 w-full cursor-pointer items-center rounded-lg px-2 text-left text-sm text-content-muted outline-none hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-2 focus-visible:ring-ring"
        >
          {expanded ? ui("Thu gọn") : ui("Xem thêm ({{v1}})", { v1: hidden })}
        </button>
      )}
      {reorder.error && (
        <p role="alert" className="px-2 text-xs text-status-danger-content">
          {actionErrorText(reorder.error)}
        </p>
      )}
    </SidebarSection>
  );
}

/** One assistant: its face, name and last time above the last line of its latest topic. */
function AssistantRow({
  assistant,
  selected,
  onNavigate,
}: {
  assistant: ChatAssistant;
  selected: boolean;
  onNavigate?: () => void;
}) {
  const ui = useAppTranslation();
  const topicTime = useTopicTime();
  const topic = assistant.latestTopic;
  const running = topic?.replyRunning === true;
  const line = !assistant.usable
    ? ui("Không còn dùng được")
    : running
      ? ui("Đang trả lời…")
      : topic?.preview || (topic ? topic.title : ui("Chưa có chủ đề"));
  const content = (
    <>
      <span className="relative shrink-0">
        <AgentAvatar
          agent={assistant}
          size="row"
          className={cn(!assistant.usable && "opacity-60 grayscale")}
        />
        {running && (
          <span
            aria-hidden="true"
            // A square ring breathes rather than spins: a turning square swings its corners out.
            className="absolute -inset-1 animate-pulse rounded-xl border-2 border-status-info-content motion-reduce:animate-none"
          />
        )}
      </span>
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="flex min-w-0 items-baseline gap-2">
          <span className="min-w-0 flex-1 truncate font-medium text-content-primary">
            {assistant.name}
          </span>
          {topic && (
            <time
              dateTime={topic.lastMessageAt}
              className="shrink-0 text-xs text-content-muted tabular-nums group-focus-within:invisible group-hover:invisible no-hover:visible"
            >
              {topicTime(topic.lastMessageAt)}
            </time>
          )}
        </span>
        <span
          className={cn(
            "truncate text-xs",
            running ? "font-medium text-status-info-content" : "text-content-muted",
          )}
        >
          {line}
        </span>
      </span>
    </>
  );
  const className = cn(
    "flex min-w-0 items-center gap-2.5 rounded-lg px-2 py-1 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring",
    selected ? "bg-surface-sunken" : "hover:bg-surface-subtle",
  );
  // A gone assistant without a topic has nothing to open.
  if (!assistant.usable && !topic) return <div className={cn(className, "pr-8")}>{content}</div>;
  return (
    <Link
      {...assistantLink(assistant)}
      onClick={onNavigate}
      aria-current={selected ? "true" : undefined}
      className={className}
    >
      {content}
    </Link>
  );
}

/** An icon button of the section header with its name as a tooltip. */
function HeaderTip({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>{children}</TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}
