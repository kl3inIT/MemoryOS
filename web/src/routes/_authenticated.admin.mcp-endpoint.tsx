import { createFileRoute } from "@tanstack/react-router";
import { McpEndpointAdminPage } from "@/features/mcp/mcp-endpoint-admin-page";

export const Route = createFileRoute("/_authenticated/admin/mcp-endpoint")({
  component: McpEndpointAdminPage,
});
