import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { Slot } from "radix-ui";

import {
  actionVariants,
  type ActionProminence,
  type ActionTone,
} from "@/components/ui/action-styles";
import { cn } from "@/lib/utils";

const buttonSizes = cva(
  "group/button inline-flex shrink-0 items-center justify-center rounded-lg bg-clip-padding font-main-ui-action whitespace-nowrap select-none active:not-aria-[haspopup]:translate-y-px [&_svg]:pointer-events-none [&_svg]:shrink-0",
  {
    variants: {
      size: {
        sm: "h-[var(--control-height-sm)] gap-1.5 px-3 [&_svg:not([class*='size-'])]:size-[var(--control-icon-sm)]",
        md: "h-[var(--control-height-md)] gap-2 px-4 [&_svg:not([class*='size-'])]:size-[var(--control-icon-md)]",
        lg: "h-[var(--control-height-lg)] gap-2 px-5 [&_svg:not([class*='size-'])]:size-[var(--control-icon-lg)]",
      },
    },
    defaultVariants: {
      size: "md",
    },
  },
);

type ButtonProps = Omit<React.ComponentProps<"button">, "color"> &
  VariantProps<typeof buttonSizes> & {
    asChild?: boolean;
    pending?: boolean;
    tone?: ActionTone;
    prominence?: ActionProminence;
  };

function Button({
  className,
  tone = "default",
  prominence = "primary",
  size = "md",
  asChild = false,
  pending = false,
  disabled = false,
  type,
  ...props
}: ButtonProps) {
  const Comp = asChild ? Slot.Root : "button";
  const blocked = disabled || pending;

  return (
    <Comp
      {...props}
      type={asChild ? undefined : (type ?? "button")}
      data-slot="button"
      data-tone={tone}
      data-prominence={prominence}
      data-size={size}
      data-pending={pending || undefined}
      aria-busy={pending || undefined}
      aria-disabled={asChild && blocked ? true : undefined}
      disabled={asChild ? undefined : blocked}
      className={cn(actionVariants({ tone, prominence }), buttonSizes({ size }), className)}
    />
  );
}

export { Button, buttonSizes, type ButtonProps };
