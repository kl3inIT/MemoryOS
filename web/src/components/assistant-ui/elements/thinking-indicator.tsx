"use client";

import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";
import { mono, ShimmerLabel } from "./surfaces";

export function ThinkingIndicator({
  label,
  elapsed,
  className,
  ...props
}: Omit<ComponentProps<"div">, "children" | "label" | "elapsed"> & {
  label: string;
  elapsed?: string;
}) {
  return (
    <div
      data-slot="thinking-indicator"
      className={cn("flex items-center gap-2.5 text-sm text-foreground/55", className)}

      {...props}
    >
      <span
        aria-hidden
        className="size-1.5 shrink-0 animate-pulse rounded-full bg-content-muted motion-reduce:animate-none"
      />
      <ShimmerLabel
        key={label}
        className="relative inline-block animate-in leading-none duration-300 fade-in slide-in-from-bottom-1 motion-reduce:animate-none"
      >
        {label}
      </ShimmerLabel>
      {elapsed !== undefined && (
        <span className={cn(mono, "text-foreground/30 tabular-nums")}>{elapsed}</span>
      )}
    </div>
  );
}
