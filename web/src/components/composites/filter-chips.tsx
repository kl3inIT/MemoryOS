import { useState } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";

export type FilterChip = { value: string; label: string; count?: number };

const chipClass =
  "inline-flex h-7 items-center gap-1.5 rounded-full border px-3 font-secondary-action transition-colors duration-150 outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40";

/**
 * Toggle chips with facet counts, the catalog filter row used by Langdock and Lindy. Pressing the
 * selected chip clears it, so the row needs no "all" chip; rows longer than `visible` collapse.
 */
export function FilterChips({
  chips,
  value,
  onChange,
  label,
  visible = 8,
  className,
}: {
  chips: FilterChip[];
  value: string | undefined;
  onChange: (value: string | undefined) => void;
  label: string;
  visible?: number;
  className?: string;
}) {
  const ui = useAppTranslation();
  const [expanded, setExpanded] = useState(false);
  const hidden = expanded ? 0 : Math.max(0, chips.length - visible);
  const shown = hidden === 0 ? chips : chips.slice(0, visible);
  // A selected chip beyond the fold stays visible.
  const selected = chips.find((chip) => chip.value === value);
  if (selected && !shown.includes(selected)) shown.push(selected);
  return (
    <div
      role="group"
      aria-label={label}
      className={cn("flex flex-wrap items-center gap-1.5", className)}
    >
      {shown.map((chip) => {
        const pressed = chip.value === value;
        return (
          <button
            key={chip.value}
            type="button"
            aria-pressed={pressed}
            onClick={() => onChange(pressed ? undefined : chip.value)}
            className={cn(
              chipClass,
              pressed
                ? "border-transparent bg-action-primary text-action-primary-foreground"
                : "border-border-subtle bg-surface-raised text-content-secondary hover:border-border-default hover:text-content-primary",
            )}
          >
            {chip.label}
            {chip.count !== undefined && (
              <span
                className={cn(
                  "tabular-nums",
                  pressed ? "text-action-primary-foreground/70" : "text-content-muted",
                )}
              >
                {chip.count}
              </span>
            )}
          </button>
        );
      })}
      {hidden > 0 && (
        <button
          type="button"
          onClick={() => setExpanded(true)}
          className={cn(
            chipClass,
            "border-transparent text-content-muted hover:text-content-primary",
          )}
        >
          {ui("Thêm {{v1}} nhãn", { v1: hidden })}
        </button>
      )}
    </div>
  );
}
