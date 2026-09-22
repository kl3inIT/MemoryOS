import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  getChatModelDefaultOptions,
  listChatModelFlowsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { setChatModelDefault, setChatModelFlow } from "@/lib/hey-api/sdk.gen";
import type { ModelFlow } from "@/lib/hey-api/types.gen";
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
type Selection = { modelConfigurationId: string | null; revision: number; available?: boolean };
type Row = {
  title: string;
  description: string;
  ariaLabel: string;
  saveLabel: string;
  savedMessage: string;
  /** Offered only by clearable rows: the empty selection. */
  emptyLabel?: string;
  unavailableMessage?: string;
  save: (revision: number, modelId: string | null, signal: AbortSignal) => Promise<Selection>;
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
  const action = useModelAction();
  const [baseline, setBaseline] = useState(selection);
  const [chosen, setChosen] = useState(selection.modelConfigurationId ?? "");
  const [saved, setSaved] = useState(false);
  // Deleting a task model clears it, and catalog changes alter availability, without a revision change.
  if (
    selection.revision === baseline.revision &&
    (selection.modelConfigurationId !== baseline.modelConfigurationId ||
      selection.available !== baseline.available)
  ) {
    setBaseline(selection);
    setChosen(selection.modelConfigurationId ?? "");
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
  const candidateChosen =
    candidates.some((model) => model.id === chosen) || (Boolean(row.emptyLabel) && !chosen);
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
        const result = await row.save(baseline.revision, chosen || null, signal);
        signal.throwIfAborted();
        setBaseline(result);
        setChosen(result.modelConfigurationId ?? "");
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
          <h3 className="font-main-ui-action">{row.title}</h3>
          <p className="font-secondary-body text-content-muted">{row.description}</p>
        </div>
        <ModelPicker
          ariaLabel={row.ariaLabel}
          emptyLabel={row.emptyLabel}
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
      {baseline.available === false && row.unavailableMessage && (
        <p role="status" className="font-secondary-body text-status-warning-content">
          {row.unavailableMessage}
        </p>
      )}
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
      {saved && <p role="status">{row.savedMessage}</p>}
      {chosen !== (baseline.modelConfigurationId ?? "") && (
        <Button
          pending={action.pending}
          disabled={conflicted || !candidateChosen}
          onClick={() => void save()}
        >
          {row.saveLabel}
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
      row={{
        title: ui("Chat"),
        description: ui("Used for new conversations and assistants without their own model."),
        ariaLabel: ui("Tenant model default"),
        saveLabel: ui("Save Tenant default"),
        savedMessage: ui("Default saved. Existing transcript is unchanged."),
        save: async (revision, modelConfigurationId, signal) =>
          (
            await setChatModelDefault({
              query: { revision, modelConfigurationId: modelConfigurationId ?? "" },
              headers: sameOriginMutationHeaders,
              signal,
              throwOnError: true,
            })
          ).data,
      }}
    />
  );
}

/** Tenant models for tasks beside the conversation (Onyx llm_model_flow); unset uses the conversation model. */
export function TaskModels(catalog: Catalog) {
  const ui = useAppTranslation();
  const flows = useQuery({ ...listChatModelFlowsOptions(), retry: false });
  if (flows.isPending) return <p role="status">{ui("Loading task models…")}</p>;
  if (flows.isError)
    return (
      <div role="alert" className="space-y-2">
        <p>{ui("Task models could not be loaded.")}</p>
        <Button prominence="secondary" onClick={() => void flows.refetch()}>
          {ui("Retry task models")}
        </Button>
      </div>
    );
  const copy: Record<ModelFlow["flow"], Pick<Row, "title" | "description" | "ariaLabel">> = {
    CHAT_NAMING: {
      title: ui("Conversation naming"),
      description: ui("Names new conversations. A small, fast model keeps the chat model free."),
      ariaLabel: ui("Conversation naming model"),
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
        emptyLabel: ui("Use the conversation model"),
        unavailableMessage: ui("Unavailable; the conversation model is used instead."),
        saveLabel: ui("Save task model"),
        savedMessage: ui("Task model saved."),
        save: async (revision, modelConfigurationId, signal) =>
          (
            await setChatModelFlow({
              path: { flow: flow.flow },
              query: { revision, ...(modelConfigurationId ? { modelConfigurationId } : {}) },
              headers: sameOriginMutationHeaders,
              signal,
              throwOnError: true,
            })
          ).data,
      }}
    />
  ));
}
