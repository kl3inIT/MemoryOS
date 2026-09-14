import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Outlet } from "@tanstack/react-router";
import { useLayoutEffect, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { i18n, uiLanguage } from "@/i18n";
import { presentProblem } from "@/lib/problem-presentation";
import { Button } from "@/components/ui/button";
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
  const { t } = useTranslation(["identity", "common"]);
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

  useLayoutEffect(() => {
    if (sessionQuery.data) void i18n.changeLanguage(uiLanguage(sessionQuery.data.uiLanguage));
  }, [sessionQuery.data]);

  if (sessionQuery.isPending) {
    return <SessionLoadingScreen />;
  }

  if (
    sessionQuery.isError &&
    (!sessionQuery.data || !presentProblem(sessionQuery.error, "backgroundRead").preserveData)
  ) {
    if (isUnauthenticated(sessionQuery.error)) {
      return <SignInScreen />;
    }

    return <SessionErrorScreen onRetry={() => void sessionQuery.refetch()} />;
  }

  if (!sessionQuery.data?.tenant) {
    return <AccessNotProvisionedScreen />;
  }

  return (
    <ApplicationSessionProvider
      key={sessionQuery.data.actorId}
      session={{ ...sessionQuery.data, tenant: sessionQuery.data.tenant }}
    >
      {sessionQuery.isError ? (
        <div
          role="status"
          className="flex flex-wrap items-center gap-2 px-4 py-2 text-content-muted"
        >
          {t("identity:backgroundFailed")}
          <Button prominence="secondary" onClick={() => void sessionQuery.refetch()}>
            {t("common:retry")}
          </Button>
        </div>
      ) : null}
      {children ?? <Outlet />}
    </ApplicationSessionProvider>
  );
}
