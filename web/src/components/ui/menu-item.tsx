import { Link, type LinkProps } from "@tanstack/react-router";
import type { MouseEventHandler, ReactNode } from "react";
import { actionVariants } from "@/components/ui/action-styles";
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
    actionVariants({ tone, prominence: "internal" }),
    "flex h-[var(--control-height-md)] w-full items-center gap-3 rounded-lg px-3 text-left font-main-ui-body",
    disabled && "cursor-not-allowed",
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
        aria-disabled={disabled || undefined}
        tabIndex={disabled ? -1 : undefined}
        to={to}
        onClick={
          disabled
            ? (event) => event.preventDefault()
            : (onClick as MouseEventHandler<HTMLAnchorElement> | undefined)
        }
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
