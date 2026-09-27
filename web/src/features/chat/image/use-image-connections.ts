import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import {
  getChatImageAvailabilityQueryKey,
  listChatImageConnectionsOptions,
  listChatImageConnectionsQueryKey,
  listChatImageProvidersOptions,
  saveChatImageConnectionMutation,
  selectChatImageProviderMutation,
  testChatImageConnectionMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ImageProviderResponse } from "@/lib/hey-api/types.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";

export type ImageProvider = ImageProviderResponse["provider"];

/** Product names are proper nouns; the model catalog itself comes from the backend. */
export const imageProviderNames: Record<ImageProvider, string> = {
  OPENAI_IMAGE: "OpenAI Images",
  CLOUDFLARE_WORKERS_AI: "Cloudflare Workers AI",
};
export const imageProviderMarks = {
  OPENAI_IMAGE: "OPENAI",
  CLOUDFLARE_WORKERS_AI: "CLOUDFLARE",
} as const;

/** A failed image connection request as the page shows it. */
export function imageProblem(error: unknown): ErrorMessage {
  return presentProblem(error, "mutation").message;
}

/** Refreshes what an image connection change affects: the connections and the availability Chat reads. */
function invalidateImage(cache: QueryClient) {
  return Promise.all([
    cache.invalidateQueries({ queryKey: listChatImageConnectionsQueryKey() }),
    cache.invalidateQueries({ queryKey: getChatImageAvailabilityQueryKey() }),
  ]);
}

/** The image provider catalog, the Tenant's connections and the choice of the provider in use. */
export function useImageConnections(enabled: boolean) {
  const cache = useQueryClient();
  const providers = useQuery({ ...listChatImageProvidersOptions(), enabled, retry: false });
  const connections = useQuery({ ...listChatImageConnectionsOptions(), enabled, retry: false });
  const select = useMutation({
    ...selectChatImageProviderMutation(),
    onSuccess: () => invalidateImage(cache),
  });
  return { providers, connections, select };
}

/** Saving, testing and disconnecting one provider's connection. */
export function useImageConnectionMutations() {
  const cache = useQueryClient();
  const save = useMutation({
    ...saveChatImageConnectionMutation(),
    onSuccess: () => invalidateImage(cache),
  });
  const test = useMutation(testChatImageConnectionMutation());
  const select = useMutation(selectChatImageProviderMutation());
  /** Removes the stored key; a replacement is selected first so image generation stays on. */
  const disconnect = useMutation({
    mutationFn: async ({
      provider,
      replacement,
    }: {
      provider: ImageProvider;
      replacement: ImageProvider | null;
    }) => {
      if (replacement) await select.mutateAsync({ body: { provider: replacement } });
      // Selection advances revisions, so the key is removed against the stored state.
      const fresh = await cache.fetchQuery({ ...listChatImageConnectionsOptions(), staleTime: 0 });
      const current = fresh.find((connection) => connection.provider === provider);
      if (current)
        await save.mutateAsync({
          path: { provider },
          body: {
            endpoint: current.endpoint,
            model: current.model,
            revision: current.revision,
            credentialAction: "REMOVE",
          },
        });
    },
    onSettled: () => invalidateImage(cache),
  });
  return { save, test, disconnect };
}
