import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { getChatModelDefaultOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { setChatModelDefault } from "@/lib/hey-api/sdk.gen";
import type { GetChatModelDefaultResponse } from "@/lib/hey-api/types.gen";
import { sameOriginMutationHeaders } from "@/lib/api";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  refreshModelCatalog,
  tenantCandidate,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
} from "./model-catalog";
import { useModelAction } from "./use-model-action";
import { ModelPicker } from "./model-picker";

type Catalog = {
  providers: ManagedProvider[];
  models: ManagedModel[];
  adapters: InstalledAdapter[];
};
type Selection = GetChatModelDefaultResponse;

function SelectionEditor({
  selection,
  reload,
  providers,
  models,
  adapters,
}: Catalog & {
  selection: Selection;
  reload: () => Promise<Selection>;
}) {
  const ui = useAppTranslation();
  const client = useQueryClient();
  const action = useModelAction();
  const [baseline, setBaseline] = useState(selection);
  const [chosen, setChosen] = useState(selection.modelConfigurationId ?? "");
  const [saved, setSaved] = useState(false);
  const candidates = models.filter((model) => {
    const provider = providers.find((entry) => entry.id === model.providerId);
    return provider && tenantCandidate(model, provider, adapters);
  });
  const savedModel = models.find((model) => model.id === baseline.modelConfigurationId);
  const savedProvider =
    savedModel && providers.find((provider) => provider.id === savedModel.providerId);
  const savedHidden =
    baseline.modelConfigurationId &&
    !candidates.some((model) => model.id === baseline.modelConfigurationId);
  const candidateChosen = candidates.some((model) => model.id === chosen);
  const conflicted = action.conflict || selection.revision !== baseline.revision;

  async function save() {
    if (
      action.pending ||
      conflicted ||
      !candidateChosen ||
      chosen === (baseline.modelConfigurationId ?? "")
    )
      return;
    setSaved(false);
    try {
      await action.run(async (signal) => {
        const result = await setChatModelDefault({
          query: { revision: baseline.revision, modelConfigurationId: chosen },
          headers: sameOriginMutationHeaders,
          signal,
          throwOnError: true,
        });
        signal.throwIfAborted();
        setBaseline(result.data);
        setChosen(result.data.modelConfigurationId ?? "");
        await refreshModelCatalog(client);
        signal.throwIfAborted();
        setSaved(true);
      });
    } catch {
      /* Action errors are safe strings, never provider payloads. */
    }
  }

  async function reconcile() {
    action.cancel();
    setSaved(false);
    try {
      await action.run(async (signal) => {
        const current = await reload();
        signal.throwIfAborted();
        setBaseline(current);
        action.reconciled();
      });
    } catch {
      /* Keep the selection draft; require manual retry after review. */
    }
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0">
          <h3 className="font-main-ui-action">{ui("Default model")}</h3>
          <p className="font-secondary-body text-content-muted">
            {ui("This model will be used by Chat by default in your conversations.")}
          </p>
        </div>
        <ModelPicker
          ariaLabel={ui("Tenant model default")}
          value={chosen}
          disabled={action.pending}
          placeholder={ui("Choose an eligible model")}
          onChange={(modelId) => {
            setChosen(modelId);
            setSaved(false);
          }}
          options={[
            ...(savedHidden && savedModel && savedProvider
              ? [
                  {
                    model: savedModel,
                    provider: savedProvider,
                    disabled: true,
                    note: ui("saved; hidden or unavailable"),
                  },
                ]
              : []),
            ...(chosen &&
            chosen !== baseline.modelConfigurationId &&
            !candidates.some((model) => model.id === chosen)
              ? (() => {
                  const draftModel = models.find((model) => model.id === chosen);
                  const draftProvider =
                    draftModel &&
                    providers.find((provider) => provider.id === draftModel.providerId);
                  return draftModel && draftProvider
                    ? [
                        {
                          model: draftModel,
                          provider: draftProvider,
                          disabled: true,
                          note: ui("draft no longer eligible"),
                        },
                      ]
                    : [];
                })()
              : []),
            ...candidates.flatMap((model) => {
              const provider = providers.find((entry) => entry.id === model.providerId);
              return provider ? [{ model, provider }] : [];
            }),
          ]}
        />
      </div>
      {!candidates.length && <p role="status">{ui("No eligible models are available.")}</p>}
      {conflicted && (
        <div role="alert" className="space-y-2">
          <p>
            {ui(
              "The saved selection changed or conflicted. Refresh its own revision and review before retrying; model/provider revisions are not selection revisions.",
            )}
          </p>
          <Button prominence="secondary" disabled={action.pending} onClick={() => void reconcile()}>
            {ui("Reconcile saved selection")}
          </Button>
        </div>
      )}
      {action.error && <p role="alert">{ui(action.error)}</p>}
      {saved && <p role="status">{ui("Default saved. Existing transcript is unchanged.")}</p>}
      {chosen !== (baseline.modelConfigurationId ?? "") && (
        <Button
          pending={action.pending}
          disabled={conflicted || !candidateChosen}
          onClick={() => void save()}
        >
          {ui("Save Tenant default")}
        </Button>
      )}
    </div>
  );
}

/** The Tenant Chat default; a per-Persona override belongs with the Persona, not the catalog. */
export function TenantDefault(catalog: Catalog) {
  const ui = useAppTranslation();
  const tenant = useQuery({ ...getChatModelDefaultOptions(), retry: false });
  if (tenant.isPending) return <p role="status">{ui("Loading Tenant default…")}</p>;
  if (tenant.isError)
    return (
      <div role="alert" className="space-y-2">
        <p>{ui("Tenant default could not be loaded.")}</p>
        <Button prominence="secondary" onClick={() => void tenant.refetch()}>
          {ui("Retry Tenant default")}
        </Button>
      </div>
    );
  return (
    <SelectionEditor
      {...catalog}
      selection={tenant.data}
      reload={async () => {
        const result = await tenant.refetch({ throwOnError: true });
        if (!result.data) throw new Error("Selection unavailable");
        return result.data;
      }}
    />
  );
}
