import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { ArrowRight, CircleAlert, RefreshCw } from "lucide-react";
import { AuthFrame } from "@/components/states/auth-frame";
import { RoutePending } from "@/components/states/route-states";
import { Button } from "@/components/ui/button";

export function SignInScreen() {
  const { t } = useTranslation("identity");
  return (
    <AuthFrame>
      <h1 className="font-heading-h2 text-content-primary">{t("signIn")}</h1>
      <p className="mt-2 font-main-ui-body text-content-muted">{t("signInDescription")}</p>
      <Button asChild size="lg" className="mt-8 h-auto min-h-10 w-full py-2 whitespace-normal">
        <a href="/oauth2/authorization/memoryos">
          {t("companyAccount")}
          <ArrowRight />
        </a>
      </Button>
    </AuthFrame>
  );
}

export function AccessNotProvisionedScreen() {
  const { t } = useTranslation("identity");
  return (
    <AuthFrame>
      <CircleAlert className="mb-4 size-6 text-content-muted" aria-hidden="true" />
      <h1 className="font-heading-h2 text-content-primary">{t("notProvisioned")}</h1>
      <p className="mt-3 font-main-ui-body text-content-muted">{t("notProvisionedDescription")}</p>
      <Button asChild size="lg" className="mt-8 w-full">
        <a href="/oauth2/authorization/memoryos">{t("anotherAccount")}</a>
      </Button>
    </AuthFrame>
  );
}

export function AccessDeniedScreen() {
  const { t } = useTranslation("identity");
  return (
    <AuthFrame>
      <CircleAlert className="mb-4 size-6 text-content-muted" aria-hidden="true" />
      <h1 className="font-heading-h2 text-content-primary">{t("denied")}</h1>
      <p className="mt-3 font-main-ui-body text-content-muted">{t("deniedDescription")}</p>
      <Button asChild prominence="secondary" className="mt-8 w-full">
        <Link to="/">{t("return")}</Link>
      </Button>
    </AuthFrame>
  );
}

export function SessionErrorScreen({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation(["identity", "common"]);
  return (
    <AuthFrame>
      <RefreshCw className="mb-4 size-6 text-content-muted" aria-hidden="true" />
      <h1 className="font-heading-h2 text-content-primary">{t("identity:sessionFailed")}</h1>
      <p className="mt-3 font-main-ui-body text-content-muted">
        {t("identity:sessionFailedDescription")}
      </p>
      <Button prominence="secondary" onClick={onRetry} className="mt-8 w-full">
        {t("common:retry")}
      </Button>
    </AuthFrame>
  );
}

export function SessionLoadingScreen() {
  const { t } = useTranslation("identity");
  return <RoutePending label={t("opening")} />;
}
