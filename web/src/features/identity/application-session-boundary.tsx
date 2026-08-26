import { useRef } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCan } from "@/features/identity/application-session-context";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import {
  AccessDeniedScreen,
  AccessNotProvisionedScreen,
  SessionErrorScreen,
  SessionLoadingScreen,
  SignInScreen,
} from "@/features/identity/session-states";
import { SourcesPage } from "@/features/knowledge/sources-page";
import { NewSessionPage } from "@/features/identity/new-session-page";
import { OrganizationInvitationsPage } from "@/features/invitations/organization-invitations-page";
import { isUnauthenticated } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { getCurrentIdentity } from "@/lib/hey-api/sdk.gen";

const currentIdentityQueryKey = getCurrentIdentityQueryKey();
export function ApplicationSessionBoundary({
  page = "new-session",
}: {
  page?: "new-session" | "sources" | "invitations";
}) {
  const queryClient = useQueryClient();
  const acceptedActorId = useRef<string | undefined>(undefined);
  const sessionQuery = useQuery({
    queryKey: currentIdentityQueryKey,
    queryFn: async ({ signal }) => {
      const { data } = await getCurrentIdentity({ signal, throwOnError: true });
      if (acceptedActorId.current && acceptedActorId.current !== data.actorId) {
        const queryCache = queryClient.getQueryCache();
        const identityQuery = queryCache.find({
          queryKey: currentIdentityQueryKey,
          exact: true,
        });
        for (const query of queryCache.getAll()) {
          if (query !== identityQuery) queryCache.remove(query);
        }
        queryClient.getMutationCache().clear();
      }
      acceptedActorId.current = data.actorId;
      return data;
    },
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

  if (!sessionQuery.data.organization) {
    return <AccessNotProvisionedScreen />;
  }

  return (
    <ApplicationSessionProvider
      session={{ ...sessionQuery.data, organization: sessionQuery.data.organization }}
    >
      <ApplicationPage page={page} />
    </ApplicationSessionProvider>
  );
}

function ApplicationPage({ page }: { page: "new-session" | "sources" | "invitations" }) {
  const canManageInvitations = useCan("INVITATIONS_MANAGE");
  if (page !== "new-session" && !canManageInvitations) return <AccessDeniedScreen />;
  if (page === "invitations") return <OrganizationInvitationsPage />;
  return page === "sources" ? <SourcesPage /> : <NewSessionPage />;
}
