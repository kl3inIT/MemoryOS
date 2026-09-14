"use client";

// assistant-ui elements-tool-group and elements-reasoning (MIT). Adaptations: one disclosure for
// reasoning and tool steps, radix-ui Collapsible and ShimmerLabel from this kit, a caller-provided
// localized summary instead of "N tool calls", and a step row with running/done/failed state.
import { useCallback, useRef, type ComponentProps, type ReactNode } from "react";
import { ChevronDown, CircleAlert, LoaderCircle } from "lucide-react";
import { Collapsible } from "radix-ui";
import { useScrollLock } from "@assistant-ui/react";
import { cn } from "@/lib/utils";
import { mono, ShimmerLabel } from "./surfaces";

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

export function ActivityGroupTrigger({
  label,
  active,
  elapsed,
  className,
  ...props
}: Omit<ComponentProps<typeof Collapsible.Trigger>, "children"> & {
  label: string;
  active: boolean;
  elapsed?: string;
}) {
  return (
    <Collapsible.Trigger
      data-slot="activity-group-trigger"
      className={cn(
        "group/trigger flex max-w-full items-center gap-2 rounded-md py-1 text-left text-sm text-content-muted transition-colors hover:text-content-primary focus-visible:outline-2 focus-visible:outline-ring",
        className,
      )}
      {...props}
    >
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
      {elapsed && <span className={cn(mono, "shrink-0 tabular-nums")}>{elapsed}</span>}
      <ChevronDown
        aria-hidden="true"
        className="size-3.5 shrink-0 -rotate-90 transition-transform group-data-[state=open]/trigger:rotate-0 motion-reduce:transition-none"
      />
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
  meta,
  children,
  className,
}: {
  icon: ReactNode;
  title: string;
  status: "running" | "done" | "failed";
  meta?: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <li data-slot="activity-step" data-status={status} className={cn("min-w-0 text-sm", className)}>
      <div className="flex min-w-0 items-center gap-2">
        <span
          aria-hidden="true"
          className="flex size-4 shrink-0 items-center justify-center text-content-muted [&_svg]:size-3.5"
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
            "min-w-0 truncate",
            status === "failed" ? "text-content-secondary" : "text-content-primary",
          )}
        >
          {title}
        </span>
        {meta && (
          <span className={cn(mono, "ml-auto shrink-0 tabular-nums text-content-muted")}>
            {meta}
          </span>
        )}
      </div>
      {children && <div className="mt-1.5 min-w-0 pl-6 text-content-muted">{children}</div>}
    </li>
  );
}
