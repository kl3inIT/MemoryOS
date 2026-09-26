import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  getChatModelDefaultOptions,
  listChatProviderAdaptersOptions,
  listChatProvidersOptions,
  listChatProvidersQueryKey,
  listConfiguredChatModelsOptions,
  listConfiguredChatModelsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { deleteChatModel, deleteChatProvider } from "@/lib/hey-api/sdk.gen";
import type { ProviderPreset } from "./add-provider-section";
import { refreshModelCatalog, type ManagedModel, type ManagedProvider } from "./model-catalog";
import { useModelCatalogBusy, useModelMutation } from "./model-mutation";

export type Editor =
  | { kind: "provider"; initial?: ManagedProvider; preset?: ProviderPreset }
  | { kind: "model"; providerId: string; initial?: ManagedModel; modelName?: string }
  | { kind: "discovery"; providerId: string };
export type Deletion =
  | { kind: "provider"; provider: ManagedProvider }
  | { kind: "model"; model: ManagedModel };

/** A Tenant holds at most this many providers; connecting another is refused. */
const providerLimit = 64;

/**
 * The model catalog the administration reads, the open editor and deletion, and the page-wide catalog
 * operations: reloading everything and deleting a provider or a model.
 */
export function useModelsAdministration() {
  const client = useQueryClient();
  const providers = useQuery({ ...listChatProvidersOptions(), retry: false });
  const adapters = useQuery({ ...listChatProviderAdaptersOptions(), retry: false });
  // Bounded management reads are cached by provider UUID, not rediscovered on each render.
  const configured = useQueries({
    queries: (providers.data ?? []).map((provider) => ({
      ...listConfiguredChatModelsOptions({ path: { providerId: provider.id } }),
      retry: false,
    })),
  });
  const tenantDefault = useQuery({ ...getChatModelDefaultOptions(), retry: false });
  const models = configured.flatMap((query) => query.data ?? []);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [deletion, setDeletion] = useState<Deletion | null>(null);
  const busy = useModelCatalogBusy();
  const [conflict, setConflict] = useState(false);
  const catalogPending =
    providers.isPending || adapters.isPending || configured.some((query) => query.isPending);
  const catalogError =
    providers.isError || adapters.isError || configured.some((query) => query.isError);
  const unavailable = catalogPending || catalogError || busy;
  const defaultModelId = tenantDefault.data?.modelConfigurationId ?? null;

  const reloading = useModelMutation(async (signal) => {
    await Promise.all([refreshModelCatalog(client), adapters.refetch({ throwOnError: true })]);
    signal.throwIfAborted();
    setConflict(false);
  });

  const removing = useModelMutation(
    async (signal) => {
      if (!deletion) return;
      if (deletion.kind === "provider")
        await deleteChatProvider({
          path: { providerId: deletion.provider.id },
          query: { revision: deletion.provider.revision },
          signal,
        });
      else
        await deleteChatModel({
          path: { modelId: deletion.model.id },
          query: { revision: deletion.model.revision },
          signal,
        });
      signal.throwIfAborted();
      if (deletion.kind === "provider") {
        const providerId = deletion.provider.id;
        client.setQueriesData<ManagedProvider[]>(
          { queryKey: listChatProvidersQueryKey() },
          (previous) => previous?.filter((provider) => provider.id !== providerId),
        );
        const removedModels = {
          queryKey: listConfiguredChatModelsQueryKey({ path: { providerId } }),
        };
        await client.cancelQueries(removedModels);
        client.removeQueries(removedModels);
      }
      await refreshModelCatalog(client);
      signal.throwIfAborted();
    },
    { onConflict: () => setConflict(true) },
  );

  async function reload() {
    if (busy) return;
    try {
      await reloading.run();
    } catch {
      /* Safe feedback on the page. */
    }
  }

  async function remove() {
    if (!deletion || unavailable) throw new Error("Refresh the catalog before deleting.");
    if (conflict)
      throw new Error(
        "Cancel this dialog, reload the catalog, and review the current configuration before deleting again.",
      );
    await removing.run();
  }

  function startDeletion(next: Deletion) {
    reloading.cancel();
    setDeletion(next);
  }

  function cancelDeletion() {
    setDeletion(null);
    removing.cancel();
  }

  const isDefaultProvider = (provider: ManagedProvider) =>
    models.some((model) => model.providerId === provider.id && model.id === defaultModelId);
  // The provider serving the Tenant default comes first, as Onyx lists its default connection.
  const sortedProviders = [...(providers.data ?? [])].sort(
    (a, b) => Number(isDefaultProvider(b)) - Number(isDefaultProvider(a)),
  );
  const editedProviderId =
    editor?.kind === "model" || editor?.kind === "discovery" ? editor.providerId : undefined;

  return {
    providers,
    adapters,
    models,
    sortedProviders,
    isDefaultProvider,
    defaultModelId,
    catalogPending,
    catalogError,
    unavailable,
    busy,
    atProviderLimit: (providers.data?.length ?? 0) >= providerLimit,
    editor,
    setEditor,
    editedProvider: providers.data?.find((provider) => provider.id === editedProviderId),
    deletion,
    startDeletion,
    cancelDeletion,
    reloading,
    reload,
    remove,
  };
}
