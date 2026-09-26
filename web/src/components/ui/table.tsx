import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

/**
 * A named table's scroll container is a focusable region carrying the same name, so a keyboard can scroll a
 * table that overflows (axe `scrollable-region-focusable`). A table that scrolls within a bounded height takes
 * the bound on its own container (`maxHeight`), so a `sticky` header sticks to the box that scrolls;
 * `containerRef` reaches that box, as a virtualizer needs.
 */
function Table({
  className,
  maxHeight,
  containerRef,
  ...props
}: React.ComponentProps<"table"> & {
  /** `list` for a picker list inside a form, `viewer` for a sheet preview. */
  maxHeight?: "list" | "viewer";
  containerRef?: React.Ref<HTMLDivElement>;
}) {
  const label = props["aria-label"];
  const labelledBy = props["aria-labelledby"];
  const named = Boolean(label || labelledBy);
  return (
    <div
      ref={containerRef}
      data-slot="table-container"
      role={named ? "region" : undefined}
      tabIndex={named ? 0 : undefined}
      aria-label={label}
      aria-labelledby={labelledBy}
      className={cn(
        "relative w-full overflow-x-auto rounded-[inherit] outline-none focus-visible:ring-2 focus-visible:ring-focus-ring",
        maxHeight && "overflow-y-auto overscroll-contain",
        maxHeight === "list" && "max-h-80",
        maxHeight === "viewer" && "max-h-viewer",
      )}
    >
      <table
        data-slot="table"
        className={cn("w-full caption-bottom font-main-ui-body", className)}
        {...props}
      />
    </div>
  );
}

function TableHeader({
  className,
  sticky = false,
  ...props
}: React.ComponentProps<"thead"> & {
  /** Keeps the header in view while the rows scroll under it. */
  sticky?: boolean;
}) {
  return (
    <thead
      data-slot="table-header"
      data-sticky={sticky || undefined}
      className={cn(
        "[&_tr]:border-b",
        sticky && "sticky top-0 z-10 bg-surface-subtle [&_tr]:bg-inherit",
        className,
      )}
      {...props}
    />
  );
}

function TableBody({ className, ...props }: React.ComponentProps<"tbody">) {
  return (
    <tbody
      data-slot="table-body"
      className={cn("[&_tr:last-child]:border-0", className)}
      {...props}
    />
  );
}

function TableFooter({ className, ...props }: React.ComponentProps<"tfoot">) {
  return (
    <tfoot
      data-slot="table-footer"
      className={cn(
        "border-t bg-surface-subtle font-main-ui-action [&>tr]:last:border-b-0",
        className,
      )}
      {...props}
    />
  );
}

const tableRowVariants = cva(
  "border-b border-border-subtle transition-colors hover:bg-surface-base has-aria-expanded:bg-surface-base data-[state=selected]:bg-surface-subtle",
  {
    variants: {
      /**
       * `surface` gives the row an opaque background, which a pinned cell inherits; `cited` and `current` mark a
       * row the reader came for, as a cited passage is marked inside a document, and keep the mark under the pointer.
       */
      tone: {
        none: "",
        surface: "bg-surface-base",
        cited: "bg-evidence-highlight-surface/60 hover:bg-evidence-highlight-surface/60",
        current: "bg-evidence-highlight-surface hover:bg-evidence-highlight-surface",
      },
    },
    defaultVariants: { tone: "none" },
  },
);

function TableRow({
  className,
  tone,
  ...props
}: React.ComponentProps<"tr"> & VariantProps<typeof tableRowVariants>) {
  return (
    <tr
      data-slot="table-row"
      data-tone={tone && tone !== "none" ? tone : undefined}
      className={cn(tableRowVariants({ tone }), className)}
      {...props}
    />
  );
}

function TableHead({
  className,
  pinned = false,
  ...props
}: React.ComponentProps<"th"> & {
  /** Stays at the start while a wide table scrolls sideways, on its row's background. */
  pinned?: boolean;
}) {
  return (
    <th
      data-slot="table-head"
      data-pinned={pinned || undefined}
      // MemoryOS headers carry long Vietnamese labels, so wrapping stays on by default.
      className={cn(
        "h-10 px-2 text-left align-middle font-secondary-action text-content-secondary [&:has([role=checkbox])]:pr-0",
        pinned && "sticky left-0 bg-inherit",
        className,
      )}
      {...props}
    />
  );
}

function TableCell({
  className,
  pinned = false,
  ...props
}: React.ComponentProps<"td"> & {
  /**
   * The row's label cell: it stays at the start while a wide table scrolls sideways, on its row's background, and
   * carries the accent of the current row.
   */
  pinned?: boolean;
}) {
  return (
    <td
      data-slot="table-cell"
      data-pinned={pinned || undefined}
      className={cn(
        "p-2 align-middle [&:has([role=checkbox])]:pr-0",
        pinned &&
          "sticky left-0 bg-inherit font-medium in-data-[tone=current]:border-l-4 in-data-[tone=current]:border-pdf-highlight",
        className,
      )}
      {...props}
    />
  );
}

function TableCaption({ className, ...props }: React.ComponentProps<"caption">) {
  return (
    <caption
      data-slot="table-caption"
      className={cn("mt-4 font-secondary-body text-content-muted", className)}
      {...props}
    />
  );
}

export { Table, TableHeader, TableBody, TableFooter, TableHead, TableRow, TableCell, TableCaption };
