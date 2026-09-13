// Adapted from assistant-ui elements-document-reference (MIT), 2026-09-13.
// Real citation IDs/labels replace demo page counts; excerpts may come from authorized readers.
"use client";

import type { ComponentProps, ReactNode } from "react";
import { FileTextIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { field, mono, paper } from "./surfaces";

export interface DocumentAnchor {
  id: number;
  label: string;
  accessibleLabel: string;
  quote: ReactNode;
}

export function DocumentReference({
  title,
  subtitle,
  icon,
  anchors,
  activeId,
  onJump,
  className,
  ...props
}: Omit<
  ComponentProps<"div">,
  "children" | "title" | "subtitle" | "anchors" | "activeId" | "onJump"
> & {
  title: string;
  subtitle: string;
  icon?: ReactNode;
  anchors: readonly DocumentAnchor[];
  activeId?: number;
  onJump?: (citationId: number) => void;
}) {
  // Citations identify actual excerpts; a source need not have page numbers.
  const currentIndex = anchors.findIndex((anchor) => anchor.id === activeId);

  return (
    <div
      data-slot="document-reference"
      className={cn(paper, "flex w-full max-w-full flex-col gap-3 rounded-2xl p-3.5", className)}

      {...props}
    >
      <div className="flex items-center gap-2.5">
        <span className="bg-foreground/[0.05] text-content-secondary flex size-8 shrink-0 items-center justify-center rounded-lg">
          {icon ?? <FileTextIcon className="size-4" aria-hidden="true" />}
        </span>
        <div className="flex min-w-0 flex-1 flex-col">
          <h3 className="truncate text-sm font-medium" title={title}>
            {title}
          </h3>
          <span className={cn(mono, "text-content-muted")}>{subtitle}</span>
        </div>
      </div>

      <div className="flex flex-col gap-1.5">
        {anchors.map((anchor, i) => (
          <button
            key={`${anchor.id}-${i}`}
            type="button"
            aria-label={anchor.accessibleLabel}
            aria-current={i === currentIndex || undefined}
            onClick={() => onJump?.(anchor.id)}
            className={cn(
              "focus-visible:outline-2 focus-visible:outline-ring flex flex-col gap-1 rounded-xl px-2.5 py-2 text-start transition-colors",
              anchor.id === activeId ? field : "hover:bg-foreground/[0.035]",
            )}
          >
            <span className={cn(mono, "text-content-muted")}>{anchor.label}</span>
            <div className="text-content-secondary border-border-default border-s-2 ps-2 text-xs leading-relaxed break-words">
              {anchor.quote}
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
