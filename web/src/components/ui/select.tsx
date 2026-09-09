import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const selectVariants = cva(
  "w-full min-w-0 rounded-lg border border-border-subtle bg-surface-raised px-3 font-main-ui-body text-content-primary outline-none transition-[color,background-color,border-color,box-shadow] duration-150 hover:border-border-default focus-visible:border-focus-ring focus-visible:shadow-[inset_0_0_0_2px_var(--surface-sunken)] disabled:cursor-not-allowed disabled:border-transparent disabled:bg-surface-sunken disabled:text-content-disabled",
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
