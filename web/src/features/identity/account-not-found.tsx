import { lazy, Suspense, useLayoutEffect } from "react";
import { useTranslation } from "react-i18next";
import { RouteNotFound, RoutePending } from "@/components/states/route-states";
import { i18n, uiLanguage } from "@/i18n";

// Loaded on demand: the identity read and its API client would otherwise join the initial load.
const AccountLanguage = lazy(() =>
  import("./account-language").then((module) => ({ default: module.AccountLanguage })),
);

/**
 * The not-found page in the visitor's account language. It sits outside the session boundary, so it applies the
 * language itself; a visitor who is not signed in sees the default language and is not sent to sign in.
 */
export function AccountNotFound() {
  return (
    <Suspense fallback={<RoutePending />}>
      <AccountLanguage>
        {(language, settled) => <NotFoundIn language={language} settled={settled} />}
      </AccountLanguage>
    </Suspense>
  );
}

function NotFoundIn({ language, settled }: { language: string | undefined; settled: boolean }) {
  // Observed so the page renders again once i18next has switched.
  const { i18n: active } = useTranslation();
  const wanted = language === undefined ? undefined : uiLanguage(language);
  useLayoutEffect(() => {
    if (wanted) void i18n.changeLanguage(wanted);
  }, [wanted]);
  if (!settled || (wanted !== undefined && active.language !== wanted)) return <RoutePending />;
  return <RouteNotFound />;
}
