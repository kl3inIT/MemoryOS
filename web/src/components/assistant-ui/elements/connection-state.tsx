// Adapted from assistant-ui (MIT), elements-connection-state registry, 2026-09-12.
import { Loader2Icon } from "lucide-react";
import { cn } from "@/lib/utils";

/** Only the reconnecting state exposed by our transport; no fabricated attempt/token counts. */
export function ConnectionState({ label, className }: { label: string; className?: string }) {
  return (
    <div
      data-slot="connection-state"
      role="status"
      className={cn(
        "flex min-w-0 items-center gap-2.5 rounded-2xl border border-border-default bg-surface-raised px-3.5 py-2.5 text-sm text-content-secondary",
        className,
      )}
    >
      <Loader2Icon
        aria-hidden="true"
        className="size-3.5 shrink-0 animate-spin motion-reduce:animate-none"
      />
      <span className="min-w-0 [overflow-wrap:anywhere]">{label}</span>
    </div>
  );
}
