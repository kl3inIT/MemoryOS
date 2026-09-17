import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/** A titled block inside a page: heading, optional muted description and trailing actions. */
export function SectionHeader({
  title,
  description,
  icon,
  actions,
  level = "section",
  id,
  className,
}: {
  title: ReactNode;
  description?: ReactNode;
  icon?: ReactNode;
  actions?: ReactNode;
  /** `section` for page sections, `group` for smaller groups inside a form or panel. */
  level?: "section" | "group";
  id?: string;
  className?: string;
}) {
  const Heading = level === "section" ? "h2" : "h3";
  return (
    <div className={cn("flex min-w-0 items-start justify-between gap-3", className)}>
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          {icon && (
            <span
              aria-hidden="true"
              className="grid shrink-0 place-items-center text-content-secondary [&_svg]:size-4"
            >
              {icon}
            </span>
          )}
          <Heading
            id={id}
            className={cn(
              "min-w-0 text-content-primary",
              level === "section" ? "font-heading-h3" : "font-main-ui-action",
            )}
          >
            {title}
          </Heading>
        </div>
        {description && (
          <p className="mt-0.5 max-w-2xl font-secondary-body text-content-muted">{description}</p>
        )}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  );
}
