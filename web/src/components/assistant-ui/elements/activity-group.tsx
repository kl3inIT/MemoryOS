"use client";

// assistant-ui elements-tool-group and elements-reasoning (MIT). Adaptations: one disclosure for
// reasoning and tool steps, radix-ui Collapsible and ShimmerLabel from this kit, a caller-provided
// localized header in the Onyx timeline shape ("Thought for 14s" · "3 steps"), and a step row
// with running/done/failed state.
import { useCallback, useRef, useState, type ComponentProps, type ReactNode } from "react";
import { ChevronDown, CircleAlert, LoaderCircle } from "lucide-react";
import { Collapsible } from "radix-ui";
import { useScrollLock } from "@assistant-ui/react";
import { cn } from "@/lib/utils";
import { field, ShimmerLabel } from "./surfaces";

const ANIMATION_DURATION = 200;

export function ActivityGroupRoot({
  onOpenChange,
  className,
  ...props
}: ComponentProps<typeof Collapsible.Root>) {
  const ref = useRef<HTMLDivElement>(null);
  const lockScroll = useScrollLock(ref, ANIMATION_DURATION);
  const change = useCallback(
    (open: boolean) => {
      lockScroll();
      onOpenChange?.(open);
    },
    [lockScroll, onOpenChange],
  );
  return (
    <Collapsible.Root
      ref={ref}
      data-slot="activity-group"
      onOpenChange={change}
      className={cn("group/activity mb-3 w-full min-w-0", className)}
      {...props}
    />
  );
}

/** The whole header row toggles; the step count sits at the end like a tertiary button. */
export function ActivityGroupTrigger({
  label,
  active,
  steps,
  className,
  ...props
}: Omit<ComponentProps<typeof Collapsible.Trigger>, "children"> & {
  label: string;
  active: boolean;
  steps?: string;
}) {
  return (
    <Collapsible.Trigger
      data-slot="activity-group-trigger"
      className={cn(
        "group/trigger flex w-full min-w-0 items-center justify-between gap-3 rounded-md py-1 text-left text-sm text-content-muted transition-colors hover:text-content-primary focus-visible:outline-2 focus-visible:outline-ring",
        className,
      )}
      {...props}
    >
      <span className="flex min-w-0 items-center gap-2">
        {active && (
          <LoaderCircle
            aria-hidden="true"
            className="size-3.5 shrink-0 animate-spin motion-reduce:animate-none"
          />
        )}
        <span aria-live="polite" className="min-w-0 truncate">
          {active ? (
            <ShimmerLabel key={label} className="relative inline-block leading-5">
              {label}
            </ShimmerLabel>
          ) : (
            label
          )}
        </span>
      </span>
      <span className="inline-flex shrink-0 items-center gap-1 rounded-md px-1.5 py-0.5 text-xs transition-colors group-hover/trigger:bg-surface-sunken">
        {steps}
        <ChevronDown
          aria-hidden="true"
          className="size-3.5 shrink-0 transition-transform group-data-[state=open]/trigger:rotate-180 motion-reduce:transition-none"
        />
      </span>
    </Collapsible.Trigger>
  );
}

export function ActivityGroupContent({
  className,
  children,
  ...props
}: ComponentProps<typeof Collapsible.Content>) {
  return (
    <Collapsible.Content
      data-slot="activity-group-content"
      className={cn("overflow-hidden", className)}
      {...props}
    >
      <ol className="mt-1.5 ml-1.5 flex flex-col gap-3 border-l border-border-subtle pl-4">
        {children}
      </ol>
    </Collapsible.Content>
  );
}

export function ActivityStep({
  icon,
  title,
  status,
  children,
  className,
}: {
  icon: ReactNode;
  title: string;
  status: "running" | "done" | "failed";
  children?: ReactNode;
  className?: string;
}) {
  return (
    <li data-slot="activity-step" data-status={status} className={cn("min-w-0 text-sm", className)}>
      <div className="flex min-w-0 items-start gap-2">
        <span
          aria-hidden="true"
          className="flex h-5 w-4 shrink-0 items-center justify-center text-content-muted [&_svg]:size-3.5"
        >
          {status === "running" ? (
            <LoaderCircle className="animate-spin motion-reduce:animate-none" />
          ) : status === "failed" ? (
            <CircleAlert />
          ) : (
            icon
          )}
        </span>
        <span
          className={cn(
            "min-w-0 leading-5 [overflow-wrap:anywhere]",
            status === "failed" ? "text-content-secondary" : "text-content-primary",
          )}
        >
          {title}
        </span>
      </div>
      {children && <div className="mt-1.5 min-w-0 pl-6 text-content-muted">{children}</div>}
    </li>
  );
}

export type ActivityChip = { key: string; icon: ReactNode; label: string; title?: string };

/** Query and evidence chips, like the Onyx search chip list: a few first, the rest behind "+N". */
export function ActivityChips({
  items,
  limit = 4,
  moreLabel,
}: {
  items: ActivityChip[];
  limit?: number;
  moreLabel: (count: number) => string;
}) {
  const [expanded, setExpanded] = useState(false);
  const shown = expanded ? items : items.slice(0, limit);
  const rest = items.length - shown.length;
  return (
    <ul className="flex flex-wrap gap-1.5">
      {shown.map((item) => (
        <li
          key={item.key}
          title={item.title ?? item.label}
          className={cn(
            field,
            "inline-flex max-w-64 min-w-0 items-center gap-1.5 rounded-full px-2.5 py-1 text-xs text-content-secondary [&_svg]:size-3 [&_svg]:shrink-0",
          )}
        >
          {item.icon}
          <span className="truncate">{item.label}</span>
        </li>
      ))}
      {rest > 0 && (
        <li>
          <button
            type="button"
            onClick={() => setExpanded(true)}
            className={cn(
              field,
              "rounded-full px-2.5 py-1 text-xs text-content-muted transition-colors hover:text-content-primary focus-visible:outline-2 focus-visible:outline-ring",
            )}
          >
            {moreLabel(rest)}
          </button>
        </li>
      )}
    </ul>
  );
}
