import { useQueryClient } from "@tanstack/react-query";
import { ChevronDown } from "lucide-react";
import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  listChatProvidersOptions,
  listConfiguredChatModelsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { createChatModel, updateChatModel, validateChatModel } from "@/lib/hey-api/sdk.gen";
import { CatalogDialog } from "./catalog-dialog";
import {
  reportedDraft,
  type ReportedModel,
  compactTokens,
  findKnownModel,
  matchesKnownModel,
  millionTokenPrice,
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

type Change = <K extends keyof ModelDraft>(key: K, value: ModelDraft[K]) => void;

/** Limits and capabilities the runtime enforces; typed only for a model the catalog does not declare. */
function Specs({ draft, change }: { draft: ModelDraft; change: Change }) {
  const ui = useAppTranslation();
  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <label className="block space-y-1">
          {ui("Context window (tokens)")}
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
          {ui("Maximum output (tokens)")}
          <Input
            type="number"
            min={1}
            step={1}
            placeholder={ui("Provider default")}
            value={draft.maxOutputTokens}
            onChange={(event) => change("maxOutputTokens", event.target.value)}
          />
        </label>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        {(
          [
            ["toolCalling", "Tool calling"],
            ["vision", "Vision input"],
            ["reasoning", "Reasoning"],
          ] as const
        ).map(([key, label]) => (
          <label key={key} className="flex items-center gap-2 font-main-ui-body">
            <Checkbox
              checked={draft[key]}
              onCheckedChange={(checked) => change(key, checked === true)}
            />
            {ui(label)}
          </label>
        ))}
        <label className="flex items-center gap-2 font-main-ui-body text-content-muted">
          <Checkbox checked disabled aria-label={ui("Streaming (required)")} />
          {ui("Streaming (required)")}
        </label>
      </div>
    </div>
  );
}

function Prices({ draft, change }: { draft: ModelDraft; change: Change }) {
  const ui = useAppTranslation();
  return (
    <fieldset className="space-y-3">
      <legend className="font-main-ui-action">{ui("Pricing · USD per million tokens")}</legend>
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="block space-y-1">
          {ui("Input price")}
          <Input
            type="number"
            min={0}
            step="any"
            value={draft.inputPrice}
            onChange={(event) => change("inputPrice", event.target.value)}
          />
        </label>
        <label className="block space-y-1">
          {ui("Output price")}
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
  );
}
type ValidationObservation = { providerRevision: number; modelRevision: number; message: string };

