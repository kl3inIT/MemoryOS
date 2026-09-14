import { createFileRoute } from "@tanstack/react-router";
import { GeneralSettingsPage } from "@/features/identity/general-settings-page";
import { AppShell } from "@/components/app-shell/app-shell";
import { useTranslation } from "react-i18next";

export const Route = createFileRoute("/_authenticated/settings/general")({
  component: function PersonalSettingsRoute() {
    const { t } = useTranslation("common");
    return (
      <AppShell pageTitle={t("settings")}>
        <GeneralSettingsPage />
      </AppShell>
    );
  },
});
