import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

export function SettingsLayout({
  children,
  className,
  wide = false,
}: {
  children: ReactNode;
  className?: string;
  wide?: boolean;
}) {
  return (
    <section
      className={cn(
        "mx-auto flex w-full min-w-0 flex-col gap-8 px-(--page-gutter) pt-6 pb-18 md:pt-13",
        wide ? "max-w-(--page-width-wide)" : "max-w-(--page-width-standard)",
        className,
      )}
    >
      {children}
    </section>
  );
}

export function PageHeader({
  title,
  description,
  icon,
  actions,
  eyebrow,
}: {
  title: string;
  description?: ReactNode;
  icon?: ReactNode;
  actions?: ReactNode;
  eyebrow?: string;
}) {
  return (
    <header className="flex min-w-0 flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
      <div className="min-w-0">
        {eyebrow && <p className="mb-2 font-secondary-body text-content-muted">{eyebrow}</p>}
        <div className="flex items-center gap-3">
          {icon && (
            <span
              className="grid size-6 shrink-0 place-items-center text-content-secondary [&_svg]:size-6"
              aria-hidden="true"
            >
              {icon}
            </span>
          )}
          <h1 className="min-w-0 break-words font-heading-h2 text-content-primary">{title}</h1>
        </div>
        {description && (
          <div
            className={cn("mt-2 max-w-2xl font-main-ui-body text-content-muted", icon && "sm:ml-9")}
          >
            {description}
          </div>
        )}
      </div>
      {actions && (
        <div className="flex shrink-0 flex-wrap items-center gap-2 sm:pt-0.5">{actions}</div>
      )}
    </header>
  );
}