export function ModelEditor({
  initial,
  modelName,
  reported,
  models,
  provider,
  adapter,
  onClose,
}: {
  initial?: ManagedModel;
  modelName?: string;
  /** A provider-reported model: its published limits, capabilities and prices prefill the form (MEM-130). */
  reported?: ReportedModel;
  models: ManagedModel[];
  provider: ManagedProvider;
  adapter?: InstalledAdapter;
  onClose: () => void;
}) {
  const client = useQueryClient();
  const ui = useAppTranslation();
  const action = useModelAction();
  const [baseline, setBaseline] = useState(initial);
  const [draft, setDraft] = useState(() =>
    reported
      ? reportedDraft(reported, adapter)
      : modelName
        ? changeModelDraft(modelDraft(initial, adapter), "modelName", modelName, adapter)
        : modelDraft(initial, adapter),
  );
  const [validating, setValidating] = useState(false);
  const [validation, setValidation] = useState<ValidationObservation | null>(null);
  const [saved, setSaved] = useState(false);
  const validationGeneration = useRef(0);
  const latest = baseline && models.find((model) => model.id === baseline.id);
  const dirty =
    !baseline || JSON.stringify(draft) !== JSON.stringify(modelDraft(baseline, adapter));
  const conflicted =
    action.conflict || Boolean(baseline && (!latest || latest.revision !== baseline.revision));
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

  function change<K extends keyof ModelDraft>(key: K, value: ModelDraft[K]) {
    validationGeneration.current += 1;
    if (validating) {
      action.cancel();
      setValidating(false);
    }
    setValidation(null);
    setSaved(false);
    setDraft((current) => changeModelDraft(current, key, value, adapter));
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
        setDraft(modelDraft(result.data, adapter));
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
            ? ui(
                "Saved connection reached the model. This does not certify model quality, capabilities or cancellation.",
              )
            : ui(
                "The saved connection check completed, but the model was not reachable. Review the endpoint, model name and credentials; no provider payload is shown.",
              ),
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
      title={
        baseline
          ? ui(appText("Edit model: {{name}}", { name: baseline.displayName }))
          : ui("Add model")
      }
      description={provider.name}
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
              {ui("API model name")}
              <Input
                required
                list="known-chat-models"
                maxLength={200}
                value={draft.modelName}
                onChange={(event) => change("modelName", event.target.value)}
              />
            </label>
            <label className="block space-y-1">
              {ui("Display name")}
              <Input
                required
                maxLength={200}
                value={draft.displayName}
                onChange={(event) => change("displayName", event.target.value)}
              />
            </label>
          </div>
          <datalist id="known-chat-models">
            {adapter?.knownModels.map((known) => (
              <option key={known.modelName} value={known.modelName} />
            ))}
          </datalist>
          {declared ? (
            <dl className="grid gap-x-6 gap-y-2 font-main-ui-body sm:grid-cols-2">
              <div className="flex justify-between gap-4">
                <dt className="text-content-muted">{ui("Context window (tokens)")}</dt>
                <dd className="tabular-nums">{compactTokens(Number(draft.contextWindow))}</dd>
              </div>
              <div className="flex justify-between gap-4">
                <dt className="text-content-muted">{ui("Maximum output (tokens)")}</dt>
                <dd className="tabular-nums">
                  {draft.maxOutputTokens.trim() === ""
                    ? ui("Provider default")
                    : compactTokens(Number(draft.maxOutputTokens))}
                </dd>
              </div>
              <div className="flex justify-between gap-4">
                <dt className="text-content-muted">{ui("Input price")}</dt>
                <dd className="tabular-nums">
                  {millionTokenPrice(Number(draft.inputPrice)) ?? ui("Unknown")}
                </dd>
              </div>
              <div className="flex justify-between gap-4">
                <dt className="text-content-muted">{ui("Output price")}</dt>
                <dd className="tabular-nums">
                  {millionTokenPrice(Number(draft.outputPrice)) ?? ui("Unknown")}
                </dd>
              </div>
              <div className="flex justify-between gap-4 sm:col-span-2">
                <dt className="text-content-muted">{ui("Capabilities")}</dt>
                <dd>
                  {[
                    draft.toolCalling ? ui("Tool calling") : null,
                    draft.vision ? ui("Vision input") : null,
                    draft.reasoning ? ui("Reasoning") : null,
                    ui("Streaming (required)"),
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </dd>
              </div>
            </dl>
          ) : (
            <Specs draft={draft} change={change} />
          )}
          <label className="flex items-center gap-2 font-main-ui-body">
            <Checkbox
              checked={draft.visible}
              onCheckedChange={(checked) => change("visible", checked === true)}
            />
            {ui("Visible in selection lists")}
          </label>
          <Collapsible className="group">
            <CollapsibleTrigger className="flex min-h-11 cursor-pointer items-center gap-2 font-main-ui-action focus-visible:outline-2 focus-visible:outline-focus-ring">
              <ChevronDown
                aria-hidden="true"
                className="size-4 transition-transform group-has-[[data-state=open]]:rotate-180"
              />
              {ui("Advanced options")}
            </CollapsibleTrigger>
            <CollapsibleContent className="space-y-4 pt-4">
              {declared && (
                <>
                  <Specs draft={draft} change={change} />
                  <Prices draft={draft} change={change} />
                </>
              )}
              <fieldset className="space-y-3">
                <legend className="font-main-ui-action">{ui("Request options")}</legend>
                <label className="flex items-center gap-2 font-main-ui-body">
                  <Checkbox
                    checked={draft.completionTokens}
                    onCheckedChange={(checked) => change("completionTokens", checked === true)}
                  />
                  {ui("Use maxCompletionTokens option family")}
                </label>
                {!draft.completionTokens && !draft.reasoning && (
                  <label className="block max-w-xs space-y-1">
                    {ui("Temperature")}
                    <Input
                      type="number"
                      min={0}
                      max={2}
                      step="any"
                      value={draft.temperature}
                      onChange={(event) => change("temperature", event.target.value)}
                    />
                  </label>
                )}
                {draft.reasoning && (
                  <label className="block space-y-1">
                    {ui("Reasoning effort")}
                    <Select
                      value={draft.reasoningEffort}
                      onChange={(event) => change("reasoningEffort", event.target.value)}
                    >
                      <option value="">{ui("Provider default (omitted)")}</option>
                      {["minimal", "low", "medium", "high"].map((effort) => (
                        <option key={effort} value={effort}>
                          {effort}
                        </option>
                      ))}
                    </Select>
                  </label>
                )}
              </fieldset>
            </CollapsibleContent>
          </Collapsible>
          {!declared && <Prices draft={draft} change={change} />}
        </fieldset>
        {invalid && <p role="status">{ui(invalid)}</p>}
        {conflicted && (
          <div role="alert" className="space-y-2">
            <p>
              {ui(
                "The catalog changed or conflicted. Reconcile the saved revision, review your draft, and retry manually.",
              )}
            </p>
            <Button
              prominence="secondary"
              disabled={action.pending}
              onClick={() => void reconcile()}
            >
              {ui("Reconcile saved model")}
            </Button>
          </div>
        )}
        {action.error && <p role="alert">{ui(action.error)}</p>}
        {saved && <p role="status">{ui("Model saved.")}</p>}
        {displayedValidation && <p role="status">{displayedValidation}</p>}
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
            prominence="secondary"
            pending={validating && action.pending}
            disabled={!baseline || dirty || conflicted || Boolean(invalid) || action.pending}
            onClick={() => void validate()}
          >
            {ui("Validate saved connection")}
          </Button>
          <Button
            type="submit"
            pending={action.pending && !validating}
            disabled={!dirty || conflicted || Boolean(invalid) || action.pending}
          >
            {ui("Save model")}
          </Button>
        </div>
      </form>
    </CatalogDialog>
  );
}
