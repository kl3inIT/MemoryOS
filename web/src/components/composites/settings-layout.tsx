import { use, type ReactNode, type Ref } from "react";
import { ShellBarTitle } from "@/components/app-shell/app-shell-header-slot";
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
  titleRef,
  description,
  icon,
  iconSize = "sm",
  actions,
}: {
  title: string;
  /** Focus lands on the title when a page-level action rebuilds what is below it. */
  titleRef?: Ref<HTMLHeadingElement>;
  description?: ReactNode;
  icon?: ReactNode;
  iconSize?: "sm" | "lg";
  actions?: ReactNode;
}) {
  // Below `md` the shell bar names the page, so the same title and its one-line description step aside and the
  // content starts sooner; a screen reader still reads both.
  const inBar = use(ShellBarTitle) === title;
  return (
    <header
      className={cn(
        "flex min-w-0 flex-col gap-4 sm:flex-row sm:items-start sm:justify-between",
        // With no actions either, nothing of the header is on screen, so it leaves the layout's flow.
        inBar && !actions && "max-md:sr-only",
      )}
    >
      <div className={cn("min-w-0", inBar && "max-md:sr-only")}>
        <div className="flex items-center gap-3">
          {icon && (
            <span
              className={cn(
                "grid shrink-0 place-items-center text-content-secondary",
                iconSize === "lg" ? "size-10 [&_svg]:size-10" : "size-6 [&_svg]:size-6",
              )}
              aria-hidden="true"
            >
              {icon}
            </span>
          )}
          <h1
            ref={titleRef}
            tabIndex={titleRef ? -1 : undefined}
            className="min-w-0 font-heading-h2 break-words text-content-primary outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
          >
            {title}
          </h1>
        </div>
        {description && (
          <div
            className={cn(
              "mt-2 max-w-2xl font-main-ui-body text-content-muted",
              icon && (iconSize === "lg" ? "sm:ml-13" : "sm:ml-9"),
            )}
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
