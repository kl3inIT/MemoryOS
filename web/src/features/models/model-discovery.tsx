import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import { createChatModel } from "@/lib/hey-api/sdk.gen";
import { CatalogDialog } from "./catalog-dialog";
import {
  modelBody,
  refreshModelCatalog,
  reportedDraft,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
  type ReportedModel,
} from "./model-catalog";
import { ProviderModelsField } from "./provider-models-field";
import { useModelAction } from "./use-model-action";

/**
 * "Fetch models from the provider" on a saved connection: only the listing, searched and picked, and one button that
 * adds what was picked. The same list the provider form carries, with the stored key, so nothing else is edited here.
 */
export function ModelDiscovery({
  provider,
  adapter,
  models,
  onClose,
}: {
  provider: ManagedProvider;
  adapter: InstalledAdapter;
  models: ManagedModel[];
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const client = useQueryClient();
  const action = useModelAction();
  const [chosen, setChosen] = useState<ReportedModel[]>([]);
  const [failed, setFailed] = useState<string[]>([]);

  async function add() {
    if (chosen.length === 0 || action.pending) return;
    setFailed([]);
    try {
      await action.run(async (signal) => {
        const rejected: string[] = [];
        for (const model of chosen) {
          try {
            await createChatModel({
              path: { providerId: provider.id },
              body: modelBody(reportedDraft(model, adapter)),
              headers: sameOriginMutationHeaders,
              signal,
              throwOnError: true,
            });
          } catch (cause) {
            signal.throwIfAborted();
            if (cause instanceof Error && cause.name === "AbortError") throw cause;
            rejected.push(model.modelName);
          }
          signal.throwIfAborted();
        }
        await refreshModelCatalog(client);
        signal.throwIfAborted();
        if (rejected.length === 0) {
          onClose();
          return;
        }
        setChosen(chosen.filter((model) => rejected.includes(model.modelName)));
        setFailed(rejected);
      });
    } catch {
      /* Safe action-local feedback is owned by useModelAction. */
    }
  }

  return (
    <CatalogDialog
      title={ui("Fetch models from the provider")}
      description={provider.name}
      onClose={onClose}
    >
      <div className="space-y-4">
        <ProviderModelsField
          connection={() => ({
            adapterType: provider.adapterType,
            baseUrl: provider.baseUrl,
            providerId: provider.id,
            credential: { action: "KEEP" },
          })}
          configured={models}
          selected={chosen}
          onSelected={setChosen}
          disabled={action.pending}
          listOnOpen
        />
        {failed.length > 0 && (
          <p role="alert">
            {ui(appText("These models were not added: {{models}}", { models: failed.join(", ") }))}
          </p>
        )}
        {action.error && <p role="alert">{ui(action.error)}</p>}
        <div className="flex flex-wrap justify-end gap-2">
          <Button
            prominence="secondary"
            onClick={() => {
              action.cancel();
              onClose();
            }}
          >
            {ui("Close")}
          </Button>
          <Button
            pending={action.pending}
            disabled={chosen.length === 0}
            onClick={() => void add()}
          >
            {ui(appText("Add {{count}} models", { count: chosen.length }))}
          </Button>
        </div>
      </div>
    </CatalogDialog>
  );
}
