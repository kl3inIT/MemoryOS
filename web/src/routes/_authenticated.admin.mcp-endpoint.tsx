import { createFileRoute, stripSearchParams } from "@tanstack/react-router";
import { McpEndpointAdminPage } from "@/features/mcp/mcp-endpoint-admin-page";
import {
  DEFAULT_MCP_ENDPOINT_SEARCH,
  mcpEndpointAdminSearchSchema,
} from "@/features/mcp/mcp-endpoint-admin-search";

export const Route = createFileRoute("/_authenticated/admin/mcp-endpoint")({
  validateSearch: mcpEndpointAdminSearchSchema,
  search: { middlewares: [stripSearchParams(DEFAULT_MCP_ENDPOINT_SEARCH)] },
  component: McpEndpointAdminPage,
});
