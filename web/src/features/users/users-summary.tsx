import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { StatStrip, StatToggleTile } from "@/components/composites/stat-strip";
import { Skeleton } from "@/components/ui/skeleton";
import type { UserCounts } from "@/lib/hey-api/types.gen";
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

/** Status counts in the shared stat strip; each tile filters the list to its status. */
export function UsersSummary({
  counts,
  selectedStatus,
  loading = false,
  onStatusChange,
}: UsersSummaryProps) {
  const ui = useAppTranslation();

  return (
    <StatStrip columns={3} label={ui("Filter users by status")}>
      {summaryItems.map((item) => {
        const selected = selectedStatus === item.status;
        const count = counts?.[item.count];
        return (
          <StatToggleTile
            key={item.status}
            selected={selected}
            onToggle={() => onStatusChange(selected ? undefined : item.status)}
            accessibleLabel={
              count === undefined
                ? ui("Show {{v1}} users, count unavailable", {
                    v1: ui(item.label).toLocaleLowerCase(),
                  })
                : ui("Show {{v1}} users, {{v2}}", {
                    v1: ui(item.label).toLocaleLowerCase(),
                    v2: count.toLocaleString(uiLocale()),
                  })
            }
            label={ui(item.label)}
            value={
              count === undefined ? (
                loading ? (
                  <Skeleton className="h-6 w-10" />
                ) : (
                  "—"
                )
              ) : (
                count.toLocaleString(uiLocale())
              )
            }
          />
        );
      })}
    </StatStrip>
  );
}
