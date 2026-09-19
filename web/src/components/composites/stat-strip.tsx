import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/** Shared stat typography, also for stats that must live in a table cell. */
export const statLabelClass = "font-secondary-body text-content-muted";
export const statValueClass = "font-heading-h3 tabular-nums text-content-primary";
export const statHintClass = "font-secondary-body tabular-nums text-content-muted";

// A last tile left alone in a two-column row spans it, so the strip never shows an empty cell.
const columnClasses = {
  2: "grid-cols-2 [&>:last-child:nth-child(odd)]:col-span-2",
  3: "grid-cols-3",
  4: "grid-cols-2 lg:grid-cols-4",
  5: "grid-cols-2 lg:grid-cols-5 max-lg:[&>:last-child:nth-child(odd)]:col-span-2",
} as const;

/**
 * One bordered strip of stats divided by hairlines (LangChain and ElevenLabs usage summaries), used for every
 * figure summary in the app. `columns` is the desktop count; four and five fold to two per row on phones.
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

type StatTileProps = {
  label: ReactNode;
  value: ReactNode;
  /** A second line under the value: its parts, its period or its source. */
  hint?: ReactNode;
  /** The same second line, written as children. */
  children?: ReactNode;
  tone?: "warning" | "danger";
  className?: string;
};

function StatBody({ label, value, hint = null, children, tone }: StatTileProps) {
  const second = hint ?? children;
  return (
    <>
      <span className={cn("block", statLabelClass)}>{label}</span>
      <span
        className={cn(
          "mt-1 block",
          statValueClass,
          tone === "warning" && "text-status-warning-content",
          tone === "danger" && "text-status-danger-content",
        )}
      >
        {value}
      </span>
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
