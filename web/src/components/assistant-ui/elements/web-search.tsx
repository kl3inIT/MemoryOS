"use client";

// assistant-ui elements-web-search (MIT). Adaptations: real status/count, i18n,
// no reserved demo height/timer, bounded domain text and unique URL keys.
import type { ComponentProps } from "react";
import { ChevronDown, SearchIcon } from "lucide-react";
import { Collapsible } from "radix-ui";
import { SourceIcon } from "./source-icon";
import { cn } from "@/lib/utils";
import { field, mono, ShimmerLabel } from "./surfaces";

export interface WebSearchResult {
  title: string;
  domain: string;
  url: string;
}
export function WebSearch({
  query,
  results,
  searching,
  label,
  className,
  ...props
}: Omit<ComponentProps<"div">, "results"> & {
  query: string;
  results: readonly WebSearchResult[];
  searching: boolean;
  label: string;
}) {
  return (
    <Collapsible.Root
      data-slot="web-search"
      className={cn("flex w-full max-w-sm flex-col gap-2.5", className)}
      {...props}
    >
      <Collapsible.Trigger
        aria-label={query || label}
        className="group flex max-w-full items-center gap-2 rounded-full text-left focus-visible:outline-2 focus-visible:outline-ring"
      >
        {query && (
          <span
            className={cn(
              field,
              "inline-flex w-fit max-w-full items-center gap-1.5 rounded-full px-3.5 py-2 text-xs",
            )}
          >
            <SearchIcon aria-hidden="true" className="size-3 shrink-0" />
            <span className="truncate" title={query}>
              {query}
            </span>
          </span>
        )}
        <ChevronDown
          className="size-3.5 shrink-0 text-content-muted transition-transform group-data-[state=open]:rotate-180"
          aria-hidden="true"
        />
      </Collapsible.Trigger>
      <div role="status" className="text-xs text-content-muted">
        {searching ? (
          <ShimmerLabel className="relative inline-block leading-none">{label}</ShimmerLabel>
        ) : (
          label
        )}
      </div>
      {results.length > 0 && (
        <Collapsible.Content className="flex max-h-60 flex-col overflow-y-auto">
          {results.map((result) => (
            <a
              key={result.url}
              href={result.url}
              target="_blank"
              rel="noopener noreferrer"
              className="-mx-2.5 flex items-center gap-2.5 rounded-xl px-2.5 py-1.5 hover:bg-surface-sunken focus-visible:outline-2 focus-visible:outline-ring"
            >
              <SourceIcon domain={result.domain} className="size-4 shrink-0" />
              <span className="min-w-0 flex-1 truncate text-sm" title={result.title}>
                {result.title}
              </span>
              <span className={cn(mono, "max-w-28 truncate text-content-muted")}>
                {result.domain}
              </span>
            </a>
          ))}
        </Collapsible.Content>
      )}
    </Collapsible.Root>
  );
}
