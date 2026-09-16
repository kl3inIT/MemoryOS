import { createFileRoute } from "@tanstack/react-router";
import { McpServersPage } from "@/features/mcp/mcp-servers-page";

export const Route = createFileRoute("/_authenticated/admin/mcp")({
  /** The OAuth callback returns here with an outcome code and the server it concerns. */
  validateSearch: (search: Record<string, unknown>) => ({
    mcp: typeof search.mcp === "string" ? search.mcp : undefined,
    serverId: typeof search.serverId === "string" ? search.serverId : undefined,
  }),
  component: McpServersPage,
});
