import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * A named table's scroll container is a focusable region carrying the same name, so a keyboard can scroll a
 * table that overflows (axe `scrollable-region-focusable`).
 */
function Table({ className, ...props }: React.ComponentProps<"table">) {
  const label = props["aria-label"];
  const labelledBy = props["aria-labelledby"];
  const named = Boolean(label || labelledBy);
  return (
    <div
      data-slot="table-container"
      role={named ? "region" : undefined}
      tabIndex={named ? 0 : undefined}
      aria-label={label}
      aria-labelledby={labelledBy}
      className="relative w-full overflow-x-auto rounded-[inherit] outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
    >
      <table
        data-slot="table"
        className={cn("w-full caption-bottom font-main-ui-body", className)}
        {...props}
      />
    </div>
  );
}

function TableHeader({ className, ...props }: React.ComponentProps<"thead">) {
  return <thead data-slot="table-header" className={cn("[&_tr]:border-b", className)} {...props} />;
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

function TableRow({ className, ...props }: React.ComponentProps<"tr">) {
  return (
    <tr
      data-slot="table-row"
      className={cn(
        "border-b border-border-subtle transition-colors hover:bg-surface-base has-aria-expanded:bg-surface-base data-[state=selected]:bg-surface-subtle",
        className,
      )}
      {...props}
    />
  );
}

function TableHead({ className, ...props }: React.ComponentProps<"th">) {
  return (
    <th
      data-slot="table-head"
      // MemoryOS headers carry long Vietnamese labels, so wrapping stays on by default.
      className={cn(
        "h-10 px-2 text-left align-middle font-secondary-action text-content-secondary [&:has([role=checkbox])]:pr-0",
        className,
      )}
      {...props}
    />
  );
}

function TableCell({ className, ...props }: React.ComponentProps<"td">) {
  return (
    <td
      data-slot="table-cell"
      className={cn("p-2 align-middle [&:has([role=checkbox])]:pr-0", className)}
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
