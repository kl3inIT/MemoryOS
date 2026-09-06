import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const statusBadgeVariants = cva(
  "inline-flex rounded-full px-2 py-0.5 font-figure-small-label tracking-wide",
  {
    variants: {
      tone: {
        success: "bg-status-success-surface text-status-success-content",
        warning: "bg-status-warning-surface text-status-warning-content",
        danger: "bg-status-danger-surface text-status-danger-content",
        info: "bg-status-info-surface text-status-info-content",
        neutral: "bg-status-info-surface text-content-muted",
      },
    },
    defaultVariants: {
      tone: "info",
    },
  },
);

export type StatusTone = NonNullable<VariantProps<typeof statusBadgeVariants>["tone"]>;

export function StatusBadge({
  tone,
  className,
  ...props
}: React.ComponentProps<"span"> & VariantProps<typeof statusBadgeVariants>) {
  return (
    <span
      data-slot="status-badge"
      className={cn(statusBadgeVariants({ tone }), className)}
      {...props}
    />
  );
}
