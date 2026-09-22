import { Minus, Plus } from "lucide-react";
import { useState, type ReactNode } from "react";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";

/**
 * The reader's controls, floating over the document rather than framing it, so the page keeps the width.
 * Every format shares the shell; a control a format cannot support is simply not passed.
 */
export function PreviewToolbar({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  const ui = useAppTranslation();
  return (
    <div
      role="toolbar"
      aria-label={ui("Điều khiển tài liệu")}
      data-slot="preview-toolbar"
      className={cn(
        "pointer-events-auto flex items-center gap-1 rounded-xl border border-border-subtle bg-surface-overlay/95 p-1 shadow-lg backdrop-blur-sm",
        className,
      )}
    >
      {children}
    </div>
  );
}

/** Groups controls inside the toolbar, with a hairline between groups. */
export function ToolbarGroup({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-center gap-1 border-border-subtle border-l pl-1 first:border-0 first:pl-0">
      {children}
    </div>
  );
}

export function ZoomControl({
  label,
  onOut,
  onIn,
  outDisabled,
  inDisabled,
}: {
  /** The current level as the reader reads it, such as "Vừa khung" or "150%". */
  label: string;
  onOut: () => void;
  onIn: () => void;
  outDisabled?: boolean;
  inDisabled?: boolean;
}) {
  const ui = useAppTranslation();
  return (
    <ToolbarGroup>
      <IconButton
        prominence="internal"
        size="sm"
        aria-label={ui("Thu nhỏ")}
        disabled={outDisabled}
        onClick={onOut}
      >
        <Minus />
      </IconButton>
      <span
        aria-live="polite"
        className="min-w-16 text-center font-secondary-action text-content-secondary tabular-nums"
      >
        {label}
      </span>
      <IconButton
        prominence="internal"
        size="sm"
        aria-label={ui("Phóng to")}
        disabled={inDisabled}
        onClick={onIn}
      >
        <Plus />
      </IconButton>
    </ToolbarGroup>
  );
}

/**
 * The page the reader is on, which they may also type to jump. It follows the document while they scroll
 * and only reports a page when what they typed is one.
 */
export function PageField({
  page,
  total,
  onPage,
}: {
  page: number;
  total: number;
  onPage: (page: number) => void;
}) {
  const ui = useAppTranslation();
  const [typed, setTyped] = useState(String(page));
  // Scrolling moves the reader between pages, so the field follows the document rather than the keystroke.
  const [shown, setShown] = useState(page);
  if (page !== shown) {
    setShown(page);
    setTyped(String(page));
  }
  const commit = () => {
    const wanted = Number.parseInt(typed, 10);
    if (!Number.isFinite(wanted)) {
      setTyped(String(page));
      return;
    }
    onPage(Math.min(Math.max(wanted, 1), total));
  };
  return (
    <ToolbarGroup>
      <input
        type="number"
        inputMode="numeric"
        min={1}
        max={total}
        value={typed}
        aria-label={ui("Số trang")}
        className="h-7 w-12 rounded-md bg-surface-sunken px-1.5 text-center font-secondary-action text-content-primary tabular-nums outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40 [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
        onChange={(event) => setTyped(event.target.value)}
        onBlur={commit}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            commit();
          }
        }}
      />
      <span className="pr-1 font-secondary-body text-content-muted tabular-nums">/ {total}</span>
    </ToolbarGroup>
  );
}
