import { Link, type LinkProps } from "@tanstack/react-router";
import {
  forwardRef,
  type HTMLAttributes,
  type MouseEventHandler,
  type ReactNode,
  type Ref,
} from "react";
import { actionVariants } from "@/components/ui/action-styles";
import { cn } from "@/lib/utils";

type SidebarTabProps = Omit<HTMLAttributes<HTMLElement>, "children" | "onClick"> & {
  children: ReactNode;
  icon: ReactNode;
  selected?: boolean;
  collapsed?: boolean;
  to?: LinkProps["to"];
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
      to,
      onClick,
      variant = "heavy",
      className: customClassName,
      ...elementProps
    },
    ref,
  ) {
    const label = typeof children === "string" ? children : undefined;
    const className = cn(
      actionVariants({ tone: "default", prominence: "internal" }),
      "flex h-9 w-full items-center gap-2 rounded-lg px-2 font-main-ui-body",
      selected && "bg-surface-raised text-content-primary shadow-xs",
      !selected && variant === "light" && "text-content-muted",
      collapsed && "justify-center px-0",
      customClassName,
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

    if (to) {
      return (
        <Link
          {...elementProps}
          ref={ref as Ref<HTMLAnchorElement>}
          data-slot="sidebar-tab"
          to={to}
          aria-current={selected ? "page" : undefined}
          aria-label={collapsed ? label : undefined}
          title={collapsed ? label : undefined}
          onClick={onClick as MouseEventHandler<HTMLAnchorElement> | undefined}
          className={className}
        >
          {content}
        </Link>
      );
    }

    return (
      <button
        {...elementProps}
        ref={ref as Ref<HTMLButtonElement>}
        data-slot="sidebar-tab"
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
