import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

export type SearchSourceOption = {
  /** `all` or a connector `SourceType`. */
  value: string;
  /** Untranslated label: rendered through `ui()`. */
  label: string;
  count: number;
  icon: ReactNode;
};

/**
 * Connector facet rail for large screens. Counts cover the bounded search candidates rather than the current page,
 * and exactly one row is pressed; smaller screens use the Source filter menu instead.
 */
export function SearchSourceRail({
  options,
  value,
  busy,
  onChange,
  className,
}: {
  options: readonly SearchSourceOption[];
  value: string;
  busy: boolean;
  onChange: (value: string) => void;
  className?: string;
}) {
  const ui = useAppTranslation();
  return (
    <aside
      aria-labelledby="search-source-rail-heading"
      aria-busy={busy}
      className={cn(
        "transition-opacity duration-150 motion-reduce:transition-none",
        busy && "opacity-60",
        className,
      )}
    >
      <h2
        id="search-source-rail-heading"
        className="px-2.5 pt-1.5 pb-2 font-secondary-action text-content-muted"
      >
        {ui("Source")}
      </h2>
      <ul className="space-y-0.5">
        {options.map((option) => (
          <li key={option.value}>
            <button
              type="button"
              aria-pressed={value === option.value}
              aria-label={ui("{{name}}: {{count}} results", {
                name: ui(option.label),
                count: option.count,
              })}
              className="flex min-h-9 w-full cursor-pointer items-center gap-2.5 rounded-lg px-2.5 text-left font-main-ui-body text-content-secondary outline-none transition-colors duration-150 hover:bg-surface-subtle hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/30 aria-pressed:bg-surface-sunken aria-pressed:text-content-primary motion-reduce:transition-none"
              onClick={() => onChange(option.value)}
            >
              <span className="grid size-4 shrink-0 place-items-center" aria-hidden="true">
                {option.icon}
              </span>
              <span className="min-w-0 flex-1 truncate">{ui(option.label)}</span>
              <span className="font-secondary-action text-content-muted tabular-nums">
                {option.count}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </aside>
  );
}
