import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";
import { Progress as ProgressPrimitive } from "radix-ui";

/** The fill says how much is left: a budget or a quota nearly spent turns warning, then danger. */
const progressIndicatorVariants = cva("size-full flex-1 transition-all", {
  variants: {
    tone: {
      default: "bg-primary",
      warning: "bg-status-warning-strong",
      danger: "bg-status-danger-strong",
    },
  },
  defaultVariants: { tone: "default" },
});

function Progress({
  className,
  value,
  tone,
  ...props
}: React.ComponentProps<typeof ProgressPrimitive.Root> &
  VariantProps<typeof progressIndicatorVariants>) {
  return (
    <ProgressPrimitive.Root
      data-slot="progress"
      data-tone={tone ?? "default"}
      // The value reaches the primitive too, or the bar is announced as indeterminate however full it is.
      value={value}
      className={cn(
        "relative flex h-1 w-full items-center overflow-x-hidden rounded-full bg-muted",
        className,
      )}
      {...props}
    >
      <ProgressPrimitive.Indicator
        data-slot="progress-indicator"
        className={progressIndicatorVariants({ tone })}
        style={{ transform: `translateX(-${100 - (value || 0)}%)` }}
      />
    </ProgressPrimitive.Root>
  );
}

export { Progress };
