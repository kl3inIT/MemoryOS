import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const selectVariants = cva(
  "w-full rounded-lg border border-border-default bg-surface-base px-3 font-main-ui-body text-content-primary outline-none transition-[color,background-color,border-color,box-shadow] duration-150 hover:border-border-strong focus-visible:border-focus-ring focus-visible:ring-3 focus-visible:ring-focus-ring/30 disabled:cursor-not-allowed disabled:border-border-subtle disabled:bg-surface-sunken disabled:text-content-disabled",
  {
    variants: {
      size: {
        sm: "h-[var(--control-height-sm)]",
        md: "h-[var(--control-height-md)]",
        lg: "h-[var(--control-height-lg)]",
      },
    },
    defaultVariants: {
      size: "md",
    },
  },
);

type SelectProps = Omit<React.ComponentProps<"select">, "size"> &
  VariantProps<typeof selectVariants>;

function Select({ className, size = "md", ...props }: SelectProps) {
  return (
    <select
      {...props}
      data-slot="select"
      data-size={size}
      className={cn(selectVariants({ size }), className)}
    />
  );
}

export { Select, selectVariants, type SelectProps };
