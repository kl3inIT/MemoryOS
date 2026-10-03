import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import {
  getChatModelDefaultOptions,
  listChatModelFlowsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { setChatModelDefault, setChatModelFlow } from "@/lib/hey-api/sdk.gen";
import type { ModelFlow } from "@/lib/hey-api/types.gen";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  modelLabel,
  refreshModelCatalog,
  tenantCandidate,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
} from "./model-catalog";
import { useModelCatalogBusy, useModelMutation } from "./model-mutation";
import type { ModelSelectorEffortOption } from "@/components/assistant-ui/elements/model-selector";
import { ModelPicker } from "./model-picker";

type Catalog = {
  providers: ManagedProvider[];
  models: ManagedModel[];
  adapters: InstalledAdapter[];
};
type Effort = ModelFlow["reasoningEffort"];
type Selection = {
  modelConfigurationId: string | null;
  revision: number;
  available?: boolean;
  reasoningEffort?: Effort;
};
type Row = {
  title: string;
  description: string;
  ariaLabel: string;
  savedMessage: string;
  /** Shown while nothing is saved, naming the model the task falls back to. */
  unsetMessage?: string;
  unavailableMessage?: string;
  /** A task row offers the reasoning level beside the model; the Chat default leaves it to each conversation. */
  efforts?: readonly ModelSelectorEffortOption[];
  save: (
    revision: number,
    modelId: string | null,
    effort: Effort | undefined,
    signal: AbortSignal,
  ) => Promise<Selection>;
};

function SelectionEditor({
  selection,
  reload,
  providers,
  models,
  adapters,
  row,
}: Catalog & {
  selection: Selection;
  reload: () => Promise<Selection>;
  row: Row;
}) {
  const ui = useAppTranslation();
  const client = useQueryClient();
  const busy = useModelCatalogBusy();
  const [conflict, setConflict] = useState(false);
  const [baseline, setBaseline] = useState(selection);
  const [chosen, setChosen] = useState(selection.modelConfigurationId ?? "");
  const [effort, setEffort] = useState(selection.reasoningEffort);
  const [saved, setSaved] = useState(false);
  // Choosing a model or a level saves it at once; the operation reads the choice from here, not from a render that
  // predates it.
  const target = useRef<{ model: string; effort: Effort | undefined }>({
    model: "",
    effort: undefined,
  });
  const saving = useModelMutation(
    async (signal) => {
      const result = await row.save(
        baseline.revision,
        target.current.model || null,
        target.current.effort,
        signal,
      );
      signal.throwIfAborted();
      setBaseline(result);
      setChosen(result.modelConfigurationId ?? "");
      setEffort(result.reasoningEffort);
      await refreshModelCatalog(client);
      signal.throwIfAborted();
      setSaved(true);
    },
    { onConflict: () => setConflict(true) },
  );
  const reconciling = useModelMutation(async (signal) => {
    const current = await reload();
    signal.throwIfAborted();
    setBaseline(current);
    setConflict(false);
  });
  const actionError = saving.error ?? reconciling.error;
  // Deleting a task model clears it, and catalog changes alter availability, without a revision change.
  if (
    selection.revision === baseline.revision &&
    (selection.modelConfigurationId !== baseline.modelConfigurationId ||
      selection.available !== baseline.available ||
      selection.reasoningEffort !== baseline.reasoningEffort)
  ) {
    setBaseline(selection);
    setChosen(selection.modelConfigurationId ?? "");
    setEffort(selection.reasoningEffort);
  }
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
  const conflicted = conflict || selection.revision !== baseline.revision;

  async function save(model: string, level: Effort | undefined) {
    target.current = { model, effort: level };
    setChosen(model);
    setEffort(level);
    setSaved(false);
    reconciling.cancel();
    try {
      await saving.run();
    } catch {
      // The picker shows what is saved again; action errors are safe strings, never provider payloads.
      setChosen(baseline.modelConfigurationId ?? "");
      setEffort(baseline.reasoningEffort);
    }
  }

  async function choose(modelId: string) {
    if (busy || conflicted || modelId === (baseline.modelConfigurationId ?? "")) return;
    if (!candidates.some((model) => model.id === modelId)) return;
    await save(modelId, effort);
  }

  async function chooseEffort(level: string) {
    if (busy || conflicted || !chosen || level === baseline.reasoningEffort) return;
    await save(chosen, level as Effort);
  }

  async function reconcile() {
    saving.cancel();
    setSaved(false);
    try {
      await reconciling.run();
    } catch {
      /* Keep the selection draft; require manual retry after review. */
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0">
          <h3 className="font-main-ui-action">{row.title}</h3>
          <p className="font-secondary-body text-content-muted">{row.description}</p>
        </div>
        <ModelPicker
          ariaLabel={row.ariaLabel}
          value={chosen}
          disabled={busy || conflicted}
          placeholder={ui("Choose an eligible model")}
          onChange={(modelId) => void choose(modelId)}
          efforts={row.efforts}
          effort={effort}
          onEffortChange={(level) => void chooseEffort(level)}
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
      {!candidates.length && (
        <p role="status">
          {ui("No eligible models are available. Add a model to a connection below.")}
        </p>
      )}
      {!baseline.modelConfigurationId && row.unsetMessage && (
        <p role="status" className="font-secondary-body text-status-warning-content">
          {row.unsetMessage}
        </p>
      )}
      {baseline.available === false && row.unavailableMessage && (
        <p role="status" className="font-secondary-body text-status-warning-content">
          {row.unavailableMessage}
        </p>
      )}
      {conflicted && (
        <Alert variant="warning" role="alert">
          <AlertDescription>
            <div className="flex flex-col items-start gap-2">
              <p>
                {ui(
                  "The saved selection changed or conflicted. Refresh its own revision and review before retrying; model/provider revisions are not selection revisions.",
                )}
              </p>
              <Button
                prominence="secondary"
                size="sm"
                disabled={busy}
                onClick={() => void reconcile()}
              >
                {ui("Reconcile saved selection")}
              </Button>
            </div>
          </AlertDescription>
        </Alert>
      )}
      {actionError && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{ui(actionError)}</AlertDescription>
        </Alert>
      )}
      {saved && (
        <p role="status" className="font-secondary-body text-content-muted">
          {row.savedMessage}
        </p>
      )}
    </div>
  );
}

