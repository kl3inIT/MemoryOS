import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/**
 * Why a page or a section has nothing to show, and what to do about it. One shape for an empty list, a resource
 * that is gone and a read that failed, so those three never look like three different products.
 */
export function EmptyState({
  icon,
  title,
  detail,
  action,
  role,
  className,
}: {
  icon?: ReactNode;
  title: ReactNode;
  detail?: ReactNode;
  /** A way forward: retry, or the action that would fill the emptiness. */
  action?: ReactNode;
  /** `alert` when this replaces a loading state, so the failure is announced rather than silently swapped in. */
  role?: "alert" | "status";
  className?: string;
}) {
  return (
    <div role={role} className={cn("flex flex-col items-center py-8 text-center", className)}>
      {icon && (
        <span aria-hidden="true" className="mb-3 text-content-muted [&_svg]:size-6">
          {icon}
        </span>
      )}
      <h2 className="font-heading-h3 text-content-primary">{title}</h2>
      {detail && <p className="mt-2 max-w-md font-main-ui-body text-content-muted">{detail}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}
