import { Link } from "@tanstack/react-router";
import { CircleAlert, RefreshCw } from "lucide-react";
import { Brand } from "@/components/brand";
import { RoutePending } from "@/components/states/route-states";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";

export function SignInScreen() {
  return (
    <main className="grid min-h-svh place-items-center bg-background px-6 py-12 text-foreground">
      <section className="w-full max-w-sm" aria-labelledby="sign-in-heading">
        <div className="flex justify-center">
          <Brand />
        </div>

        <h1
          id="sign-in-heading"
          className="mt-10 text-center text-2xl font-semibold tracking-[-0.035em]"
        >
          Sign in to MemoryOS
        </h1>

        <Button asChild size="lg" className="mt-8 w-full">
          <a href="/oauth2/authorization/memoryos">Continue with company account</a>
        </Button>
      </section>
    </main>
  );
}

export function AccessNotProvisionedScreen() {
  return (
    <main className="flex min-h-svh flex-col bg-background text-foreground">
      <header className="flex h-14 items-center justify-between border-b border-border px-5 sm:px-8">
        <Brand />
        <span className="text-xs text-muted-foreground">Access restricted</span>
      </header>

      <div className="grid flex-1 place-items-center px-6 py-12">
        <Empty className="max-w-md">
          <EmptyHeader>
            <EmptyMedia variant="icon" className="bg-muted text-foreground">
              <CircleAlert />
            </EmptyMedia>
            <EmptyTitle
              role="heading"
              aria-level={1}
              className="text-2xl font-semibold tracking-[-0.03em]"
            >
              You don’t have access yet.
            </EmptyTitle>
            <EmptyDescription className="max-w-sm text-sm leading-6">
              Your identity was verified, but it has not been added to this MemoryOS Tenant. Ask a
              Tenant owner for access, or continue with another account.
            </EmptyDescription>
          </EmptyHeader>
          <EmptyContent>
            <Button asChild size="lg">
              <a href="/oauth2/authorization/memoryos">Try another account</a>
            </Button>
          </EmptyContent>
        </Empty>
      </div>
    </main>
  );
}

export function AccessDeniedScreen() {
  return (
    <main className="flex min-h-svh items-center justify-center bg-background p-6 text-foreground">
      <Empty className="max-w-md">
        <EmptyHeader>
          <EmptyMedia variant="icon" className="bg-muted text-foreground">
            <CircleAlert />
          </EmptyMedia>
          <EmptyTitle
            role="heading"
            aria-level={1}
            className="text-2xl font-semibold tracking-[-0.03em]"
          >
            You don’t have access to this area.
          </EmptyTitle>
          <EmptyDescription>
            Your account is active, but it cannot manage Tenant invitations or administration.
          </EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <Button asChild prominence="secondary">
            <Link to="/">Return to MemoryOS</Link>
          </Button>
        </EmptyContent>
      </Empty>
    </main>
  );
}

export function SessionErrorScreen({ onRetry }: { onRetry: () => void }) {
  return (
    <main className="flex min-h-svh items-center justify-center bg-background p-6 text-foreground">
      <Empty className="max-w-md">
        <EmptyHeader>
          <EmptyMedia variant="icon" className="bg-muted text-foreground">
            <RefreshCw />
          </EmptyMedia>
          <EmptyTitle
            role="heading"
            aria-level={1}
            className="text-2xl font-semibold tracking-[-0.03em]"
          >
            We couldn’t confirm your session.
          </EmptyTitle>
          <EmptyDescription>
            Your Tenant data is unchanged. Check the MemoryOS service and try again.
          </EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <Button prominence="secondary" onClick={onRetry}>
            Try again
          </Button>
        </EmptyContent>
      </Empty>
    </main>
  );
}

export function SessionLoadingScreen() {
  return <RoutePending label="Opening MemoryOS" />;
}
