import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  listChatProvidersOptions,
  listConfiguredChatModelsOptions,
  listOcrConnectionsOptions,
  listOcrConnectionsQueryKey,
  listOcrProvidersOptions,
  saveOcrConnectionMutation,
  selectOcrProviderMutation,
  testOcrConnectionMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { OcrProviderResponse } from "@/lib/hey-api/types.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";

export type OcrProvider = OcrProviderResponse["provider"];

/** A catalog model that reads images, with the provider it is called through. */
export type VisionModel = {
  id: string;
  name: string;
  providerName: string;
  dataBoundary: "INTERNAL" | "EXTERNAL";
};

/** A failed OCR connection request as the page shows it. */
export function ocrProblem(error: unknown): ErrorMessage {
  return presentProblem(error, "mutation").message;
}

/** The installed OCR providers, the Tenant's connections and the choice of the reader. */
export function useOcrConnections(enabled: boolean) {
  const cache = useQueryClient();
  const providers = useQuery({ ...listOcrProvidersOptions(), enabled, retry: false });
  const connections = useQuery({ ...listOcrConnectionsOptions(), enabled, retry: false });
  const select = useMutation({
    ...selectOcrProviderMutation(),
    onSuccess: () => cache.invalidateQueries({ queryKey: listOcrConnectionsQueryKey() }),
  });
  return { providers, connections, select };
}

/** The models of enabled providers that read images: what a model connection may read with. */
export function useVisionModels(enabled: boolean) {
  const providers = useQuery({ ...listChatProvidersOptions(), enabled, retry: false });
  const usable = (providers.data ?? []).filter((provider) => provider.enabled);
  const configured = useQueries({
    queries: usable.map((provider) => ({
      ...listConfiguredChatModelsOptions({ path: { providerId: provider.id } }),
      enabled,
      retry: false,
    })),
  });
  const models: VisionModel[] = usable.flatMap((provider, index) =>
    (configured[index]?.data ?? [])
      .filter((model) => model.settings.capabilities.vision)
      .map((model) => ({
        id: model.id,
        name: model.displayName,
        providerName: provider.name,
        dataBoundary: provider.dataBoundary,
      })),
  );
  return {
    models,
    pending: providers.isPending || configured.some((query) => query.isPending),
    failed: providers.isError || configured.some((query) => query.isError),
  };
}

/** Saving and testing one provider's connection. */
export function useOcrConnectionMutations() {
  const cache = useQueryClient();
  const save = useMutation({
    ...saveOcrConnectionMutation(),
    onSuccess: () => cache.invalidateQueries({ queryKey: listOcrConnectionsQueryKey() }),
  });
  const test = useMutation(testOcrConnectionMutation());
  return { save, test };
}
