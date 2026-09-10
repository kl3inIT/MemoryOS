"use client";

import { useId, type ComponentProps, type ReactNode } from "react";
import { HoverCard } from "radix-ui";
import { cn } from "@/lib/utils";

// Adapt the registry's fixed-sentence specimen for streamed markdown and Radix.
export function InlineCitation({
  preview,
  open,
  onOpenChange,
  className,
  children,
  onClick,
  ...props
}: ComponentProps<"button"> & {
  preview: ReactNode;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const previewId = useId();
  return (
    <HoverCard.Root open={open} onOpenChange={onOpenChange} openDelay={100} closeDelay={150}>
      <HoverCard.Trigger asChild>
        <button
          {...props}
          type="button"
          data-slot="inline-citation"
          aria-describedby={open ? previewId : undefined}
          onFocus={() => onOpenChange(true)}
          onBlur={() => onOpenChange(false)}
          onKeyDown={(event) => {
            if (event.key === "Escape" && open) {
              event.preventDefault();
              event.stopPropagation();
              onOpenChange(false);
            }
          }}
          onClick={(event) => {
            onOpenChange(false);
            onClick?.(event);
          }}
          className={cn(
            "ms-0.5 inline-flex max-w-40 items-center rounded px-1.5 align-baseline text-xs font-medium transition-colors focus-visible:outline-2 focus-visible:outline-ring",
            open
              ? "bg-content-primary text-surface-base"
              : "bg-surface-sunken text-content-secondary hover:bg-content-primary hover:text-surface-base",
            className,
          )}
        >
          <span className="truncate">{children}</span>
        </button>
      </HoverCard.Trigger>
      <HoverCard.Portal>
        <HoverCard.Content
          id={previewId}
          side="bottom"
          align="start"
          sideOffset={6}
          collisionPadding={12}
          className="z-50 w-80 max-w-[calc(100vw-1.5rem)] rounded-xl border border-border-default bg-surface-overlay p-4 text-content-primary shadow-lg outline-none"
        >
          {preview}
        </HoverCard.Content>
      </HoverCard.Portal>
    </HoverCard.Root>
  );
}
