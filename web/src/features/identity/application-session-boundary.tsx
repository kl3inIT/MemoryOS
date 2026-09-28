import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Outlet } from "@tanstack/react-router";
import { useLayoutEffect, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { i18n, uiLanguage } from "@/i18n";
import { presentProblem } from "@/lib/problem-presentation";
import { Button } from "@/components/ui/button";
import { ApplicationSessionProvider } from "@/features/identity/application-session-provider";
import {
  AccessNotProvisionedScreen,
  SessionErrorScreen,
  SessionLoadingScreen,
  SignInRedirect,
} from "@/features/identity/session-states";
import { isUnauthenticated } from "@/lib/api";
import { currentIdentityQueryOptions } from "./current-identity-query";

export function ApplicationSessionBoundary({ children }: { children?: ReactNode } = {}) {
  const { t } = useTranslation(["identity", "common"]);
  const queryClient = useQueryClient();
  const sessionQuery = useQuery(currentIdentityQueryOptions(queryClient));

  useLayoutEffect(() => {
    if (!sessionQuery.data) return;
    void i18n.changeLanguage(uiLanguage(sessionQuery.data.uiLanguage));
  }, [sessionQuery.data]);

  // English loads on demand, and i18next switches once it has, so each signed-in person waits for their
  // language on entry rather than seeing another language first. Entry is remembered per actor: the same
  // person changing their language keeps the page mounted, a different actor waits for theirs.
  const wanted = sessionQuery.data ? uiLanguage(sessionQuery.data.uiLanguage) : undefined;
  const actorId = sessionQuery.data?.actorId;
  const [enteredActor, setEnteredActor] = useState<string>();
  const entered = actorId !== undefined && enteredActor === actorId;
  if (!entered && actorId !== undefined && wanted !== undefined && i18n.language === wanted) {
    setEnteredActor(actorId);
  }

  if (sessionQuery.isPending || (sessionQuery.data && !entered)) {
    return <SessionLoadingScreen />;
  }

  if (
    sessionQuery.isError &&
    (!sessionQuery.data || !presentProblem(sessionQuery.error, "backgroundRead").preserveData)
  ) {
    if (isUnauthenticated(sessionQuery.error)) {
      return <SignInRedirect />;
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
