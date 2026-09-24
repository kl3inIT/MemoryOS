import { createFileRoute } from "@tanstack/react-router";
import { DangerZoneSection } from "@/features/chat/settings/danger-zone-section";
import { GeneralSettingsPage } from "@/features/identity/general-settings-page";

export const Route = createFileRoute("/_authenticated/settings/general")({
  component: function GeneralSettingsRoute() {
    return <GeneralSettingsPage dangerZone={<DangerZoneSection />} />;
  },
});
