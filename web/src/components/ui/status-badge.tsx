import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const statusBadgeVariants = cva(
  "inline-flex items-center gap-1 rounded-md font-secondary-body whitespace-nowrap [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-3",
  {
    variants: {
      tone: {
        success: "bg-status-success-surface text-status-success-content",
        warning: "bg-status-warning-surface text-status-warning-content",
        danger: "bg-status-danger-surface text-status-danger-content",
        info: "bg-status-info-surface text-status-info-content",
        neutral: "bg-surface-subtle text-content-muted",
      },
      /**
       * `pill` letters the tone in white on its deep emphasis fill, as Onyx draws connector status: the dark
       * theme's status surfaces are near-black, so a pill never uses them.
       */
      variant: {
        soft: "",
        pill: "gap-1.5 rounded-full border font-medium text-content-on-emphasis",
      },
      size: {
        md: "px-2 py-0.5",
        sm: "px-1.5 py-px text-xs",
      },
    },
    compoundVariants: [
      {
        variant: "pill",
        tone: "success",
        class: "border-status-success-emphasis-border bg-status-success-emphasis",
      },
      {
        variant: "pill",
        tone: "warning",
        class: "border-status-warning-emphasis-border bg-status-warning-emphasis",
      },
      {
        variant: "pill",
        tone: "danger",
        class: "border-status-danger-emphasis-border bg-status-danger-emphasis",
      },
      {
        variant: "pill",
        tone: "info",
        class: "border-status-info-emphasis-border bg-status-info-emphasis",
      },
      {
        variant: "pill",
        tone: "neutral",
        class: "border-status-neutral-emphasis-border bg-status-neutral-emphasis",
      },
    ],
    defaultVariants: {
      tone: "info",
      variant: "soft",
      size: "md",
    },
  },
);

export type StatusTone = NonNullable<VariantProps<typeof statusBadgeVariants>["tone"]>;

export function StatusBadge({
  tone,
  variant,
  size,
  className,
  ...props
}: React.ComponentProps<"span"> & VariantProps<typeof statusBadgeVariants>) {
  return (
    <span
      data-slot="status-badge"
      data-tone={tone ?? "info"}
      data-variant={variant ?? "soft"}
      className={cn(statusBadgeVariants({ tone, variant, size }), className)}
      {...props}
    />
  );
}
