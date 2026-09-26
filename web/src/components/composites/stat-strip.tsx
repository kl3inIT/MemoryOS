import type { ReactNode } from "react";
import { Minus, TrendingDown, TrendingUp } from "lucide-react";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

/** Shared stat typography, also for stats that must live in a table cell. */
export const statLabelClass = "font-secondary-body text-content-secondary";
export const statValueClass = "font-heading-h3 tabular-nums text-content-primary";
const statHintClass = "font-secondary-body tabular-nums text-content-muted";

// A last tile left alone in a two-column row spans it, so the strip never shows an empty cell.
const columnClasses = {
  2: "grid-cols-2 [&>:last-child:nth-child(odd)]:col-span-2",
  3: "grid-cols-3",
  4: "grid-cols-2 lg:grid-cols-4",
  5: "grid-cols-2 lg:grid-cols-5 max-lg:[&>:last-child:nth-child(odd)]:col-span-2",
} as const;

/**
 * One bordered strip of stats divided by hairlines, used for every figure summary in the app: it repeats the
 * shape of the tables these summaries sit above. `columns` is the desktop count; four and five fold to two
 * per row on phones.
 */
export function StatStrip({
  columns,
  label,
  className,
  children,
}: {
  columns: keyof typeof columnClasses;
  /** Names a strip of selectable tiles for assistive technology. */
  label?: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div
      role={label ? "group" : undefined}
      aria-label={label}
      className={cn(
        "grid gap-px overflow-hidden rounded-2xl border border-border-subtle bg-border-subtle",
        columnClasses[columns],
        className,
      )}
    >
      {children}
    </div>
  );
}

/** How a figure moved against the period before it. The direction carries the meaning, never colour alone. */
type StatTrend = { direction: "up" | "down" | "flat"; label: string };

type StatTileProps = {
  label: ReactNode;
  /** Beside the label, in a chart or status colour; it repeats what the label already says. */
  icon?: ReactNode;
  iconClass?: string;
  value: ReactNode;
  /** A second line under the value: its parts, its period or its source. */
  hint?: ReactNode;
  /** The same second line, written as children. */
  children?: ReactNode;
  trend?: StatTrend;
  tone?: "warning" | "danger";
  /** Shows a placeholder in place of the value while the figure loads. */
  loading?: boolean;
  className?: string;
};

const trendIcons = { up: TrendingUp, down: TrendingDown, flat: Minus };

function StatBody({
  label,
  icon,
  iconClass,
  value,
  hint = null,
  children,
  trend,
  tone,
  loading = false,
}: StatTileProps) {
  const second = hint ?? children;
  const TrendIcon = trend ? trendIcons[trend.direction] : null;
  return (
    <>
      <span className={cn("flex min-w-0 items-center gap-2", statLabelClass)}>
        {icon && (
          <span
            aria-hidden="true"
            className={cn("shrink-0 [&_svg]:size-4.5", iconClass ?? "text-content-muted")}
          >
            {icon}
          </span>
        )}
        <span className="truncate">{label}</span>
      </span>
      {loading ? (
        <Skeleton className="mt-1.5 h-6 w-20" />
      ) : (
        <span
          // A long figure keeps the tile's width; the full text stays available on hover.
          title={typeof value === "string" ? value : undefined}
          className={cn(
            "mt-1 block truncate",
            statValueClass,
            tone === "warning" && "text-status-warning-content",
            tone === "danger" && "text-status-danger-content",
          )}
        >
          {value}
        </span>
      )}
      {TrendIcon && trend && (
        <span className={cn("mt-0.5 flex items-center gap-1", statHintClass)}>
          <TrendIcon aria-hidden="true" className="size-3.5 shrink-0" />
          {trend.label}
        </span>
      )}
      {second ? <span className={cn("mt-0.5 block", statHintClass)}>{second}</span> : null}
    </>
  );
}

const tileClass = "min-w-0 bg-surface-raised px-4 py-3";

export function StatTile(props: StatTileProps) {
  return (
    <div className={cn(tileClass, props.className)}>
      <StatBody {...props} />
    </div>
  );
}

/** A tile that filters the view it summarizes, as the Users status counts do. */
export function StatToggleTile({
  selected,
  onToggle,
  accessibleLabel,
  ...props
}: StatTileProps & { selected: boolean; onToggle: () => void; accessibleLabel: string }) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      aria-label={accessibleLabel}
      onClick={onToggle}
      className={cn(
        tileClass,
        "relative text-left outline-none transition-colors duration-150 hover:bg-surface-subtle focus-visible:z-10 focus-visible:ring-3 focus-visible:ring-focus-ring/40",
        selected && "bg-surface-sunken",
        props.className,
      )}
    >
      <StatBody {...props} />
      <span
        aria-hidden="true"
        className={cn(
          "absolute inset-x-4 bottom-0 h-0.5 origin-left scale-x-0 bg-action-selection transition-transform duration-150",
          selected && "scale-x-100",
        )}
      />
    </button>
  );
}
