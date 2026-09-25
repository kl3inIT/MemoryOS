import { createFileRoute } from "@tanstack/react-router";
import { ConnectionsSettingsPage } from "@/features/mcp/connections-settings-page";

export const Route = createFileRoute("/_authenticated/settings/connections")({
  component: ConnectionsSettingsPage,
});
