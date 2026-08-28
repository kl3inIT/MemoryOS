import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { Slot } from "radix-ui";

import type { ActionTone } from "@/components/ui/action-styles";
import { cn } from "@/lib/utils";

const textButtonVariants = cva(
  "inline-flex shrink-0 items-center justify-center gap-1 border-0 bg-transparent p-0 font-main-ui-action whitespace-nowrap outline-none transition-colors duration-150 select-none focus-visible:ring-3 focus-visible:ring-focus-ring/40 focus-visible:ring-offset-2 focus-visible:ring-offset-surface-base disabled:pointer-events-none disabled:text-content-disabled aria-disabled:pointer-events-none aria-disabled:text-content-disabled [&_svg]:pointer-events-none [&_svg]:shrink-0",
  {
    variants: {
      tone: {
        default: "text-content-secondary hover:text-content-primary active:text-content-primary",
        danger:
          "text-status-danger-content hover:text-[var(--action-danger-tertiary-content-hover)] active:text-[var(--action-danger-tertiary-content-active)]",
      },
      size: {
        sm: "h-[var(--control-height-sm)] [&_svg:not([class*='size-'])]:size-[var(--control-icon-sm)]",
        md: "h-[var(--control-height-md)] [&_svg:not([class*='size-'])]:size-[var(--control-icon-md)]",
        lg: "h-[var(--control-height-lg)] [&_svg:not([class*='size-'])]:size-[var(--control-icon-lg)]",
      },
    },
    defaultVariants: {
      tone: "default",
      size: "md",
    },
  },
);

type TextButtonProps = Omit<React.ComponentProps<"button">, "color"> &
  VariantProps<typeof textButtonVariants> & {
    asChild?: boolean;
    pending?: boolean;
    tone?: ActionTone;
  };

function TextButton({
  className,
  tone = "default",
  size = "md",
  asChild = false,
  pending = false,
  disabled = false,
  type,
  ...props
}: TextButtonProps) {
  const Comp = asChild ? Slot.Root : "button";
  const blocked = disabled || pending;

  return (
    <Comp
      {...props}
      type={asChild ? undefined : (type ?? "button")}
      data-slot="text-button"
      data-tone={tone}
      data-size={size}
      data-pending={pending || undefined}
      aria-busy={pending || undefined}
      aria-disabled={asChild && blocked ? true : undefined}
      disabled={asChild ? undefined : blocked}
      className={cn(textButtonVariants({ tone, size }), className)}
    />
  );
}

export { TextButton, textButtonVariants, type TextButtonProps };
