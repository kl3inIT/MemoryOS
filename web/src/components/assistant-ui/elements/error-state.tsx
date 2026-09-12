// Adapted from assistant-ui (MIT), elements-error-state registry, 2026-09-12.
import type { ComponentProps } from "react";
import { CircleAlertIcon, RefreshCwIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface ErrorStateProps extends Omit<ComponentProps<"div">, "children" | "role"> {
  title: string;
  detail: string;
  action?: { label: string; pending: boolean; onClick: () => void };
}

/** Presentation only: the caller owns safe copy and the actual recovery command. */
export function ErrorState({ title, detail, action, className, ...props }: ErrorStateProps) {
  return (
    <div
      {...props}
      data-slot="error-state"
      role="alert"
      className={cn(
        "flex w-full min-w-0 flex-wrap items-start gap-2.5 rounded-2xl border border-border-default bg-surface-raised px-4 py-3 text-sm",
        className,
      )}
    >
      <CircleAlertIcon
        aria-hidden="true"
        className="mt-0.5 size-4 shrink-0 text-content-secondary"
      />
      <div className="min-w-0 flex-1 basis-40 [overflow-wrap:anywhere]">
        <p className="font-medium">{title}</p>
        <p className="mt-0.5 text-sm leading-snug text-content-secondary">{detail}</p>
      </div>
      {action && (
        <Button
          type="button"
          size="sm"
          prominence="secondary"
          pending={action.pending}
          onClick={action.onClick}
          className="ms-auto max-w-full whitespace-normal"
        >
          <RefreshCwIcon aria-hidden="true" className="size-3 shrink-0" />
          {action.label}
        </Button>
      )}
    </div>
  );
}
