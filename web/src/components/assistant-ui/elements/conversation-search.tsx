// Adapted from assistant-ui elements-conversation-search (MIT), 2026-09-13.
// Retains controlled matches, stepping, excerpt and position markers; adds i18n and keyboard behavior.
"use client";

import type { ComponentProps } from "react";
import { ChevronDownIcon, ChevronUpIcon, SearchIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { field, ghostButton, mono, paper } from "./surfaces";

export interface SearchHit {
  id: string;
  before: string;
  match: string;
  after: string;
  position: number;
}

export function ConversationSearch({
  query,
  hits,
  activeIndex,
  onQueryChange,
  onStep,
  className,
  ...props
}: Omit<
  ComponentProps<"div">,
  "children" | "query" | "hits" | "activeIndex" | "onQueryChange" | "onStep"
> & {
  query: string;
  hits: readonly SearchHit[];
  activeIndex: number;
  onQueryChange?: (query: string) => void;
  onStep?: (delta: number) => void;
}) {
  const ui = useAppTranslation();
  const index = hits.length === 0 ? -1 : Math.min(Math.max(activeIndex, 0), hits.length - 1);
  const active = index === -1 ? undefined : hits[index];

  return (
    <div
      data-slot="conversation-search"
      className={cn("flex w-full max-w-sm gap-2", className)}

      {...props}
    >
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <div className={cn(paper, "flex items-center gap-2 rounded-full py-1.5 pr-1.5 pl-3")}>
          <SearchIcon className="text-content-muted size-3.5 shrink-0" />
          <input
            value={query}
            onChange={(event) => onQueryChange?.(event.target.value)}
            placeholder={ui("Tìm trong hội thoại này…")}
            aria-label={ui("Tìm trong hội thoại này…")}
            autoFocus
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.nativeEvent.isComposing) {
                event.preventDefault();
                onStep?.(event.shiftKey ? -1 : 1);
              }
            }}
            className="text-content-primary placeholder:text-content-muted min-w-0 flex-1 bg-transparent text-[13px] outline-none"
          />
          <span role="status" className={cn(mono, "text-content-muted shrink-0 tabular-nums")}>
            {hits.length === 0
              ? "0"
              : ui("{{current}}/{{total}}", { current: index + 1, total: hits.length })}
          </span>
          <button
            type="button"
            aria-label={ui("Kết quả trước")}
            disabled={hits.length === 0}
            onClick={() => onStep?.(-1)}
            className={cn(ghostButton, "size-6 shrink-0")}
          >
            <ChevronUpIcon className="size-3.5" />
          </button>
          <button
            type="button"
            aria-label={ui("Kết quả tiếp theo")}
            disabled={hits.length === 0}
            onClick={() => onStep?.(1)}
            className={cn(ghostButton, "size-6 shrink-0")}
          >
            <ChevronDownIcon className="size-3.5" />
          </button>
        </div>

        {active && (
          <div
            className={cn(
              field,
              "fade-in animate-in rounded-xl px-3 py-2 text-xs leading-relaxed duration-200",
            )}
          >
            <span className="text-content-secondary">{active.before}</span>
            <span className="text-content-primary rounded bg-highlight-active px-0.5">
              {active.match}
            </span>
            <span className="text-content-secondary">{active.after}</span>
          </div>
        )}
      </div>

      <div className="bg-foreground/[0.04] relative w-1.5 shrink-0 rounded-full">
        {hits.map((hit, i) => (
          <span
            key={hit.id}
            aria-hidden
            className={cn(
              "absolute inset-x-0 h-1 rounded-full transition-colors duration-200",
              i === index ? "bg-highlight-active" : "bg-highlight-match",
            )}
            style={{ top: `${hit.position}%` }}
          />
        ))}
      </div>
    </div>
  );
}
