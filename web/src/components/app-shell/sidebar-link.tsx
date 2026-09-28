import { Link, type LinkProps } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { SidebarMenuButton, SidebarMenuItem } from "@/components/ui/sidebar";

/** One sidebar row that opens a page; folded to the rail it keeps its name and shows it as a tooltip. */
export function SidebarLink({
  to,
  label,
  icon,
  selected = false,
  activeOptions,
  variant,
  onNavigate,
}: {
  to: LinkProps["to"];
  label: string;
  icon: ReactNode;
  selected?: boolean;
  /** Router matching for the link's own active state, which otherwise marks a parent path current on every child route. */
  activeOptions?: LinkProps["activeOptions"];
  variant?: "default" | "light";
  onNavigate?: () => void;
}) {
  return (
    <SidebarMenuItem>
      <SidebarMenuButton asChild isActive={selected} variant={variant} tooltip={label}>
        <Link
          to={to}
          activeOptions={activeOptions}
          aria-current={selected ? "page" : undefined}
          onClick={onNavigate}
        >
          {icon}
          <span>{label}</span>
        </Link>
      </SidebarMenuButton>
    </SidebarMenuItem>
  );
}
