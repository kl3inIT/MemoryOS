import { useQuery } from "@tanstack/react-query";
import {
  SessionErrorScreen,
  SessionLoadingScreen,
  SignInScreen,
} from "@/features/identity/session-states";
import { SourcesPage } from "@/features/knowledge/sources-page";
import { NewSessionPage } from "@/features/identity/new-session-page";
import { OrganizationInvitationsPage } from "@/features/invitations/organization-invitations-page";
import { isUnauthenticated } from "@/lib/api";
import { getCurrentIdentityOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

export function ApplicationSessionBoundary({
  page = "new-session",
}: {
  page?: "new-session" | "sources" | "invitations";
}) {
  const sessionQuery = useQuery({
    ...getCurrentIdentityOptions(),
    retry: false,
  });

  if (sessionQuery.isPending) {
    return <SessionLoadingScreen />;
  }

  if (sessionQuery.isError) {
    if (isUnauthenticated(sessionQuery.error)) {
      return <SignInScreen />;
    }

    return <SessionErrorScreen onRetry={() => void sessionQuery.refetch()} />;
  }

  if (page === "invitations") return <OrganizationInvitationsPage />;
  return page === "sources" ? <SourcesPage /> : <NewSessionPage />;
}