function LoadFailure({
  message,
  retry,
  onRetry,
}: {
  message: string;
  retry: string;
  onRetry: () => void;
}) {
  return (
    <Alert variant="destructive" role="alert">
      <AlertDescription>
        <div className="flex flex-col items-start gap-2">
          <p>{message}</p>
          <Button prominence="secondary" size="sm" onClick={onRetry}>
            {retry}
          </Button>
        </div>
      </AlertDescription>
    </Alert>
  );
}

/** A section of the catalog on its way: the rows it will hold, named for a screen reader. */
export function LoadingRows({ label }: { label: string }) {
  return (
    <div role="status" aria-label={label} className="flex flex-col gap-3">
      <Skeleton className="h-5 w-48" />
      <Skeleton className="h-9 w-full" />
    </div>
  );
}

/** The Tenant Chat default; a per-Persona override belongs with the Persona, not the catalog. */
export function TenantDefault(catalog: Catalog) {
  const ui = useAppTranslation();
  const tenant = useQuery({ ...getChatModelDefaultOptions(), retry: false });
  if (tenant.isPending) return <LoadingRows label={ui("Loading organization default…")} />;
  if (tenant.isError)
    return (
      <LoadFailure
        message={ui("Organization default could not be loaded.")}
        retry={ui("Retry organization default")}
        onRetry={() => void tenant.refetch()}
      />
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
      row={{
        title: ui("Chat"),
        description: ui("Used for new conversations and assistants without their own model."),
        ariaLabel: ui("Organization model default"),
        unsetMessage: ui("No default chosen; Chat cannot answer until one is."),
        savedMessage: ui("Default saved. Existing transcript is unchanged."),
        save: async (revision, modelConfigurationId, _effort, signal) =>
          (
            await setChatModelDefault({
              query: { revision, modelConfigurationId: modelConfigurationId ?? "" },
              signal,
            })
          ).data,
      }}
    />
  );
}

