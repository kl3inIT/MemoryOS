// Adapted from assistant-ui (MIT), elements-error-state registry, 2026-09-12.
import type { ComponentProps, ReactNode } from "react";
import { CircleAlertIcon, RefreshCwIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface ErrorStateProps extends Omit<ComponentProps<"div">, "children" | "role"> {
  title: string;
  detail: ReactNode;
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
        // The registry's quiet red banner, in the status tokens so both themes keep their contrast.
        "flex w-full min-w-0 flex-wrap items-start gap-2.5 rounded-2xl bg-status-danger-surface px-4 py-3 text-sm",
        className,
      )}
    >
      <CircleAlertIcon
        aria-hidden="true"
        className="mt-0.5 size-4 shrink-0 text-status-danger-content"
      />
      <div className="min-w-0 flex-1 basis-40 wrap-anywhere">
        <p className="font-medium text-status-danger-content">{title}</p>
        <div className="mt-0.5 text-sm leading-snug text-content-secondary [&_a]:underline [&_a]:underline-offset-2">
          {detail}
        </div>
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
