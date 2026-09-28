import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { createChatModel } from "@/lib/hey-api/sdk.gen";
import { CatalogDialog } from "@/components/composites/catalog-dialog";
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
import { useModelCatalogBusy, useModelMutation } from "./model-mutation";

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
  const busy = useModelCatalogBusy();
  const [chosen, setChosen] = useState<ReportedModel[]>([]);
  const [failed, setFailed] = useState<string[]>([]);
  const adding = useModelMutation(async (signal) => {
    const rejected: string[] = [];
    for (const model of chosen) {
      try {
        await createChatModel({
          path: { providerId: provider.id },
          body: modelBody(reportedDraft(model, adapter)),
          signal,
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

  async function add() {
    if (chosen.length === 0 || busy) return;
    setFailed([]);
    try {
      await adding.run();
    } catch {
      /* The mutation keeps only safe action-local feedback. */
    }
  }

  return (
    <CatalogDialog
      title={ui("Fetch models from the provider")}
      description={provider.name}
      onClose={onClose}
    >
      <div className="flex flex-col gap-4">
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
          disabled={busy}
          listOnOpen
        />
        {failed.length > 0 && (
          <Alert variant="destructive" role="alert">
            <AlertDescription>
              {ui(
                appText("These models were not added: {{models}}", { models: failed.join(", ") }),
              )}
            </AlertDescription>
          </Alert>
        )}
        {adding.error && (
          <Alert variant="destructive" role="alert">
            <AlertDescription>{ui(adding.error)}</AlertDescription>
          </Alert>
        )}
        <div className="flex flex-wrap justify-end gap-2">
          <Button
            prominence="secondary"
            onClick={() => {
              adding.cancel();
              onClose();
            }}
          >
            {ui("Close")}
          </Button>
          <Button
            pending={adding.pending}
            disabled={chosen.length === 0 || busy}
            onClick={() => void add()}
          >
            {ui(appText("Add {{count}} models", { count: chosen.length }))}
          </Button>
        </div>
      </div>
    </CatalogDialog>
  );
}
