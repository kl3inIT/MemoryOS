// Adapted from assistant-ui's controlled ThreadList (MIT), revision 2c22f5d7.
// Application routing, stable IDs and real action controls replace the demo spans.
import type { DragEventHandler, ReactNode } from "react";
import { cn } from "@/lib/utils";

// assistant-ui ThreadList's Today, Yesterday, Earlier buckets, adapted to the existing
// server session list (no second thread runtime). Searching is server-side, not a title filter.
export function groupThreadTitles<T extends { title: string; updatedAt: string }>(
  items: readonly T[],
  now = new Date(),
) {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  const groups: { label: "today" | "yesterday" | "earlier"; items: T[] }[] = [];
  for (const item of items.toSorted((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))) {
    const time = Date.parse(item.updatedAt);
    const label =
      time >= today.getTime() ? "today" : time >= yesterday.getTime() ? "yesterday" : "earlier";
    const last = groups.at(-1);
    if (last?.label === label) last.items.push(item);
    else groups.push({ label, items: [item] });
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
      onDragStart={onDragStart}
      className={cn(
        "group flex min-w-0 items-center gap-1 rounded-lg px-1 text-sm hover:bg-surface-sunken focus-within:bg-surface-sunken",
        selected && "bg-surface-sunken",
      )}
    >
      <div className="min-w-0 flex-1">{children}</div>
      {actions && (
        <div className="shrink-0 opacity-100 md:opacity-0 md:group-hover:opacity-100 md:group-focus-within:opacity-100 has-[[data-state=open]]:opacity-100">
          {actions}
        </div>
      )}
    </div>
  );
}
