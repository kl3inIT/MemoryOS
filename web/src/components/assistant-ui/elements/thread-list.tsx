// Adapted from assistant-ui's controlled ThreadList (MIT), revision 2c22f5d7.
// Application routing, stable IDs and real action controls replace the demo spans.
import type { DragEventHandler, ReactNode } from "react";
import { cn } from "@/lib/utils";

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
