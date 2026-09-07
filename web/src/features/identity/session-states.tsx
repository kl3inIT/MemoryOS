import { Link } from "@tanstack/react-router";
import { ArrowRight, CircleAlert, RefreshCw } from "lucide-react";
import { AuthFrame } from "@/components/states/auth-frame";
import { RoutePending } from "@/components/states/route-states";
import { Button } from "@/components/ui/button";

export function SignInScreen() {
  return (
    <AuthFrame>
      <h1 className="font-heading-h2 text-content-primary">Sign in to MemoryOS</h1>
      <p className="mt-2 font-main-ui-body text-content-muted">
        Continue with your company account to open your workspace.
      </p>
      <Button asChild size="lg" className="mt-8 h-auto min-h-10 w-full py-2 whitespace-normal">
        <a href="/oauth2/authorization/memoryos">
          Continue with company account
          <ArrowRight />
        </a>
      </Button>
    </AuthFrame>
  );
}

export function AccessNotProvisionedScreen() {
  return (
    <AuthFrame>
      <CircleAlert className="mb-4 size-6 text-content-muted" aria-hidden="true" />
      <h1 className="font-heading-h2 text-content-primary">You don’t have access yet.</h1>
      <p className="mt-3 font-main-ui-body text-content-muted">
        Your identity was verified, but it has not been added to this MemoryOS Tenant. Ask a Tenant
        owner for access, or continue with another account.
      </p>
      <Button asChild size="lg" className="mt-8 w-full">
        <a href="/oauth2/authorization/memoryos">Try another account</a>
      </Button>
    </AuthFrame>
  );
}

export function AccessDeniedScreen() {
  return (
    <AuthFrame>
      <CircleAlert className="mb-4 size-6 text-content-muted" aria-hidden="true" />
      <h1 className="font-heading-h2 text-content-primary">You don’t have access to this area.</h1>
      <p className="mt-3 font-main-ui-body text-content-muted">
        Your account is active, but it cannot manage this area. Return to your workspace to use the
        actions available to you.
      </p>
      <Button asChild prominence="secondary" className="mt-8 w-full">
        <Link to="/">Return to MemoryOS</Link>
      </Button>
    </AuthFrame>
  );
}

export function SessionErrorScreen({ onRetry }: { onRetry: () => void }) {
  return (
    <AuthFrame>
      <RefreshCw className="mb-4 size-6 text-content-muted" aria-hidden="true" />
      <h1 className="font-heading-h2 text-content-primary">We couldn’t confirm your session.</h1>
      <p className="mt-3 font-main-ui-body text-content-muted">
        Your Tenant data is unchanged. Check the MemoryOS service and try again.
      </p>
      <Button prominence="secondary" onClick={onRetry} className="mt-8 w-full">
        Try again
      </Button>
    </AuthFrame>
  );
}

export function SessionLoadingScreen() {
  return <RoutePending label="Opening MemoryOS" />;
}
