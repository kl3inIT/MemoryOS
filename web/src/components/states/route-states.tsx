import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQueryErrorResetBoundary } from "@tanstack/react-query";
import { Link, useRouter, type ErrorComponentProps } from "@tanstack/react-router";
import { Brand } from "@/components/brand";
import { BrandLoader } from "@/components/brand-loader";
import { ApplicationError } from "@/components/states/application-error";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from "@/components/ui/empty";

export function RouteError({ error, reset }: ErrorComponentProps) {
  const ui = useAppTranslation();

  const router = useRouter();
  const queryErrorResetBoundary = useQueryErrorResetBoundary();

  function retry() {
    queryErrorResetBoundary.reset();
    reset();
    void router.invalidate();
  }

  return (
    <ApplicationError
      title={ui("This page could not be loaded.")}
      description={ui("The route or its data failed to load. Your Tenant data is unchanged.")}
      error={error}
      onRetry={retry}
    />
  );
}

export function RoutePending({ label }: { label?: string }) {
  const ui = useAppTranslation();
  return (
    <main
      className="flex min-h-dvh items-center justify-center bg-surface-base p-6"
      aria-label={label ?? ui("Loading page")}
      role="status"
    >
      <BrandLoader label={label ?? ui("Loading page")} size="lg" />
    </main>
  );
}

export function RouteNotFound() {
  const ui = useAppTranslation();

  return (
    <main className="flex min-h-dvh items-center justify-center bg-surface-base p-6">
      <Empty className="max-w-lg">
        <EmptyHeader>
          <Brand />
          <p className="mt-6 font-secondary-body text-content-muted">404</p>
          <EmptyTitle role="heading" aria-level={1} size="page">
            {ui("This path isn’t part of your Tenant.")}
          </EmptyTitle>
          <EmptyDescription>{ui("No data changed. Return to MemoryOS.")}</EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <Button asChild prominence="secondary">
            <Link to="/">{ui("Return home")}</Link>
          </Button>
        </EmptyContent>
      </Empty>
    </main>
  );
}
