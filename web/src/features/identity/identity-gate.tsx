import { useQuery } from "@tanstack/react-query";
import {
  SessionErrorScreen,
  SessionLoadingScreen,
  SignInScreen,
} from "@/features/identity/auth-states";
import { AdminShell } from "@/features/identity/admin-shell";
import { OwnerShell } from "@/features/identity/owner-shell";
import { InvitationAdminPage } from "@/features/invitations/invitation-admin-page";
import { isUnauthenticated } from "@/lib/api";
import { getCurrentIdentityOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

export function IdentityGate({
  surface = "assistant",
}: {
  surface?: "assistant" | "admin" | "people";
}) {
  const identity = useQuery({
    ...getCurrentIdentityOptions(),
    retry: false,
  });

  if (identity.isPending) {
    return <SessionLoadingScreen />;
  }

  if (identity.isError) {
    if (isUnauthenticated(identity.error)) {
      return <SignInScreen />;
    }

    return <SessionErrorScreen onRetry={() => void identity.refetch()} />;
  }

  if (surface === "people") return <InvitationAdminPage />;
  return surface === "admin" ? <AdminShell /> : <OwnerShell />;
}
