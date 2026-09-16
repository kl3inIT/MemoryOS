import { cn } from "@/lib/utils";

/**
 * Identifies one MCP server in a list. An MCP server is an arbitrary third-party endpoint with no brand
 * mark to draw, and fetching its favicon would leak the Tenant's server list to that host, so the tile
 * shows a monogram taken from the slug the administrator chose.
 */
export function McpServerMark({ slug, className }: { slug: string; className?: string }) {
  const monogram =
    slug
      .replaceAll(/[^a-z0-9]/gi, "")
      .slice(0, 2)
      .toUpperCase() || "M";
  return (
    <span
      aria-hidden="true"
      className={cn(
        "inline-flex size-8 shrink-0 items-center justify-center rounded-md",
        "bg-surface-sunken font-secondary-body text-content-secondary uppercase",
        className,
      )}
    >
      {monogram}
    </span>
  );
}
