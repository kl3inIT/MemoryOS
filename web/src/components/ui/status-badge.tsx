import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const statusBadgeVariants = cva(
  "inline-flex items-center rounded-md font-secondary-body whitespace-nowrap",
  {
    variants: {
      tone: {
        success: "bg-status-success-surface text-status-success-content",
        warning: "bg-status-warning-surface text-status-warning-content",
        danger: "bg-status-danger-surface text-status-danger-content",
        info: "bg-status-info-surface text-status-info-content",
        neutral: "bg-surface-subtle text-content-muted",
      },
      size: {
        md: "px-2 py-0.5",
        sm: "px-1.5 py-px text-xs",
      },
    },
    defaultVariants: {
      tone: "info",
      size: "md",
    },
  },
);

export type StatusTone = NonNullable<VariantProps<typeof statusBadgeVariants>["tone"]>;

export function StatusBadge({
  tone,
  size,
  className,
  ...props
}: React.ComponentProps<"span"> & VariantProps<typeof statusBadgeVariants>) {
  return (
    <span
      data-slot="status-badge"
      className={cn(statusBadgeVariants({ tone, size }), className)}
      {...props}
    />
  );
}
