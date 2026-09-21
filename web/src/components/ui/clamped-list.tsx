import { useAppTranslation } from "@/i18n/use-app-translation";
import { useCallback, useLayoutEffect, useRef, useState, type ReactElement } from "react";
import { cn } from "@/lib/utils";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/components/ui/hover-card";

/**
 * Keeps a list to its first rows and moves the rest behind a "+N" card that opens on hover.
 * Overflowing entries stay mounted and are hidden through the DOM, so a resize can measure again.
 */
export function ClampedList({
  items,
  maxRows,
  label,
  orientation = "wrap",
  className,
}: {
  /** One element per entry; each needs a stable key. */
  items: ReactElement[];
  maxRows: number;
  label: string;
  /** "wrap" measures chips flowing over several rows; "stack" puts one entry on each row. */
  orientation?: "wrap" | "stack";
  className?: string;
}) {
  const ui = useAppTranslation();
  const list = useRef<HTMLUListElement>(null);
  const measuredWidth = useRef(0);
  const [visible, setVisible] = useState(items.length);

  const measure = useCallback(() => {
    const node = list.current;
    if (!node) return;
    const entries = [...node.querySelectorAll<HTMLElement>("[data-clamped-item]")];
    const more = node.querySelector<HTMLElement>("[data-clamped-more]");
    const show = (count: number) =>
      entries.forEach((entry, index) => {
        entry.style.display = index < count ? "" : "none";
      });

    if (orientation === "stack") {
      const shown = Math.min(entries.length, maxRows);
      show(shown);
      if (more) more.style.display = entries.length > shown ? "" : "none";
      setVisible(shown);
      return;
    }

    show(entries.length);
    if (more) more.style.display = "none";
    const rows = [...new Set(entries.map((entry) => entry.offsetTop))].sort((a, b) => a - b);
    if (rows.length <= maxRows) {
      setVisible(entries.length);
      return;
    }
    const lastRowTop = rows[maxRows - 1];
    let shown = entries.filter((entry) => entry.offsetTop <= lastRowTop).length;
    if (more) {
      // The "+N" trigger takes room on the last row, so give way until it fits beside the entries.
      more.style.display = "";
      while (shown > 1) {
        show(shown);
        if (more.offsetTop <= lastRowTop) break;
        shown -= 1;
      }
    }
    show(shown);
    setVisible(shown);
  }, [maxRows, orientation]);

  useLayoutEffect(() => {
    measuredWidth.current = list.current?.clientWidth ?? 0;
    measure();
  }, [measure, items]);

  useLayoutEffect(() => {
    const node = list.current;
    if (!node || orientation === "stack" || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => {
      // Hiding entries changes the list's height, so only a width change can alter the row count.
      if (node.clientWidth === measuredWidth.current) return;
      measuredWidth.current = node.clientWidth;
      measure();
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, [measure, orientation]);

  const hidden = items.length - visible;
  return (
    <ul
      ref={list}
      aria-label={label}
      className={cn(
        orientation === "wrap" ? "flex flex-wrap items-center gap-1.5" : "flex flex-col gap-1.5",
        className,
      )}
    >
      {items.map((item) => (
        <li key={item.key} data-clamped-item className="flex min-w-0 max-w-full">
          {item}
        </li>
      ))}
      {/* Always mounted: the trigger takes part in the measurement that decides whether it is needed. */}
      <li data-clamped-more>
        <HoverCard>
          <HoverCardTrigger asChild>
            <button
              type="button"
              className="rounded-full border border-border-subtle bg-surface-raised px-2.5 py-1 font-secondary-action text-content-secondary outline-none hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40"
            >
              {ui("+{{n}}", { n: hidden })}
            </button>
          </HoverCardTrigger>
          <HoverCardContent className="max-h-80 w-auto max-w-sm overflow-y-auto">
            <ul aria-label={label} className="flex flex-col gap-1.5">
              {items.slice(visible).map((item) => (
                <li key={item.key} className="flex min-w-0 max-w-full">
                  {item}
                </li>
              ))}
            </ul>
          </HoverCardContent>
        </HoverCard>
      </li>
    </ul>
  );
}
