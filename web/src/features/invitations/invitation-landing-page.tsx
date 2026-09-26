import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowRight, CircleAlert, Clock3, ShieldCheck, UserCheck } from "lucide-react";
import { AuthFrame } from "@/components/states/auth-frame";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { formatInvitationDate } from "@/features/invitations/invitation-presentation";
import { getCurrentInvitationOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

export function InvitationLandingPage({ reason }: { reason?: string }) {
  const ui = useAppTranslation();

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
        <div
          role="status"
          className="flex items-center justify-center gap-2 py-8 font-main-ui-body text-content-muted"
        >
          <Spinner aria-hidden="true" />
          {ui("Checking your invitation…")}
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
        <UserCheck className="size-5" aria-hidden="true" />
      </div>
      <p className="font-secondary-body text-content-muted">{ui("Tenant invitation")}</p>
      <h1 className="mt-3 max-w-xl font-heading-h2 text-content-primary">
        {ui("Join {{name}}", { name: invitation.data.tenantDisplayName })}
      </h1>
      <p className="mt-3 font-main-ui-body text-content-muted">
        {ui(
          "Sign in or create your local account. Once your verified email matches, MemoryOS will add you to the Tenant and take you to the application.",
        )}
      </p>

      <Alert role="note" className="mt-6">
        <ShieldCheck aria-hidden="true" />
        <AlertTitle>{ui("Your access is scoped")}</AlertTitle>
        <AlertDescription>
          {ui(
            "This invitation grants Tenant member access. It does not grant administration permissions.",
          )}
        </AlertDescription>
      </Alert>

      <Button asChild size="lg" className="mt-6 w-full">
        <a href={invitation.data.continueUrl}>
          {ui("Continue to sign in")}
          <ArrowRight data-icon="inline-end" />
        </a>
      </Button>
      <p className="mt-4 flex items-center gap-1.5 font-secondary-body text-content-muted">
        <Clock3 className="size-3.5" aria-hidden="true" />
        {ui("Link expires")} {formatInvitationDate(invitation.data.expiresAt)}
      </p>
    </AuthFrame>
  );
}

function InvitationFailure({ reason }: { reason: string }) {
  const ui = useAppTranslation();

  const copy = failureCopy(reason);
  return (
    <AuthFrame>
      <div className="mb-4 flex size-10 items-center justify-center rounded-xl bg-status-danger-surface text-status-danger-content">
        <CircleAlert className="size-5" aria-hidden="true" />
      </div>
      <p className="font-secondary-body text-content-muted">{ui("Invitation help")}</p>
      <h1 className="mt-3 font-heading-h2 text-content-primary">{ui(copy.title)}</h1>
      <p className="mt-3 font-main-ui-body text-content-muted">{ui(copy.description)}</p>
      <div className="mt-6 flex flex-col gap-2">
        <Button asChild size="lg">
          <Link to="/">{ui("Go to MemoryOS")}</Link>
        </Button>
        <Button asChild size="lg" prominence="secondary">
          <a href="mailto:?subject=MemoryOS invitation help">{ui("Ask a Tenant owner")}</a>
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
