import { createFileRoute } from "@tanstack/react-router";
import { ConnectionsSettingsPage } from "@/features/identity/connections-settings-page";

export const Route = createFileRoute("/_authenticated/settings/connections")({
  component: ConnectionsSettingsPage,
});