/**
 * Tenant models for tasks beside the conversation (Onyx llm_model_flow). Each row names its model; a row whose model
 * was deleted or became unavailable names the Chat default it falls back to.
 */
export function TaskModels(catalog: Catalog) {
  const ui = useAppTranslation();
  const flows = useQuery({ ...listChatModelFlowsOptions(), retry: false });
  const tenant = useQuery({ ...getChatModelDefaultOptions(), retry: false });
  const fallbackModel = catalog.models.find(
    (model) => model.id === tenant.data?.modelConfigurationId,
  );
  const fallbackProvider =
    fallbackModel && catalog.providers.find((provider) => provider.id === fallbackModel.providerId);
  const fallback =
    fallbackModel && fallbackProvider ? modelLabel(fallbackModel, fallbackProvider) : undefined;
  if (flows.isPending) return <LoadingRows label={ui("Loading task models…")} />;
  if (flows.isError)
    return (
      <LoadFailure
        message={ui("Task models could not be loaded.")}
        retry={ui("Retry task models")}
        onRetry={() => void flows.refetch()}
      />
    );
  // The Chat labels for the same levels.
  const efforts: ModelSelectorEffortOption[] = [
    { id: "OFF", name: ui("Off") },
    { id: "LOW", name: ui("Low") },
    { id: "MEDIUM", name: ui("Medium") },
    { id: "HIGH", name: ui("High") },
  ];
  const copy: Record<ModelFlow["flow"], Pick<Row, "title" | "description" | "ariaLabel">> = {
    CHAT_NAMING: {
      title: ui("Conversation naming"),
      description: ui("Names new conversations. A small, fast model keeps the chat model free."),
      ariaLabel: ui("Conversation naming model"),
    },
    CHAT_GUARDRAIL: {
      title: ui("Question check"),
      description: ui(
        "Sorts a question before it is answered: small talk, a question or a blocked topic. Needs a model that returns a fixed format.",
      ),
      ariaLabel: ui("Question check model"),
    },
    MEETING_MINUTES: {
      title: ui("Meeting minutes"),
      description: ui("Writes the summary, decisions and action items of a recorded meeting."),
      ariaLabel: ui("Meeting minutes model"),
    },
    MEETING_CORRECTION: {
      title: ui("Transcript corrections"),
      description: ui("Proposes what was said where the speech provider was unsure."),
      ariaLabel: ui("Transcript correction model"),
    },
  };
  return flows.data.map((flow) => (
    <SelectionEditor
      key={flow.flow}
      {...catalog}
      selection={flow}
      reload={async () => {
        const result = await flows.refetch({ throwOnError: true });
        const current = result.data?.find((entry) => entry.flow === flow.flow);
        if (!current) throw new Error("Selection unavailable");
        return current;
      }}
      row={{
        ...copy[flow.flow],
        unsetMessage: fallback
          ? ui("No model chosen; {{model}} is used.", { model: fallback })
          : ui("No model chosen; the task does not run until one is."),
        unavailableMessage: fallback
          ? ui("Unavailable; {{model}} is used instead.", { model: fallback })
          : ui("Unavailable; the Chat model is used instead."),
        efforts,
        savedMessage: ui("Task model saved."),
        save: async (revision, modelConfigurationId, reasoningEffort, signal) =>
          (
            await setChatModelFlow({
              path: { flow: flow.flow },
              query: {
                revision,
                ...(modelConfigurationId ? { modelConfigurationId } : {}),
                ...(reasoningEffort ? { reasoningEffort } : {}),
              },
              signal,
            })
          ).data,
      }}
    />
  ));
}
