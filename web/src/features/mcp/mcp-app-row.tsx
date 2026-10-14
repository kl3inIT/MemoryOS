import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/** The two-column list of apps, as ChatGPT's plugin directory lays them out. */
export function McpAppGrid({ children }: { children: ReactNode }) {
  return <ul className="grid gap-x-8 gap-y-1 md:grid-cols-2">{children}</ul>;
}

/** Classes for the clickable part of a row when it opens the app's page. */
export const appRowLinkClass =
  "flex min-w-0 flex-1 items-center gap-3 rounded-lg text-left focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none";

/**
 * One app: its mark, its name and one line, with a single action at the end. The line says what is missing when
 * something is, so a row needs no badge; everything else is on the app's own page, which `link` opens.
 */
export function McpAppRow({
  mark,
  title,
  line,
  tone = "muted",
  link,
  action,
}: {
  mark: ReactNode;
  title: string;
  line?: ReactNode;
  tone?: "muted" | "warning" | "danger";
  /** Wraps the mark and text in a link to the app's page; give it `appRowLinkClass`. */
  link?: (body: ReactNode) => ReactNode;
  action?: ReactNode;
}) {
  const body = (
    <>
      {mark}
      <span className="flex min-w-0 flex-col">
        <span className="truncate font-main-ui-action text-content-primary">{title}</span>
        {line ? (
          <span
            className={cn(
              "truncate font-secondary-body",
              tone === "warning"
                ? "text-status-warning-content"
                : tone === "danger"
                  ? "text-status-danger-content"
                  : "text-content-muted",
            )}
          >
            {line}
          </span>
        ) : null}
      </span>
    </>
  );
  return (
    <li className="flex min-h-16 items-center gap-3 rounded-xl px-2 py-2 hover:bg-surface-subtle">
      {link ? link(body) : <span className="flex min-w-0 flex-1 items-center gap-3">{body}</span>}
      {action ? <span className="shrink-0">{action}</span> : null}
    </li>
  );
}
