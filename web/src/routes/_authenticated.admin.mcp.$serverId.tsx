import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import { McpServerDetailPage } from "@/features/mcp/mcp-server-detail-page";

export const Route = createFileRoute("/_authenticated/admin/mcp/$serverId")({
  validateSearch: z.object({ mcp: z.string().optional().catch(undefined) }),
  component: McpServerDetailPage,
});
