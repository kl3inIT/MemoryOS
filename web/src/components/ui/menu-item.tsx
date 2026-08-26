import { Link, type LinkProps } from "@tanstack/react-router";
import type { MouseEventHandler, ReactNode } from "react";
import { cn } from "@/lib/utils";

type MenuItemProps = {
  children: ReactNode;
  icon: ReactNode;
  to?: LinkProps["to"];
  onClick?: MouseEventHandler<HTMLElement>;
  disabled?: boolean;
  tone?: "default" | "danger";
};

export function MenuItem({
  children,
  icon,
  to,
  onClick,
  disabled = false,
  tone = "default",
}: MenuItemProps) {
  const className = cn(
    "flex h-10 w-full items-center gap-3 rounded-lg px-3 text-left font-main-ui-body outline-none transition-colors focus-visible:ring-3 focus-visible:ring-ring/50",
    tone === "danger"
      ? "text-status-danger-content hover:bg-status-danger-surface"
      : "text-content-primary hover:bg-action-ghost-hover",
    disabled && "cursor-not-allowed opacity-55",
  );
  const content = (
    <>
      <span className="grid size-4.5 shrink-0 place-items-center" aria-hidden="true">
        {icon}
      </span>
      <span className="min-w-0 flex-1 truncate">{children}</span>
    </>
  );

  if (to) {
    return (
      <Link
        to={to}
        onClick={onClick as MouseEventHandler<HTMLAnchorElement> | undefined}
        className={className}
      >
        {content}
      </Link>
    );
  }

  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick as MouseEventHandler<HTMLButtonElement> | undefined}
      className={className}
    >
      {content}
    </button>
  );
}
