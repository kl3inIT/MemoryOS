import { useAppTranslation } from "@/i18n/use-app-translation";
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
  title,
  description,
  error,
  onRetry,
  ...props
}: ApplicationErrorProps) {
  const ui = useAppTranslation();

  const details = import.meta.env.DEV && error instanceof Error ? error.message : null;

  return (
    <main
      className={cn(
        "flex min-h-dvh items-center justify-center bg-surface-base p-6 text-content-primary",
        className,
      )}
      {...props}
    >
      <Empty className="max-w-lg">
        <EmptyHeader>
          <EmptyMedia variant="icon" className="bg-muted text-foreground">
            <TriangleAlert />
          </EmptyMedia>
          <EmptyTitle role="heading" aria-level={1} className="font-heading-h2">
            {title ?? ui("Something went wrong")}
          </EmptyTitle>
          <EmptyDescription>
            {description ??
              ui("MemoryOS could not complete this request. Your data was not changed.")}
          </EmptyDescription>
          {details && (
            <p className="max-w-md break-words font-mono text-xs text-content-muted">{details}</p>
          )}
        </EmptyHeader>
        {onRetry && (
          <EmptyContent>
            <Button prominence="secondary" onClick={onRetry}>
              {ui("Try again")}
            </Button>
          </EmptyContent>
        )}
      </Empty>
    </main>
  );
}
