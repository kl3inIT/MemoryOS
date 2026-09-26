import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import {
  getChatWebAvailabilityQueryKey,
  listChatWebConnectionsOptions,
  listChatWebConnectionsQueryKey,
  listChatWebEnginesMutation,
  saveChatWebConnectionMutation,
  selectChatWebProviderMutation,
  testChatWebConnectionMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";

const webProviders = [
  "BRAVE",
  "TAVILY",
  "EXA",
  "SERPER",
  "GOOGLE_PSE",
  "SEARXNG",
  "NINEROUTER",
  "FIRECRAWL",
] as const;
export type WebProvider = (typeof webProviders)[number];

export const webProviderNames: Record<WebProvider, string> = {
  BRAVE: "Brave",
  TAVILY: "Tavily",
  EXA: "Exa",
  SERPER: "Serper",
  GOOGLE_PSE: "Google PSE",
  SEARXNG: "SearXNG",
  NINEROUTER: "9Router",
  FIRECRAWL: "Firecrawl",
};

/** A failed Web connection request as the page shows it. */
export function webProblem(error: unknown): ErrorMessage {
  return presentProblem(error, "mutation", {
    CHAT_PROVIDER_UNAVAILABLE: { key: "webProviderUnavailable" },
  }).message;
}

/** Refreshes what a Web connection change affects: the connections and the Web availability Chat reads. */
function invalidateWeb(cache: QueryClient) {
  return Promise.all([
    cache.invalidateQueries({ queryKey: listChatWebConnectionsQueryKey() }),
    cache.invalidateQueries({ queryKey: getChatWebAvailabilityQueryKey() }),
  ]);
}

/** The Tenant's Web connections and the choice of the search engine and page reader in use. */
export function useWebConnections(enabled: boolean) {
  const cache = useQueryClient();
  const connections = useQuery({ ...listChatWebConnectionsOptions(), enabled, retry: false });
  const select = useMutation({
    ...selectChatWebProviderMutation(),
    onSuccess: () => invalidateWeb(cache),
  });
  return { connections, select };
}

/** Saving, testing and listing the engines of one provider's connection. */
export function useWebConnectionMutations() {
  const cache = useQueryClient();
  const save = useMutation({
    ...saveChatWebConnectionMutation(),
    onSuccess: () => invalidateWeb(cache),
  });
  const test = useMutation(testChatWebConnectionMutation());
  const engines = useMutation(listChatWebEnginesMutation());
  return { save, test, engines };
}
