import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Outlet } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import {
  AccessNotProvisionedScreen,
  SessionErrorScreen,
  SessionLoadingScreen,
  SignInScreen,
} from "@/features/identity/session-states";
import { isUnauthenticated } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { getCurrentIdentity } from "@/lib/hey-api/sdk.gen";
import { acceptCurrentIdentity } from "@/lib/query-client";

const currentIdentityQueryKey = getCurrentIdentityQueryKey();

export function ApplicationSessionBoundary({ children }: { children?: ReactNode } = {}) {
  const queryClient = useQueryClient();
  const sessionQuery = useQuery({
    queryKey: currentIdentityQueryKey,
    queryFn: async ({ signal }) => {
      const { data } = await getCurrentIdentity({ signal, throwOnError: true });
      acceptCurrentIdentity(queryClient, data);
      return data;
    },
    refetchOnWindowFocus: "always",
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

  if (!sessionQuery.data.tenant) {
    return <AccessNotProvisionedScreen />;
  }

  return (
    <ApplicationSessionProvider
      key={sessionQuery.data.actorId}
      session={{ ...sessionQuery.data, tenant: sessionQuery.data.tenant }}
    >
      {children ?? <Outlet />}
    </ApplicationSessionProvider>
  );
}
