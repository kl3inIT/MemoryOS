import { useQueryErrorResetBoundary } from "@tanstack/react-query";
import { Link, useRouter, type ErrorComponentProps } from "@tanstack/react-router";
import { Brand } from "@/components/brand";
import { ApplicationError } from "@/components/states/application-error";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";

export function RouteError({ error, reset }: ErrorComponentProps) {
  const router = useRouter();
  const queryErrorResetBoundary = useQueryErrorResetBoundary();

  function retry() {
    queryErrorResetBoundary.reset();
    reset();
    void router.invalidate();
  }

  return (
    <ApplicationError
      title="This page could not be loaded."
      description="The route or its data failed to load. Your Organization data is unchanged."
      error={error}
      onRetry={retry}
    />
  );
}

export function RoutePending() {
  return (
    <main
      className="flex min-h-svh items-center justify-center bg-background p-6"
      aria-label="Loading page"
    >
      <div className="flex w-56 flex-col items-center gap-5">
        <Brand compact />
        <Skeleton className="h-px w-full rounded-none" />
        <p className="text-xs font-medium text-muted-foreground">Loading page</p>
      </div>
    </main>
  );
}

export function RouteNotFound() {
  return (
    <main className="flex min-h-svh items-center justify-center bg-background p-6">
      <Empty className="max-w-lg">
        <EmptyHeader>
          <Brand />
          <p className="mt-6 text-xs font-medium text-muted-foreground">404</p>
          <EmptyTitle className="text-2xl font-semibold tracking-[-0.03em]">
            This path isn’t part of your Organization.
          </EmptyTitle>
          <EmptyDescription>No data changed. Return to MemoryOS.</EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <Button asChild variant="outline">
            <Link to="/">Return home</Link>
          </Button>
        </EmptyContent>
      </Empty>
    </main>
  );
}
