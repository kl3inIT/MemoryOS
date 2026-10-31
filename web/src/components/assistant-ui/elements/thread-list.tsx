// Adapted from assistant-ui's controlled ThreadList (MIT), revision 2c22f5d7.
// Application routing, stable IDs and real action controls replace the demo spans.
import type { DragEventHandler, ReactNode } from "react";
import { cn } from "@/lib/utils";

/**
 * A time bucket of conversations, by last activity, as LobeHub groups topics (`packages/utils/src/client/topic.ts`):
 * today, yesterday, the last seven days, the rest of this month, then each earlier month of this year, then each
 * earlier year. `month` and `year` carry the first day of the period they name.
 */
export type ThreadTimeGroup =
  | { kind: "today" | "yesterday" | "week" | "thisMonth"; key: string }
  | { kind: "month" | "year"; key: string; start: Date };

function threadTimeGroup(time: Date, now: Date): ThreadTimeGroup {
  const day = (offset: number) =>
    new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset).getTime();
  const value = time.getTime();
  if (value >= day(0)) return { kind: "today", key: "today" };
  if (value >= day(-1)) return { kind: "yesterday", key: "yesterday" };
  if (value > day(0) - 7 * 86_400_000) return { kind: "week", key: "week" };
  if (time.getFullYear() === now.getFullYear() && time.getMonth() === now.getMonth())
    return { kind: "thisMonth", key: "thisMonth" };
  if (time.getFullYear() === now.getFullYear())
    return {
      kind: "month",
      key: `month:${time.getMonth()}`,
      start: new Date(time.getFullYear(), time.getMonth(), 1),
    };
  return {
    kind: "year",
    key: `year:${time.getFullYear()}`,
    start: new Date(time.getFullYear(), 0, 1),
  };
}

/** Conversations newest first under their time bucket. Searching is server-side, not a title filter. */
export function groupThreadTitles<T extends { updatedAt: string }>(
  items: readonly T[],
  now = new Date(),
) {
  const groups: (ThreadTimeGroup & { items: T[] })[] = [];
  for (const item of items.toSorted((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))) {
    const group = threadTimeGroup(new Date(item.updatedAt), now);
    const last = groups.at(-1);
    if (last?.key === group.key) last.items.push(item);
    else groups.push({ ...group, items: [item] });
  }
  return groups;
}

export function ThreadList({
  children,
  className,
  label,
}: {
  children: ReactNode;
  className?: string;
  label: string;
}) {
  return (
    <div
      data-slot="thread-list"
      role="group"
      aria-label={label}
      className={cn("flex w-full min-w-0 flex-col gap-0.5", className)}
    >
      {children}
    </div>
  );
}

export function ThreadListRow({
  children,
  actions,
  selected,
  onDragStart,
}: {
  children: ReactNode;
  actions?: ReactNode;
  selected?: boolean;
  onDragStart?: DragEventHandler<HTMLDivElement>;
}) {
  return (
    <div
      data-slot="thread-list-row"
      draggable={!!onDragStart}
      // A dialog the row's menu opens is in its React tree, so the drag of text selected there bubbles here.
      onDragStart={
        onDragStart &&
        ((event) => {
          if (event.currentTarget.contains(event.target as Node)) onDragStart(event);
        })
      }
      className={cn(
        "group relative flex min-w-0 items-center gap-1 rounded-lg px-1 text-sm focus-within:bg-surface-sunken hover:bg-surface-sunken",
        selected && "bg-surface-sunken",
      )}
    >
      {/* On a pointer screen the hidden menu takes no room, so the title runs to the row's end, as ChatGPT's rows;
          shown, it covers the row's end and the title makes way. */}
      <div
        className={cn(
          "min-w-0 flex-1",
          actions &&
            "md:group-focus-within:pr-7 md:group-hover:pr-7 md:group-has-[[data-state=open]]:pr-7",
        )}
      >
        {children}
      </div>
      {actions && (
        <div className="shrink-0 opacity-100 has-[[data-state=open]]:opacity-100 md:absolute md:inset-y-0 md:right-1 md:flex md:items-center md:opacity-0 md:group-focus-within:opacity-100 md:group-hover:opacity-100">
          {actions}
        </div>
      )}
    </div>
  );
}
