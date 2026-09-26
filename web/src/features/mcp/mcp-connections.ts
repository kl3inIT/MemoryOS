import { useQuery, type QueryClient } from "@tanstack/react-query";
import {
  listMcpConnectionsOptions,
  listMcpConnectionsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";

/**
 * The server slug and tool of a model-facing MCP tool name, `mcp_<slug>_<tool>`. The slug has no underscore, so the
 * split is unambiguous; any other name is not an MCP tool.
 */
export function parseMcpToolName(name: string): { slug: string; tool: string } | null {
  const [, slug, tool] = /^mcp_([a-z0-9]{1,16})_(.+)$/.exec(name) ?? [];
  return slug && tool ? { slug, tool } : null;
}

/** The MCP servers the signed-in User may use, with their own connection state. */
export function useMcpConnections() {
  return useQuery({ ...listMcpConnectionsOptions(), staleTime: 30_000 });
}

/** Refreshes the signed-in User's connections after one of them changes. */
export function invalidateMcpConnections(cache: QueryClient) {
  return cache.invalidateQueries({ queryKey: listMcpConnectionsQueryKey() });
}
