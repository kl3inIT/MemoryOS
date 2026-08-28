import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowRight, CircleAlert, Clock3, ShieldCheck, UserRoundCheck } from "lucide-react";
import { Brand } from "@/components/brand";
import { Button } from "@/components/ui/button";
import { getCurrentInvitationOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

export function InvitationLandingPage({ reason }: { reason?: string }) {
  const invitation = useQuery({
    ...getCurrentInvitationOptions(),
    enabled: !reason,
    retry: false,
  });

  if (reason) {
    return <InvitationFailure reason={reason} />;
  }

  if (invitation.isPending) {
    return (
      <InvitationFrame>
        <div className="py-8 text-center font-main-ui-body text-content-muted">
          Checking your invitation…
        </div>
      </InvitationFrame>
    );
  }

  if (invitation.isError) {
    return <InvitationFailure reason="not-available" />;
  }

  return (
    <InvitationFrame>
      <div className="mb-6 flex size-12 items-center justify-center rounded-2xl border border-border-default bg-surface-subtle text-content-primary">
        <UserRoundCheck className="size-5" aria-hidden="true" />
      </div>
      <p className="font-secondary-action text-content-muted">MEMORYOS INVITATION</p>
      <h1 className="mt-3 max-w-xl font-heading-h2 text-content-primary">
        Join {invitation.data.organizationDisplayName}
      </h1>
      <p className="mt-3 max-w-lg font-main-content-body text-content-secondary">
        Sign in or create your local account. Once your verified email matches, MemoryOS will add
        you to the Organization and take you to the application.
      </p>

      <div className="mt-7 flex items-start gap-3 rounded-xl border border-border-subtle bg-surface-subtle px-4 py-3">
        <ShieldCheck className="mt-0.5 size-4 shrink-0 text-content-secondary" aria-hidden="true" />
        <div>
          <p className="font-main-ui-action text-content-primary">Your access is scoped</p>
          <p className="mt-1 font-secondary-body text-content-muted">
            This invitation grants Organization member access. It does not grant administration
            permissions.
          </p>
        </div>
      </div>

      <Button asChild size="lg" className="mt-7 w-full sm:w-auto">
        <a href={invitation.data.continueUrl}>
          Continue to sign in
          <ArrowRight />
        </a>
      </Button>
      <p className="mt-4 flex items-center gap-1.5 font-secondary-body text-content-muted">
        <Clock3 className="size-3.5" aria-hidden="true" />
        Link expires {formatDate(invitation.data.expiresAt)}
      </p>
    </InvitationFrame>
  );
}

function InvitationFailure({ reason }: { reason: string }) {
  const copy = failureCopy(reason);
  return (
    <InvitationFrame>
      <div className="mb-6 flex size-12 items-center justify-center rounded-2xl border border-status-danger-content/20 bg-status-danger-surface text-status-danger-content">
        <CircleAlert className="size-5" aria-hidden="true" />
      </div>
      <p className="font-secondary-action text-content-muted">INVITATION HELP</p>
      <h1 className="mt-3 font-heading-h2 text-content-primary">{copy.title}</h1>
      <p className="mt-3 max-w-lg font-main-content-body text-content-secondary">
        {copy.description}
      </p>
      <div className="mt-7 flex flex-col gap-2 sm:flex-row">
        <Button asChild size="lg">
          <Link to="/">Go to MemoryOS</Link>
        </Button>
        <Button asChild size="lg" prominence="secondary">
          <a href="mailto:?subject=MemoryOS invitation help">Ask an Organization owner</a>
        </Button>
      </div>
    </InvitationFrame>
  );
}

function InvitationFrame({ children }: { children: React.ReactNode }) {
  return (
    <main className="min-h-dvh bg-surface-canvas px-5 py-6 text-content-primary sm:px-8 sm:py-10">
      <div className="mx-auto flex min-h-[calc(100dvh-3rem)] max-w-5xl flex-col rounded-3xl border border-border-subtle bg-surface-base shadow-xs sm:min-h-[calc(100dvh-5rem)]">
        <header className="flex h-16 items-center border-b border-border-subtle px-5 sm:px-7">
          <Brand />
        </header>
        <section className="flex flex-1 items-center px-5 py-12 sm:px-12 lg:px-20">
          <div className="w-full max-w-2xl">{children}</div>
        </section>
      </div>
    </main>
  );
}

function failureCopy(reason: string) {
  if (reason === "email-mismatch") {
    return {
      title: "Use the invited email",
      description:
        "You signed in successfully, but the verified email does not match this invitation. Sign out of Keycloak and continue with the invited account.",
    };
  }
  if (reason === "email-not-verified") {
    return {
      title: "Verify your email first",
      description:
        "Keycloak confirmed your account, but its email is not verified yet. Complete email verification, then open the invitation again.",
    };
  }
  if (reason === "authentication-failed") {
    return {
      title: "Sign-in was not completed",
      description:
        "Nothing was added to the Organization. Open the invitation link and try signing in again.",
    };
  }
  return {
    title: "This invitation is no longer available",
    description:
      "The link may have expired, been revoked, rotated, or already used. Ask an Organization owner for a fresh invitation.",
  };
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}
