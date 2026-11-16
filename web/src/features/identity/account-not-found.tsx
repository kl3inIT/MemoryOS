import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLayoutEffect } from "react";
import { useTranslation } from "react-i18next";
import { RouteNotFound, RoutePending } from "@/components/states/route-states";
import { i18n, uiLanguage } from "@/i18n";
import { currentIdentityQueryOptions } from "./current-identity-query";

/**
 * The not-found page in the visitor's account language. It sits outside the session boundary, so it reads the
 * identity itself; a visitor who is not signed in sees it in the default language and is not sent to sign in.
 */
export function AccountNotFound() {
  const queryClient = useQueryClient();
  const identity = useQuery(currentIdentityQueryOptions(queryClient));
  // Observed so the page renders again once i18next has switched.
  const { i18n: active } = useTranslation();
  const wanted = identity.data ? uiLanguage(identity.data.uiLanguage) : undefined;

  useLayoutEffect(() => {
    if (wanted) void i18n.changeLanguage(wanted);
  }, [wanted]);

  if (identity.isPending || (wanted !== undefined && active.language !== wanted)) {
    return <RoutePending />;
  }
  return <RouteNotFound />;
}
