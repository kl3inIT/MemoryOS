import { CircleCheck, CircleSlash, MailPlus } from "lucide-react";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { StatStrip, StatToggleTile } from "@/components/composites/stat-strip";
import type { UserCounts } from "@/lib/hey-api/types.gen";
import type { UserStatusFilter } from "./users-search";

type UsersSummaryProps = {
  counts?: UserCounts;
  selectedStatus?: UserStatusFilter;
  loading?: boolean;
  onStatusChange: (status?: UserStatusFilter) => void;
};

// The icons repeat the status tags in the table below, in the same colours.
const summaryItems = [
  {
    status: "ACTIVE",
    label: "Active",
    count: "active",
    icon: <CircleCheck />,
    iconClass: "text-status-success-content",
  },
  {
    status: "INACTIVE",
    label: "Inactive",
    count: "inactive",
    icon: <CircleSlash />,
    iconClass: "text-content-muted",
  },
  {
    status: "INVITED",
    label: "Invited",
    count: "invited",
    icon: <MailPlus />,
    iconClass: "text-status-warning-content",
  },
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
            icon={item.icon}
            iconClass={item.iconClass}
            loading={loading && count === undefined}
            label={ui(item.label)}
            value={count === undefined ? "—" : count.toLocaleString(uiLocale())}
          />
        );
      })}
    </StatStrip>
  );
}
