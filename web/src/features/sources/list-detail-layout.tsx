import { ChevronDown, ChevronRight } from "lucide-react";
import { useId, useState, type ReactNode } from "react";

/**
 * Two-pane list + detail layout shared by the Source detail surfaces.
 * On wide screens the detail sits beside the list; below `lg` it stacks
 * under the list. Pass `detail={null}` to render the list full width.
 */
export function ListDetailLayout({
  list,
  detail,
  detailLabel,
}: {
  list: ReactNode;
  detail: ReactNode;
  detailLabel: string;
}) {
  if (!detail) return <>{list}</>;
  return (
    <div className="grid min-w-0 gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
      <div className="min-w-0">{list}</div>
      <aside
        aria-label={detailLabel}
        className="min-w-0 self-start rounded-xl border border-border-subtle bg-surface-raised p-4 lg:sticky lg:top-4 lg:max-h-[calc(100dvh-6rem)] lg:overflow-y-auto"
      >
        {detail}
      </aside>
    </div>
  );
}

/**
 * One expandable row: a full-width summary button toggling a detail region.
 * Used for per-file run errors and permission entries.
 */
export function ExpandableRow({
  summary,
  label,
  children,
}: {
  summary: ReactNode;
  label: string;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const detailId = useId();
  return (
    <div className="min-w-0">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={open ? detailId : undefined}
        aria-label={label}
        className="flex min-h-11 w-full min-w-0 items-center gap-2 px-3 py-2 text-left transition-colors hover:bg-surface-subtle/40 focus-visible:outline-2 focus-visible:outline-focus-ring"
        onClick={() => setOpen((current) => !current)}
      >
        {open ? (
          <ChevronDown aria-hidden="true" className="size-4 shrink-0 text-content-muted" />
        ) : (
          <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-content-muted" />
        )}
        <span className="min-w-0 flex-1">{summary}</span>
      </button>
      {open ? (
        <div id={detailId} className="border-t border-border-subtle px-3 py-3 pl-9">
          {children}
        </div>
      ) : null}
    </div>
  );
}
