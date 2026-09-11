import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/select";
import {
  getChatModelDefaultOptions,
  getPersonaModelOptions,
  listChatModelPersonasOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { setChatModelDefault, setPersonaModel } from "@/lib/hey-api/sdk.gen";
import type {
  GetChatModelDefaultResponse,
  GetPersonaModelResponse,
  ListChatModelPersonasResponse,
} from "@/lib/hey-api/types.gen";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  modelLabel,
  personaCandidate,
  refreshModelCatalog,
  tenantCandidate,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
} from "./model-catalog";
import { useModelAction } from "./use-model-action";

type Catalog = {
  providers: ManagedProvider[];
  models: ManagedModel[];
  adapters: InstalledAdapter[];
};
type Selection = GetChatModelDefaultResponse | GetPersonaModelResponse;

function SelectionEditor({
  selection,
  reload,
  personaId,
  providers,
  models,
  adapters,
}: Catalog & {
  selection: Selection;
  reload: () => Promise<Selection>;
  personaId?: string;
}) {
  const client = useQueryClient();
  const action = useModelAction();
  const [baseline, setBaseline] = useState(selection);
  const [chosen, setChosen] = useState(selection.modelConfigurationId ?? "");
  const [saved, setSaved] = useState(false);
  const candidates = models.filter((model) => {
    const provider = providers.find((entry) => entry.id === model.providerId);
    return (
      provider &&
      (personaId
        ? personaCandidate(model, provider, adapters, personaId)
        : tenantCandidate(model, provider, adapters))
    );
  });
  const savedModel = models.find((model) => model.id === baseline.modelConfigurationId);
  const savedProvider =
    savedModel && providers.find((provider) => provider.id === savedModel.providerId);
  const savedLabel =
    savedModel && savedProvider
      ? modelLabel(savedModel, savedProvider)
      : baseline.modelConfigurationId;
  const savedHidden =
    baseline.modelConfigurationId &&
    !candidates.some((model) => model.id === baseline.modelConfigurationId);
  const candidateChosen =
    candidates.some((model) => model.id === chosen) || (Boolean(personaId) && chosen === "");
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
        const result = personaId
          ? await setPersonaModel({
              path: { personaId },
              query: {
                revision: baseline.revision,
                ...(chosen ? { modelConfigurationId: chosen } : {}),
              },
              headers: sameOriginMutationHeaders,
              signal,
              throwOnError: true,
            })
          : await setChatModelDefault({
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
      <p className="break-words font-secondary-body text-content-muted">
        Saved:{" "}
        {baseline.modelConfigurationId
          ? `${savedLabel}${savedHidden ? " (hidden or currently unavailable; retained)" : ""}`
          : "Inherit Tenant default"}
        . Selection revision {baseline.revision}.
      </p>
      <label className="block space-y-1">
        {personaId ? "Persona model default" : "Tenant model default"}
        <Select
          value={chosen}
          disabled={action.pending}
          onChange={(event) => {
            setChosen(event.target.value);
            setSaved(false);
          }}
        >
          {personaId ? (
            <option value="">Inherit Tenant default</option>
          ) : (
            <option value="" disabled>
              Choose an eligible model
            </option>
          )}
          {savedHidden && (
            <option value={baseline.modelConfigurationId ?? ""} disabled>
              {savedLabel} (saved; hidden or unavailable)
            </option>
          )}
          {chosen &&
            chosen !== baseline.modelConfigurationId &&
            !candidates.some((model) => model.id === chosen) && (
              <option value={chosen} disabled>
                {chosen} (draft no longer eligible)
              </option>
            )}
          {candidates.map((model) => {
            const provider = providers.find((entry) => entry.id === model.providerId);
            return provider ? (
              <option key={model.id} value={model.id}>
                {modelLabel(model, provider)}
              </option>
            ) : null;
          })}
        </Select>
      </label>
      {!candidates.length && (
        <p role="status">
          No eligible models are available{personaId ? "; Inherit remains available" : ""}.
        </p>
      )}
      {conflicted && (
        <div role="alert" className="space-y-2">
          <p>
            The saved selection changed or conflicted. Refresh its own revision and review before
            retrying; model/provider revisions are not selection revisions.
          </p>
          <Button prominence="secondary" disabled={action.pending} onClick={() => void reconcile()}>
            Reconcile saved selection
          </Button>
        </div>
      )}
      {action.error && <p role="alert">{action.error}</p>}
      {saved && <p role="status">Default saved. Existing transcript is unchanged.</p>}
      <Button
        pending={action.pending}
        disabled={
          conflicted || !candidateChosen || chosen === (baseline.modelConfigurationId ?? "")
        }
        onClick={() => void save()}
      >
        Save {personaId ? "Persona" : "Tenant"} default
      </Button>
    </div>
  );
}

function PersonaSelection({ personaId, ...catalog }: Catalog & { personaId: string }) {
  const selection = useQuery({ ...getPersonaModelOptions({ path: { personaId } }), retry: false });
  if (selection.isPending) return <p role="status">Loading Persona selection…</p>;
  if (selection.isError)
    return (
      <div role="alert">
        <p>Persona selection could not be loaded.</p>
        <Button prominence="secondary" onClick={() => void selection.refetch()}>
          Retry Persona selection
        </Button>
      </div>
    );
  return (
    <SelectionEditor
      {...catalog}
      personaId={personaId}
      selection={selection.data}
      reload={async () => {
        const result = await selection.refetch({ throwOnError: true });
        if (!result.data) throw new Error("Selection unavailable");
        return result.data;
      }}
    />
  );
}

export function ModelDefaults(catalog: Catalog) {
  const tenant = useQuery({ ...getChatModelDefaultOptions(), retry: false });
  const [cursors, setCursors] = useState<string[]>([]);
  const cursor = cursors.at(-1);
  const personas = useQuery({
    ...listChatModelPersonasOptions({ query: { limit: 25, ...(cursor ? { cursor } : {}) } }),
    retry: false,
  });
  const [persona, setPersona] = useState<ListChatModelPersonasResponse["items"][number] | null>(
    null,
  );
  return (
    <section aria-labelledby="model-defaults-title" className="space-y-6">
      <h2 id="model-defaults-title" className="font-heading-h3">
        Model defaults
      </h2>
      <section
        aria-labelledby="tenant-default-title"
        className="space-y-4 rounded-xl border border-border-subtle p-4"
      >
        <h3 id="tenant-default-title" className="font-main-ui-action">
          Tenant Chat default
        </h3>
        <p className="font-secondary-body text-content-muted">
          Only visible models from installed, enabled, credential-ready, public providers without
          Persona restrictions are eligible. Public providers may retain Group associations. New
          manager-only providers are excluded; this screen cannot promote Access.
        </p>
        {tenant.isPending ? (
          <p role="status">Loading Tenant default…</p>
        ) : tenant.isError ? (
          <div role="alert">
            <p>Tenant default could not be loaded.</p>
            <Button prominence="secondary" onClick={() => void tenant.refetch()}>
              Retry Tenant default
            </Button>
          </div>
        ) : (
          <SelectionEditor
            {...catalog}
            selection={tenant.data}
            reload={async () => {
              const result = await tenant.refetch({ throwOnError: true });
              if (!result.data) throw new Error("Selection unavailable");
              return result.data;
            }}
          />
        )}
      </section>
      <section
        aria-labelledby="persona-default-title"
        className="space-y-4 rounded-xl border border-border-subtle p-4"
      >
        <h3 id="persona-default-title" className="font-main-ui-action">
          Persona default
        </h3>
        <p className="font-secondary-body text-content-muted">
          Select a Persona by name and UUID. Assignment respects provider Persona restrictions, even
          for managers. It grants no access: other users can fall back to their authorized Tenant
          default. Inherit removes the Persona selection.
        </p>
        {personas.isPending ? (
          <p role="status">Loading Personas…</p>
        ) : personas.isError ? (
          <div role="alert">
            <p>
              Personas could not be loaded. A stale cursor may require returning to the first page.
            </p>
            <Button
              prominence="secondary"
              onClick={() => {
                setCursors([]);
                void personas.refetch();
              }}
            >
              Reload Personas
            </Button>
          </div>
        ) : (
          <>
            <label className="block space-y-1">
              Persona
              <Select
                value={persona?.id ?? ""}
                onChange={(event) =>
                  setPersona(
                    personas.data.items.find((entry) => entry.id === event.target.value) ?? null,
                  )
                }
              >
                <option value="">Choose a Persona</option>
                {persona && !personas.data.items.some((entry) => entry.id === persona.id) && (
                  <option value={persona.id}>
                    {persona.name} · {persona.id} (selected)
                  </option>
                )}
                {personas.data.items.map((entry) => (
                  <option key={entry.id} value={entry.id}>
                    {entry.name} · {entry.id}
                  </option>
                ))}
              </Select>
            </label>
            {!personas.data.items.length && <p role="status">No Personas on this page.</p>}
            <div className="flex flex-wrap items-center gap-2">
              <Button
                prominence="secondary"
                size="sm"
                disabled={!cursors.length || personas.isFetching}
                onClick={() => setCursors((current) => current.slice(0, -1))}
              >
                Previous Personas
              </Button>
              <span className="font-secondary-body text-content-muted">
                Page {cursors.length + 1} · up to 25 Personas
              </span>
              <Button
                prominence="secondary"
                size="sm"
                disabled={personas.data.nextCursor === null || personas.isFetching}
                onClick={() => {
                  const next = personas.data.nextCursor;
                  if (next) setCursors((current) => [...current, next]);
                }}
              >
                Next Personas
              </Button>
            </div>
          </>
        )}
        {persona && (
          <div className="space-y-3">
            <p className="break-all font-main-ui-action">
              {persona.name} · {persona.id}
            </p>
            <PersonaSelection key={persona.id} {...catalog} personaId={persona.id} />
          </div>
        )}
      </section>
    </section>
  );
}
