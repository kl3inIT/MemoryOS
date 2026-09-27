import type { QueryClient } from "@tanstack/react-query";
import {
  listMcpServerOAuthClientsQueryKey,
  listMcpServersQueryKey,
  listMcpServerToolsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";

/**
 * Refreshes the administration's reads after a server changes: the server list, whose tool counts and status
 * follow every change, and, when given, that server's tools and OAuth clients.
 */
export function invalidateMcpServers(cache: QueryClient, serverId?: string) {
  const path = serverId === undefined ? undefined : { serverId };
  return Promise.all([
    cache.invalidateQueries({ queryKey: listMcpServersQueryKey() }),
    path && cache.invalidateQueries({ queryKey: listMcpServerToolsQueryKey({ path }) }),
    path && cache.invalidateQueries({ queryKey: listMcpServerOAuthClientsQueryKey({ path }) }),
  ]);
}
