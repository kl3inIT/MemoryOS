import type { ReactNode } from "react";

export function SidebarSection({
  title,
  collapsed,
  children,
}: {
  title: string;
  collapsed: boolean;
  children: ReactNode;
}) {
  return (
    <section className="space-y-1">
      {!collapsed && (
        <h2 className="px-2.5 pt-3 pb-1.5 font-secondary-body text-content-muted">{title}</h2>
      )}
      {children}
    </section>
  );
}
