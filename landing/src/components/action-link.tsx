import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

type ActionProminence = "primary" | "secondary" | "tertiary";
type ActionSize = "md" | "lg";

// Default-tone rows of the web action matrix (web/src/components/ui/action-styles.ts), as links.
const prominenceClasses: Record<ActionProminence, string> = {
  primary:
    "border-transparent bg-[var(--action-default-primary-surface)] text-[var(--action-default-primary-content)] hover:bg-[var(--action-default-primary-surface-hover)] active:bg-[var(--action-default-primary-surface-active)]",
  secondary:
    "border-[var(--action-default-secondary-border)] bg-[var(--action-default-secondary-surface)] text-[var(--action-default-secondary-content)] hover:border-[var(--action-default-secondary-border-hover)] hover:bg-[var(--action-default-secondary-surface-hover)] hover:text-[var(--action-default-secondary-content-hover)] active:bg-[var(--action-default-secondary-surface-active)]",
  tertiary:
    "border-transparent bg-transparent text-[var(--action-default-tertiary-content)] hover:bg-[var(--action-default-tertiary-surface-hover)] hover:text-[var(--action-default-tertiary-content-hover)] active:bg-[var(--action-default-tertiary-surface-active)]",
};

const sizeClasses: Record<ActionSize, string> = {
  md: "h-[var(--control-height-md)] gap-2 px-3",
  lg: "h-[var(--control-height-lg)] gap-2 px-4",
};

type ActionLinkProps = ComponentProps<"a"> & {
  prominence?: ActionProminence;
  size?: ActionSize;
};

function ActionLink({ prominence = "primary", size = "md", className, ...props }: ActionLinkProps) {
  return (
    <a
      {...props}
      data-slot="action-link"
      className={cn(
        "inline-flex shrink-0 items-center justify-center rounded-lg border font-main-ui-action whitespace-nowrap transition-colors duration-150 outline-none select-none focus-visible:ring-3 focus-visible:ring-focus-ring/40 focus-visible:ring-offset-2 focus-visible:ring-offset-surface-base [&_svg]:size-[var(--control-icon-md)] [&_svg]:shrink-0",
        prominenceClasses[prominence],
        sizeClasses[size],
        className,
      )}
    />
  );
}

export { ActionLink };
