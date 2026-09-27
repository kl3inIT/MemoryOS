import { ChevronRight } from "lucide-react";
import type { ReactNode } from "react";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";

/**
 * One expandable row: a full-width summary button toggling a detail region.
 * Used for per-file run errors and permission entries.
 */
export function ExpandableRow({
  summary,
  label,
  children,
}: {
  summary: ReactNode;
  label: string;
  children: ReactNode;
}) {
  return (
    <Collapsible className="min-w-0">
      <CollapsibleTrigger asChild>
        <button
          type="button"
          aria-label={label}
          className="group flex min-h-11 w-full min-w-0 items-center gap-2 px-3 py-2 text-left transition-colors hover:bg-surface-subtle/40 focus-visible:outline-2 focus-visible:outline-focus-ring"
        >
          <ChevronRight
            aria-hidden="true"
            className="size-4 shrink-0 text-content-muted transition-transform group-data-[state=open]:rotate-90"
          />
          <span className="min-w-0 flex-1">{summary}</span>
        </button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="border-t border-border-subtle px-3 py-3 pl-9">{children}</div>
      </CollapsibleContent>
    </Collapsible>
  );
}
