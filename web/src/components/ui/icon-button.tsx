import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { Slot } from "radix-ui";

import { disableActionChild } from "@/components/ui/action-activation";
import {
  actionVariants,
  type ActionProminence,
  type ActionTone,
} from "@/components/ui/action-styles";
import { cn } from "@/lib/utils";

const iconButtonSizes = cva(
  "group/icon-button inline-flex shrink-0 items-center justify-center rounded-lg bg-clip-padding p-0 select-none active:not-aria-[haspopup]:translate-y-px [&_svg]:pointer-events-none [&_svg]:shrink-0",
  {
    variants: {
      size: {
        sm: "size-[var(--control-height-sm)] [&_svg:not([class*='size-'])]:size-[var(--control-icon-sm)]",
        md: "size-[var(--control-height-md)] [&_svg:not([class*='size-'])]:size-[var(--control-icon-md)]",
        lg: "size-[var(--control-height-lg)] [&_svg:not([class*='size-'])]:size-[var(--control-icon-lg)]",
      },
    },
    defaultVariants: {
      size: "md",
    },
  },
);

type AccessibleName =
  | { "aria-label": string; "aria-labelledby"?: string }
  | { "aria-label"?: string; "aria-labelledby": string };

type IconButtonProps = Omit<
  React.ComponentProps<"button">,
  "aria-label" | "aria-labelledby" | "color"
> &
  AccessibleName &
  VariantProps<typeof iconButtonSizes> & {
    asChild?: boolean;
    pending?: boolean;
    tone?: ActionTone;
    prominence?: ActionProminence;
  };

function IconButton({
  className,
  tone = "default",
  prominence = "tertiary",
  size = "md",
  asChild = false,
  pending = false,
  disabled = false,
  children,
  onClick,
  onClickCapture,
  onKeyDown,
  onKeyDownCapture,
  tabIndex,
  type,
  ...props
}: IconButtonProps) {
  const Comp = asChild ? Slot.Root : "button";
  const blocked = disabled || pending;
  const blockedAsChild = asChild && blocked;
  const renderedChildren = blockedAsChild ? disableActionChild(children) : children;

  return (
    <Comp
      {...props}
      onClick={blockedAsChild ? undefined : onClick}
      onClickCapture={blockedAsChild ? undefined : onClickCapture}
      onKeyDown={blockedAsChild ? undefined : onKeyDown}
      onKeyDownCapture={blockedAsChild ? undefined : onKeyDownCapture}
      tabIndex={blockedAsChild ? undefined : tabIndex}
      type={asChild ? undefined : (type ?? "button")}
      data-slot="icon-button"
      data-tone={tone}
      data-prominence={prominence}
      data-size={size}
      data-pending={pending || undefined}
      aria-busy={pending || undefined}
      aria-disabled={asChild && blocked ? true : undefined}
      disabled={asChild ? undefined : blocked}
      className={cn(actionVariants({ tone, prominence }), iconButtonSizes({ size }), className)}
    >
      {renderedChildren}
    </Comp>
  );
}

export { IconButton, iconButtonSizes, type IconButtonProps };
