import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import { McpServersPage } from "@/features/mcp/mcp-servers-page";

export const Route = createFileRoute("/_authenticated/admin/mcp")({
  /** The OAuth callback returns here with an outcome code and the server it concerns. */
  validateSearch: z.object({
    mcp: z.string().optional().catch(undefined),
    serverId: z.string().optional().catch(undefined),
  }),
  component: McpServersPage,
});
