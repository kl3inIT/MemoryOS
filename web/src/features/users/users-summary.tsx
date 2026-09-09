import { Skeleton } from "@/components/ui/skeleton";
import type { UserCounts } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import type { UserStatusFilter } from "./users-search";

type UsersSummaryProps = {
  counts?: UserCounts;
  selectedStatus?: UserStatusFilter;
  loading?: boolean;
  onStatusChange: (status?: UserStatusFilter) => void;
};

const summaryItems = [
  { status: "ACTIVE", label: "Active", count: "active" },
  { status: "INACTIVE", label: "Inactive", count: "inactive" },
  { status: "INVITED", label: "Invited", count: "invited" },
] as const;

export function UsersSummary({
  counts,
  selectedStatus,
  loading = false,
  onStatusChange,
}: UsersSummaryProps) {
  return (
    <div
      role="group"
      aria-label="Filter users by status"
      className="grid grid-cols-3 overflow-hidden rounded-xl border border-border-subtle bg-surface-raised"
    >
      {summaryItems.map((item, index) => {
        const selected = selectedStatus === item.status;
        const count = counts?.[item.count];
        return (
          <button
            key={item.status}
            type="button"
            aria-pressed={selected}
            aria-label={
              count === undefined
                ? `Show ${item.label.toLowerCase()} users, count unavailable`
                : `Show ${item.label.toLowerCase()} users, ${count.toLocaleString()}`
            }
            onClick={() => onStatusChange(selected ? undefined : item.status)}
            className={cn(
              "group relative min-w-0 px-3 py-3 text-left outline-none transition-colors duration-150 hover:bg-surface-subtle focus-visible:z-10 focus-visible:ring-3 focus-visible:ring-focus-ring/40 sm:px-4 sm:py-4",
              index > 0 && "border-l border-border-subtle",
              selected && "bg-surface-sunken",
            )}
          >
            {count === undefined ? (
              loading ? (
                <Skeleton className="mb-1.5 h-6 w-10" />
              ) : (
                <span className="block text-xl font-semibold leading-6 text-content-muted sm:text-2xl sm:leading-7">
                  —
                </span>
              )
            ) : (
              <span className="block text-xl font-semibold leading-6 tabular-nums text-content-primary sm:text-2xl sm:leading-7">
                {count.toLocaleString()}
              </span>
            )}
            <span
              className={cn(
                "mt-0.5 block truncate font-secondary-body text-content-muted",
                selected && "font-medium text-content-primary",
              )}
            >
              {item.label}
            </span>
            <span
              aria-hidden="true"
              className={cn(
                "absolute inset-x-3 bottom-0 h-0.5 origin-left scale-x-0 bg-content-primary transition-transform duration-150 sm:inset-x-4",
                selected && "scale-x-100",
              )}
            />
          </button>
        );
      })}
    </div>
  );
}
