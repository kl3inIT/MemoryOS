import { useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  listChatProvidersOptions,
  listConfiguredChatModelsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { createChatModel, updateChatModel, validateChatModel } from "@/lib/hey-api/sdk.gen";
import { CatalogDialog } from "./catalog-dialog";
import {
  changeModelDraft,
  modelBody,
  modelDraft,
  modelDraftError,
  refreshModelCatalog,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
  type ModelDraft,
} from "./model-catalog";
import { useModelAction } from "./use-model-action";

type ValidationObservation = { providerRevision: number; modelRevision: number; message: string };

export function ModelEditor({
  initial,
  models,
  provider,
  adapter,
  onClose,
}: {
  initial?: ManagedModel;
  models: ManagedModel[];
  provider: ManagedProvider;
  adapter?: InstalledAdapter;
  onClose: () => void;
}) {
  const client = useQueryClient();
  const action = useModelAction();
  const [baseline, setBaseline] = useState(initial);
  const [draft, setDraft] = useState(() => modelDraft(initial));
  const [validating, setValidating] = useState(false);
  const [validation, setValidation] = useState<ValidationObservation | null>(null);
  const [saved, setSaved] = useState(false);
  const validationGeneration = useRef(0);
  const latest = baseline && models.find((model) => model.id === baseline.id);
  const dirty = !baseline || JSON.stringify(draft) !== JSON.stringify(modelDraft(baseline));
  const conflicted =
    action.conflict || Boolean(baseline && (!latest || latest.revision !== baseline.revision));
  const invalid = modelDraftError(draft, adapter);
  const displayedValidation =
    validation &&
    !dirty &&
    !conflicted &&
    baseline &&
    validation.providerRevision === provider.revision &&
    validation.modelRevision === baseline.revision
      ? validation.message
      : null;

  function change<K extends keyof ModelDraft>(key: K, value: ModelDraft[K]) {
    validationGeneration.current += 1;
    if (validating) {
      action.cancel();
      setValidating(false);
    }
    setValidation(null);
    setSaved(false);
    setDraft((current) => changeModelDraft(current, key, value));
  }

  async function save() {
    if (invalid || conflicted || action.pending || !dirty) return;
    setValidation(null);
    setSaved(false);
    try {
      await action.run(async (signal) => {
        const body = modelBody(draft);
        const result = baseline
          ? await updateChatModel({
              path: { modelId: baseline.id },
              query: { revision: baseline.revision },
              body,
              headers: sameOriginMutationHeaders,
              signal,
              throwOnError: true,
            })
          : await createChatModel({
              path: { providerId: provider.id },
              body,
              headers: sameOriginMutationHeaders,
              signal,
              throwOnError: true,
            });
        signal.throwIfAborted();
        setBaseline(result.data);
        setDraft(modelDraft(result.data));
        await refreshModelCatalog(client);
        signal.throwIfAborted();
        setSaved(true);
      });
    } catch {
      /* Safe action-local feedback only. */
    }
  }

  async function reconcile() {
    setValidation(null);
    setSaved(false);
    action.cancel();
    try {
      await action.run(async (signal) => {
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
        action.reconciled();
      });
    } catch {
      /* Keep the non-secret draft for explicit review. */
    }
  }

  async function validate() {
    if (!baseline || dirty || conflicted || invalid || action.pending) return;
    const snapshot = {
      providerId: provider.id,
      providerRevision: provider.revision,
      modelId: baseline.id,
      modelRevision: baseline.revision,
    };
    const generation = ++validationGeneration.current;
    setValidation(null);
    setValidating(true);
    try {
      await action.run(async (signal) => {
        const { data: result } = await validateChatModel({
          path: { modelId: snapshot.modelId },
          headers: sameOriginMutationHeaders,
          signal,
          throwOnError: true,
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
          configured.find((entry) => entry.id === snapshot.modelId)?.revision !==
            snapshot.modelRevision
        )
          return;
        setValidation({
          providerRevision: snapshot.providerRevision,
          modelRevision: snapshot.modelRevision,
          message: result.reachable
            ? "Saved connection reached the model. This does not certify model quality, capabilities or cancellation."
            : "The saved connection check completed, but the model was not reachable. Review the endpoint, model name and credentials; no provider payload is shown.",
        });
      });
    } catch {
      /* HTTP failures are distinct from a successful HTTP response with reachable=false. */
    } finally {
      if (generation === validationGeneration.current) setValidating(false);
    }
  }

  return (
    <CatalogDialog
      title={baseline ? `Edit model: ${baseline.displayName}` : "Add model"}
      description={
        <span>
          {provider.name} · <span className="break-all">{provider.id}</span>. Settings describe this
          model explicitly; changing its name or profile never silently changes capabilities.
        </span>
      }
      onClose={() => {
        action.cancel();
        onClose();
      }}
    >
      <form
        className="space-y-5"
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        <fieldset className="space-y-4" disabled={action.pending && !validating}>
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="block space-y-1">
              API model name
              <Input
                required
                maxLength={200}
                value={draft.modelName}
                onChange={(event) => change("modelName", event.target.value)}
              />
            </label>
            <label className="block space-y-1">
              Display name
              <Input
                required
                maxLength={200}
                value={draft.displayName}
                onChange={(event) => change("displayName", event.target.value)}
              />
            </label>
          </div>
          <label className="block space-y-1">
            Tokenizer profile
            <Select
              value={draft.tokenizerProfile}
              onChange={(event) => change("tokenizerProfile", event.target.value)}
            >
              <option value="">Choose an installed profile</option>
              {draft.tokenizerProfile &&
                !adapter?.tokenizerProfiles.some(
                  (profile) => profile.id === draft.tokenizerProfile,
                ) && (
                  <option value={draft.tokenizerProfile}>
                    {draft.tokenizerProfile} (unavailable)
                  </option>
                )}
              {adapter?.tokenizerProfiles.map((profile) => (
                <option key={profile.id} value={profile.id}>
                  {profile.displayName} · {profile.id}
                </option>
              ))}
            </Select>
          </label>
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="block space-y-1">
              Context window (tokens)
              <Input
                type="number"
                required
                min={256}
                max={10_000_000}
                step={1}
                value={draft.contextWindow}
                onChange={(event) => change("contextWindow", event.target.value)}
              />
            </label>
            <label className="block space-y-1">
              Maximum output (tokens)
              <Input
                type="number"
                required
                min={1}
                step={1}
                value={draft.maxOutputTokens}
                onChange={(event) => change("maxOutputTokens", event.target.value)}
              />
            </label>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={draft.visible}
                onChange={(event) => change("visible", event.target.checked)}
              />
              Visible in selection lists
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked disabled />
              Streaming (required)
            </label>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={draft.toolCalling}
                onChange={(event) => change("toolCalling", event.target.checked)}
              />
              Tool calling
            </label>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={draft.vision}
                onChange={(event) => change("vision", event.target.checked)}
              />
              Vision input
            </label>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={draft.reasoning}
                onChange={(event) => change("reasoning", event.target.checked)}
              />
              Reasoning
            </label>
          </div>
          <fieldset className="space-y-3">
            <legend className="font-main-ui-action">Request options</legend>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={draft.completionTokens}
                onChange={(event) => change("completionTokens", event.target.checked)}
              />
              Use maxCompletionTokens option family
            </label>
            <p className="font-secondary-body text-content-muted">
              This boolean chooses the output-token field family, not the output limit.
              Completion-token mode removes sampling overrides. Blank optional fields are omitted.
            </p>
            {!draft.completionTokens && (
              <div className="grid gap-3 sm:grid-cols-2">
                {(
                  [
                    ["temperature", "Temperature", 0, 2],
                    ["topP", "Top P", 0, 1],
                    ["frequencyPenalty", "Frequency penalty", -2, 2],
                    ["presencePenalty", "Presence penalty", -2, 2],
                  ] as const
                ).map(([key, label, min, max]) => (
                  <label key={key} className="block space-y-1">
                    {label}
                    <Input
                      type="number"
                      min={min}
                      max={max}
                      step="any"
                      value={draft[key]}
                      onChange={(event) => change(key, event.target.value)}
                    />
                  </label>
                ))}
              </div>
            )}
            {draft.reasoning && (
              <label className="block space-y-1">
                Reasoning effort
                <Select
                  value={draft.reasoningEffort}
                  onChange={(event) => change("reasoningEffort", event.target.value)}
                >
                  <option value="">Provider default (omitted)</option>
                  {["minimal", "low", "medium", "high"].map((effort) => (
                    <option key={effort} value={effort}>
                      {effort}
                    </option>
                  ))}
                </Select>
              </label>
            )}
          </fieldset>
          <fieldset className="space-y-3">
            <legend className="font-main-ui-action">Pricing · USD per million tokens</legend>
            <p className="font-secondary-body text-content-muted">
              Leave both blank for Unknown. Explicit zero means known free pricing, not Unknown.
            </p>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block space-y-1">
                Input price
                <Input
                  type="number"
                  min={0}
                  step="any"
                  value={draft.inputPrice}
                  onChange={(event) => change("inputPrice", event.target.value)}
                />
              </label>
              <label className="block space-y-1">
                Output price
                <Input
                  type="number"
                  min={0}
                  step="any"
                  value={draft.outputPrice}
                  onChange={(event) => change("outputPrice", event.target.value)}
                />
              </label>
            </div>
          </fieldset>
        </fieldset>
        {invalid && <p role="status">{invalid}</p>}
        {conflicted && (
          <div role="alert" className="space-y-2">
            <p>
              The catalog changed or conflicted. Reconcile the saved revision, review your draft,
              and retry manually.
            </p>
            <Button
              prominence="secondary"
              disabled={action.pending}
              onClick={() => void reconcile()}
            >
              Reconcile saved model
            </Button>
          </div>
        )}
        {action.error && <p role="alert">{action.error}</p>}
        {saved && <p role="status">Model saved.</p>}
        {baseline && (
          <p className="break-all font-secondary-body text-content-muted">
            Model {baseline.id} · model revision {baseline.revision} · provider revision{" "}
            {provider.revision}
          </p>
        )}
        {displayedValidation && <p role="status">{displayedValidation}</p>}
        <p className="font-secondary-body text-content-muted">
          Validate is available only for clean saved settings and reconciles both saved revisions.
          Edits, closing and authority changes discard pending results.
        </p>
        <div className="flex flex-wrap justify-end gap-2">
          <Button
            prominence="secondary"
            onClick={() => {
              action.cancel();
              onClose();
            }}
          >
            Close
          </Button>
          <Button
            prominence="secondary"
            pending={validating && action.pending}
            disabled={!baseline || dirty || conflicted || Boolean(invalid) || action.pending}
            onClick={() => void validate()}
          >
            Validate saved connection
          </Button>
          <Button
            type="submit"
            pending={action.pending && !validating}
            disabled={!dirty || conflicted || Boolean(invalid) || action.pending}
          >
            Save model
          </Button>
        </div>
      </form>
    </CatalogDialog>
  );
}
