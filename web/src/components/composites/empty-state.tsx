import type { ReactNode } from "react";
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";

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
    <Empty role={role} className={className}>
      <EmptyHeader>
        {icon && (
          <EmptyMedia variant="icon" aria-hidden="true">
            {icon}
          </EmptyMedia>
        )}
        <EmptyTitle asChild>
          <h2>{title}</h2>
        </EmptyTitle>
        {detail && <EmptyDescription>{detail}</EmptyDescription>}
      </EmptyHeader>
      {action && <EmptyContent>{action}</EmptyContent>}
    </Empty>
  );
}
