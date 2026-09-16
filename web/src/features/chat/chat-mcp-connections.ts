import { useQuery } from "@tanstack/react-query";
import { listMcpConnections } from "@/lib/hey-api/sdk.gen";

export const mcpConnectionsKey = ["mcp", "connections"] as const;

/** The MCP servers the signed-in User may use, with their own connection state. */
export function useMcpConnections() {
  return useQuery({
    queryKey: mcpConnectionsKey,
    queryFn: async () => (await listMcpConnections()).data ?? [],
    staleTime: 30_000,
  });
}
