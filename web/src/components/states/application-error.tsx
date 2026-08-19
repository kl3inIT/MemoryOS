import { TriangleAlert } from "lucide-react";
import type { ComponentProps } from "react";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { cn } from "@/lib/utils";

type ApplicationErrorProps = ComponentProps<"main"> & {
  title?: string;
  description?: string;
  error?: unknown;
  onRetry?: () => void;
};

export function ApplicationError({
  className,
  title = "Something went wrong",
  description = "MemoryOS could not complete this request. Your data was not changed.",
  error,
  onRetry,
  ...props
}: ApplicationErrorProps) {
  const details = import.meta.env.DEV && error instanceof Error ? error.message : null;

  return (
    <main
      className={cn(
        "flex min-h-svh items-center justify-center bg-background p-6 text-foreground",
        className,
      )}
      {...props}
    >
      <Empty className="max-w-lg">
        <EmptyHeader>
          <EmptyMedia variant="icon" className="bg-muted text-foreground">
            <TriangleAlert />
          </EmptyMedia>
          <EmptyTitle className="text-2xl font-semibold tracking-[-0.03em]">{title}</EmptyTitle>
          <EmptyDescription>{description}</EmptyDescription>
          {details && (
            <p className="max-w-md break-words font-mono text-xs text-neutral-500">{details}</p>
          )}
        </EmptyHeader>
        {onRetry && (
          <EmptyContent>
            <Button variant="outline" onClick={onRetry}>
              Try again
            </Button>
          </EmptyContent>
        )}
      </Empty>
    </main>
  );
}
