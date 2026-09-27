import { useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  listChatProvidersOptions,
  listConfiguredChatModelsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { createChatModel, updateChatModel, validateChatModel } from "@/lib/hey-api/sdk.gen";
import {
  changeModelDraft,
  findKnownModel,
  matchesKnownModel,
  modelBody,
  modelDraft,
  modelDraftError,
  refreshModelCatalog,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
  type ModelDraft,
} from "./model-catalog";
import { useModelCatalogBusy, useModelMutation } from "./model-mutation";

export type ModelEditorProps = {
  initial?: ManagedModel;
  modelName?: string;
  models: ManagedModel[];
  provider: ManagedProvider;
  adapter?: InstalledAdapter;
};

export type ChangeDraft = <K extends keyof ModelDraft>(key: K, value: ModelDraft[K]) => void;

type ValidationObservation = { providerRevision: number; modelRevision: number; message: string };

/**
 * A model's draft and its catalog operations: save, reconcile after a conflict, and a reachability check
 * of the saved configuration that is shown only while the saved revisions and the draft are unchanged.
 */
export function useModelEditor({
  initial,
  modelName,
  models,
  provider,
  adapter,
}: ModelEditorProps) {
  const client = useQueryClient();
  const ui = useAppTranslation();
  const busy = useModelCatalogBusy();
  const [conflict, setConflict] = useState(false);
  const [baseline, setBaseline] = useState(initial);
  const [draft, setDraft] = useState(() =>
    modelName
      ? changeModelDraft(modelDraft(initial, adapter), "modelName", modelName, adapter)
      : modelDraft(initial, adapter),
  );
  const [validation, setValidation] = useState<ValidationObservation | null>(null);
  const [saved, setSaved] = useState(false);
  const validationGeneration = useRef(0);
  const latest = baseline && models.find((model) => model.id === baseline.id);
  const dirty =
    !baseline || JSON.stringify(draft) !== JSON.stringify(modelDraft(baseline, adapter));
  const conflicted =
    conflict || Boolean(baseline && (!latest || latest.revision !== baseline.revision));
  const invalid = modelDraftError(draft, adapter);
  // Limits, capabilities and prices belong to the model; typing them is only for an undeclared one.
  const declared = matchesKnownModel(draft, findKnownModel(adapter, draft.modelName));
  const displayedValidation =
    validation &&
    !dirty &&
    !conflicted &&
    baseline &&
    validation.providerRevision === provider.revision &&
    validation.modelRevision === baseline.revision
      ? validation.message
      : null;

  const saving = useModelMutation(
    async (signal) => {
      const body = modelBody(draft);
      const result = baseline
        ? await updateChatModel({
            path: { modelId: baseline.id },
            query: { revision: baseline.revision },
            body,
            signal,
          })
        : await createChatModel({ path: { providerId: provider.id }, body, signal });
      signal.throwIfAborted();
      setBaseline(result.data);
      setDraft(modelDraft(result.data, adapter));
      await refreshModelCatalog(client);
      signal.throwIfAborted();
      setSaved(true);
    },
    { onConflict: () => setConflict(true) },
  );

  const reconciling = useModelMutation(async (signal) => {
    const options = listConfiguredChatModelsOptions({ path: { providerId: provider.id } });
    await client.invalidateQueries({ queryKey: options.queryKey, refetchType: "none" });
    signal.throwIfAborted();
    const current = await client.fetchQuery({ ...options, retry: false, staleTime: 0 });
    signal.throwIfAborted();
    if (baseline) {
      const existing = current.find((model) => model.id === baseline.id);
      if (!existing) throw new Error("Model unavailable");
      setBaseline(existing);
    }
    setConflict(false);
  });

  const validating = useModelMutation(async (signal) => {
    if (!baseline) return;
    const snapshot = {
      providerId: provider.id,
      providerRevision: provider.revision,
      modelId: baseline.id,
      modelRevision: baseline.revision,
    };
    const generation = ++validationGeneration.current;
    const { data: result } = await validateChatModel({
      path: { modelId: snapshot.modelId },
      signal,
    });
    signal.throwIfAborted();
    const providerOptions = listChatProvidersOptions();
    const modelOptions = listConfiguredChatModelsOptions({
      path: { providerId: snapshot.providerId },
    });
    // Cancel earlier reads: joining an in-flight pre-validation read is not reconciliation.
    await Promise.all([
      client.cancelQueries({ queryKey: providerOptions.queryKey }),
      client.cancelQueries({ queryKey: modelOptions.queryKey }),
    ]);
    signal.throwIfAborted();
    const [providers, configured] = await Promise.all([
      client.fetchQuery({ ...providerOptions, retry: false, staleTime: 0 }),
      client.fetchQuery({ ...modelOptions, retry: false, staleTime: 0 }),
    ]);
    signal.throwIfAborted();
    if (generation !== validationGeneration.current) return;
    if (
      providers.find((entry) => entry.id === snapshot.providerId)?.revision !==
        snapshot.providerRevision ||
      configured.find((entry) => entry.id === snapshot.modelId)?.revision !== snapshot.modelRevision
    )
      return;
    setValidation({
      providerRevision: snapshot.providerRevision,
      modelRevision: snapshot.modelRevision,
      message: result.reachable
        ? ui(
            "Saved connection reached the model. This does not certify model quality, capabilities or cancellation.",
          )
        : ui(
            "The saved connection check completed, but the model was not reachable. Review the endpoint, model name and credentials; no provider payload is shown.",
          ),
    });
  });

  const change: ChangeDraft = (key, value) => {
    validationGeneration.current += 1;
    if (validating.pending) validating.cancel();
    setValidation(null);
    setSaved(false);
    setDraft((current) => changeModelDraft(current, key, value, adapter));
  };

  function cancelAll() {
    saving.cancel();
    reconciling.cancel();
    validating.cancel();
  }

  async function save() {
    if (invalid || conflicted || busy || !dirty) return;
    setValidation(null);
    setSaved(false);
    reconciling.cancel();
    validating.cancel();
    try {
      await saving.run();
    } catch {
      /* Safe action-local feedback only. */
    }
  }

  async function reconcile() {
    setValidation(null);
    setSaved(false);
    cancelAll();
    try {
      await reconciling.run();
    } catch {
      /* Keep the non-secret draft for explicit review. */
    }
  }

  async function validate() {
    if (!baseline || dirty || conflicted || invalid || busy) return;
    setValidation(null);
    saving.cancel();
    reconciling.cancel();
    try {
      await validating.run();
    } catch {
      /* HTTP failures are distinct from a successful HTTP response with reachable=false. */
    }
  }

  return {
    baseline,
    draft,
    change,
    busy,
    dirty,
    conflicted,
    invalid,
    declared,
    saved,
    displayedValidation,
    saving,
    reconciling,
    validating,
    /** One feedback line: starting an operation clears what the others reported. */
    actionError: saving.error ?? reconciling.error ?? validating.error,
    cancelAll,
    save,
    reconcile,
    validate,
  };
}
