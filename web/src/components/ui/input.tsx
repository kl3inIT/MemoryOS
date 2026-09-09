import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const inputVariants = cva(
  "w-full min-w-0 rounded-lg border border-border-subtle bg-surface-raised px-3 font-main-ui-body text-content-primary outline-none transition-[color,background-color,border-color,box-shadow] duration-150 placeholder:text-content-muted hover:border-border-default focus-visible:border-focus-ring focus-visible:shadow-[inset_0_0_0_2px_var(--surface-sunken)] disabled:cursor-not-allowed disabled:border-transparent disabled:bg-surface-sunken disabled:text-content-disabled file:mr-3 file:border-0 file:bg-transparent file:p-0 file:font-main-ui-action file:text-content-secondary",
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

type InputProps = Omit<React.ComponentProps<"input">, "size"> & VariantProps<typeof inputVariants>;

function Input({ className, size = "md", ...props }: InputProps) {
  return (
    <input
      {...props}
      data-slot="input"
      data-size={size}
      className={cn(inputVariants({ size }), className)}
    />
  );
}

export { Input, inputVariants, type InputProps };
