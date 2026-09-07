import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowRight, CircleAlert, Clock3, ShieldCheck, UserRoundCheck } from "lucide-react";
import { AuthFrame } from "@/components/states/auth-frame";
import { Button } from "@/components/ui/button";
import { formatInvitationDate } from "@/features/invitations/invitation-presentation";
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
      <AuthFrame>
        <div role="status" className="py-8 text-center font-main-ui-body text-content-muted">
          Checking your invitation…
        </div>
      </AuthFrame>
    );
  }

  if (invitation.isError) {
    return <InvitationFailure reason="not-available" />;
  }

  return (
    <AuthFrame>
      <div className="mb-4 flex size-10 items-center justify-center rounded-xl bg-surface-subtle text-content-secondary">
        <UserRoundCheck className="size-5" aria-hidden="true" />
      </div>
      <p className="font-secondary-body text-content-muted">Tenant invitation</p>
      <h1 className="mt-3 max-w-xl font-heading-h2 text-content-primary">
        Join {invitation.data.tenantDisplayName}
      </h1>
      <p className="mt-3 font-main-ui-body text-content-muted">
        Sign in or create your local account. Once your verified email matches, MemoryOS will add
        you to the Tenant and take you to the application.
      </p>

      <div className="mt-6 flex items-start gap-3 rounded-lg border border-border-subtle bg-surface-base px-3 py-3">
        <ShieldCheck className="mt-0.5 size-4 shrink-0 text-content-secondary" aria-hidden="true" />
        <div>
          <p className="font-main-ui-action text-content-primary">Your access is scoped</p>
          <p className="mt-1 font-secondary-body text-content-muted">
            This invitation grants Tenant member access. It does not grant administration
            permissions.
          </p>
        </div>
      </div>

      <Button asChild size="lg" className="mt-6 w-full">
        <a href={invitation.data.continueUrl}>
          Continue to sign in
          <ArrowRight />
        </a>
      </Button>
      <p className="mt-4 flex items-center gap-1.5 font-secondary-body text-content-muted">
        <Clock3 className="size-3.5" aria-hidden="true" />
        Link expires {formatInvitationDate(invitation.data.expiresAt)}
      </p>
    </AuthFrame>
  );
}

function InvitationFailure({ reason }: { reason: string }) {
  const copy = failureCopy(reason);
  return (
    <AuthFrame>
      <div className="mb-4 flex size-10 items-center justify-center rounded-xl bg-status-danger-surface text-status-danger-content">
        <CircleAlert className="size-5" aria-hidden="true" />
      </div>
      <p className="font-secondary-body text-content-muted">Invitation help</p>
      <h1 className="mt-3 font-heading-h2 text-content-primary">{copy.title}</h1>
      <p className="mt-3 font-main-ui-body text-content-muted">{copy.description}</p>
      <div className="mt-6 flex flex-col gap-2">
        <Button asChild size="lg">
          <Link to="/">Go to MemoryOS</Link>
        </Button>
        <Button asChild size="lg" prominence="secondary">
          <a href="mailto:?subject=MemoryOS invitation help">Ask a Tenant owner</a>
        </Button>
      </div>
    </AuthFrame>
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
        "Nothing was added to the Tenant. Open the invitation link and try signing in again.",
    };
  }
  return {
    title: "This invitation is no longer available",
    description:
      "The link may have expired, been revoked, rotated, or already used. Ask a Tenant owner for a fresh invitation.",
  };
}
