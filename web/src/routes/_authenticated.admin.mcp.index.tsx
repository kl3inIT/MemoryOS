import { createFileRoute, redirect } from "@tanstack/react-router";
import { z } from "zod";
import { McpServersPage } from "@/features/mcp/mcp-servers-page";

export const Route = createFileRoute("/_authenticated/admin/mcp/")({
  /** The OAuth callback returns here with an outcome code and the server it concerns. */
  validateSearch: z.object({
    mcp: z.string().optional().catch(undefined),
    serverId: z.string().optional().catch(undefined),
  }),
  // The callback may only name this page, so an outcome about one server is shown on that server's page.
  beforeLoad: ({ search }) => {
    if (search.serverId)
      throw redirect({
        to: "/admin/mcp/$serverId",
        params: { serverId: search.serverId },
        search: { mcp: search.mcp },
      });
  },
  component: McpServersPage,
});
