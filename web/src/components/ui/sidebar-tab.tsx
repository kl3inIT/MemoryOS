import {
  forwardRef,
  type HTMLAttributes,
  type MouseEventHandler,
  type ReactNode,
  type Ref,
} from "react";
import { cn } from "@/lib/utils";

type SidebarTabProps = Omit<HTMLAttributes<HTMLElement>, "children" | "onClick"> & {
  children: ReactNode;
  icon: ReactNode;
  selected?: boolean;
  collapsed?: boolean;
  href?: string;
  onClick?: MouseEventHandler<HTMLElement>;
  variant?: "heavy" | "light";
};

export const SidebarTab = forwardRef<HTMLAnchorElement | HTMLButtonElement, SidebarTabProps>(
  function SidebarTab(
    {
      children,
      icon,
      selected = false,
      collapsed = false,
      href,
      onClick,
      variant = "heavy",
      ...elementProps
    },
    ref,
  ) {
    const label = typeof children === "string" ? children : undefined;
    const className = cn(
      "flex h-9 w-full items-center gap-2.5 rounded-lg px-2.5 font-main-ui-body outline-none transition-colors duration-150 focus-visible:ring-3 focus-visible:ring-ring/50",
      selected
        ? "bg-surface-sunken text-content-primary hover:bg-surface-sunken"
        : variant === "light"
          ? "text-content-muted hover:bg-action-ghost-hover hover:text-content-secondary"
          : "text-content-secondary hover:bg-action-ghost-hover hover:text-content-primary",
      collapsed && "justify-center px-0",
    );
    const content = (
      <>
        <span className="grid size-5 shrink-0 place-items-center" aria-hidden="true">
          {icon}
        </span>
        <span className={cn("min-w-0 flex-1 truncate text-left", collapsed && "sr-only")}>
          {children}
        </span>
      </>
    );

    if (href) {
      return (
        <a
          {...elementProps}
          ref={ref as Ref<HTMLAnchorElement>}
          href={href}
          aria-current={selected ? "page" : undefined}
          aria-label={collapsed ? label : undefined}
          title={collapsed ? label : undefined}
          onClick={onClick as MouseEventHandler<HTMLAnchorElement> | undefined}
          className={className}
        >
          {content}
        </a>
      );
    }

    return (
      <button
        {...elementProps}
        ref={ref as Ref<HTMLButtonElement>}
        type="button"
        aria-pressed={selected || undefined}
        aria-label={collapsed ? label : undefined}
        title={collapsed ? label : undefined}
        onClick={onClick as MouseEventHandler<HTMLButtonElement> | undefined}
        className={className}
      >
        {content}
      </button>
    );
  },
);
