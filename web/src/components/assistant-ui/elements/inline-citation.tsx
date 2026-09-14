"use client";

import { useId, type ComponentProps, type ReactNode } from "react";
import { HoverCard } from "radix-ui";
import { cn } from "@/lib/utils";

// Adapt the registry's fixed-sentence specimen for streamed markdown and Radix.
export function InlineCitation({
  preview,
  open,
  onOpenChange,
  icon,
  className,
  children,
  onClick,
  ...props
}: ComponentProps<"button"> & {
  preview: ReactNode;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Decorative source glyph shown before the label. */
  icon?: ReactNode;
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
          onClick={(event) => {
            onOpenChange(false);
            onClick?.(event);
          }}
          className={cn(
            "ms-0.5 inline-flex max-w-52 items-center gap-1 rounded-md border border-border-subtle px-1.5 py-px align-baseline text-xs font-medium transition-colors focus-visible:outline-2 focus-visible:outline-ring",
            open
              ? "border-border-strong bg-surface-sunken text-content-primary"
              : "bg-surface-subtle text-content-secondary hover:border-border-default hover:bg-surface-sunken hover:text-content-primary",
            className,
          )}
        >
          {icon}
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
