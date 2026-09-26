import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const nativeSelectVariants = cva(
  "w-full min-w-0 rounded-lg border border-border-subtle bg-surface-raised px-3 font-main-ui-body text-content-primary outline-none transition-[color,background-color,border-color,box-shadow] duration-150 hover:border-border-default focus-visible:border-focus-ring focus-visible:shadow-[inset_0_0_0_2px_var(--surface-sunken)] disabled:cursor-not-allowed disabled:border-transparent disabled:bg-surface-sunken disabled:text-content-disabled aria-invalid:border-status-danger-border",
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

type NativeSelectProps = Omit<React.ComponentProps<"select">, "size"> &
  VariantProps<typeof nativeSelectVariants>;

/**
 * The browser's own select in the control frame. MemoryOS keeps the platform arrow and puts `className` on the
 * `<select>` itself (the shadcn version wraps it and draws a chevron), so width and density stay with the caller.
 */
function NativeSelect({ className, size = "md", ...props }: NativeSelectProps) {
  return (
    <select
      {...props}
      data-slot="native-select"
      data-size={size}
      className={cn(nativeSelectVariants({ size }), className)}
    />
  );
}

function NativeSelectOption({ className, ...props }: React.ComponentProps<"option">) {
  return (
    <option
      data-slot="native-select-option"
      className={cn("bg-[Canvas] text-[CanvasText]", className)}
      {...props}
    />
  );
}

function NativeSelectOptGroup({ className, ...props }: React.ComponentProps<"optgroup">) {
  return (
    <optgroup
      data-slot="native-select-optgroup"
      className={cn("bg-[Canvas] text-[CanvasText]", className)}
      {...props}
    />
  );
}

export {
  NativeSelect,
  NativeSelectOptGroup,
  NativeSelectOption,
  nativeSelectVariants,
  type NativeSelectProps,
};
