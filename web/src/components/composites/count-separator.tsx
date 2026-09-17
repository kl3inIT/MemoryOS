import type { ReactNode } from "react";

/** A thin rule with a centered count, closing a list (Opal `TextSeparator`). */
export function CountSeparator({ children }: { children: ReactNode }) {
  return (
    <div
      role="status"
      className="flex items-center gap-3 px-4 font-secondary-body text-content-muted"
    >
      <span aria-hidden="true" className="h-px flex-1 bg-border-subtle" />
      <span className="tabular-nums">{children}</span>
      <span aria-hidden="true" className="h-px flex-1 bg-border-subtle" />
    </div>
  );
}
