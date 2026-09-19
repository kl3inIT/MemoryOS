import { createFileRoute } from "@tanstack/react-router";
import { GeneralSettingsPage } from "@/features/identity/general-settings-page";

export const Route = createFileRoute("/_authenticated/settings/general")({
  component: GeneralSettingsPage,
});
