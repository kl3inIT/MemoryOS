import { createFileRoute } from "@tanstack/react-router";
import { McpEndpointSettingsPage } from "@/features/mcp/mcp-endpoint-settings-page";

export const Route = createFileRoute("/_authenticated/settings/mcp")({
  component: McpEndpointSettingsPage,
});
