import { useQuery } from "@tanstack/react-query";
import { listMcpConnections } from "@/lib/hey-api/sdk.gen";

export const mcpConnectionsKey = ["mcp", "connections"] as const;

/**
 * The server slug and tool of a model-facing MCP tool name, `mcp_<slug>_<tool>`. The slug has no underscore, so the
 * split is unambiguous; any other name is not an MCP tool.
 */
export function parseMcpToolName(name: string): { slug: string; tool: string } | null {
  const match = /^mcp_([a-z0-9]{1,16})_(.+)$/.exec(name);
  return match ? { slug: match[1]!, tool: match[2]! } : null;
}

/** The MCP servers the signed-in User may use, with their own connection state. */
export function useMcpConnections() {
  return useQuery({
    queryKey: mcpConnectionsKey,
    queryFn: async () => (await listMcpConnections()).data ?? [],
    staleTime: 30_000,
  });
}
