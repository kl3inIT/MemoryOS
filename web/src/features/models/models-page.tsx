import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { Boxes } from "lucide-react";
import { useLayoutEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  listChatProviderAdaptersOptions,
  listChatProvidersOptions,
  listConfiguredChatModelsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { deleteChatModel, deleteChatProvider } from "@/lib/hey-api/sdk.gen";
import { ModelDefaults } from "./model-defaults";
import { ModelEditor } from "./model-editor";
import { ProviderEditor } from "./provider-editor";
import { refreshModelCatalog, type ManagedModel, type ManagedProvider } from "./model-catalog";
import { useModelAction } from "./use-model-action";

type Editor =
  | { kind: "provider"; initial?: ManagedProvider }
  | { kind: "model"; providerId: string; initial?: ManagedModel };
type Deletion =
  | { kind: "provider"; provider: ManagedProvider }
  | { kind: "model"; model: ManagedModel };

/** Denial never mounts catalog queries; authority changes also retire every draft and direct call. */
export function ModelsPage() {
  const session = useApplicationSession();
  const [active, setActive] = useState(true);
  useLayoutEffect(() => {
    const hide = () => setActive(false);
    const show = () => setActive(true);
    window.addEventListener("pagehide", hide);
    window.addEventListener("pageshow", show);
    return () => {
      window.removeEventListener("pagehide", hide);
      window.removeEventListener("pageshow", show);
    };
  }, []);
  if (!session.capabilities.includes("MODELS_MANAGE")) return <AccessDeniedScreen />;
  if (!active) return null;
  return (
    <ModelsAdministration
      key={JSON.stringify([
        session.actorId,
        session.authorizationVersion,
        session.capabilities,
        session.scopedCapabilities,
      ])}
    />
  );
}

function ModelsAdministration() {
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
  const models = configured.flatMap((query) => query.data ?? []);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [deletion, setDeletion] = useState<Deletion | null>(null);
  const action = useModelAction();
  const catalogPending =
    providers.isPending || adapters.isPending || configured.some((query) => query.isPending);
  const catalogError =
    providers.isError || adapters.isError || configured.some((query) => query.isError);
  const unavailable = catalogPending || catalogError || action.pending;
  const modelProvider =
    editor?.kind === "model"
      ? providers.data?.find((provider) => provider.id === editor.providerId)
      : undefined;

  async function reload() {
    action.cancel();
    try {
      await action.run(async (signal) => {
        await Promise.all([refreshModelCatalog(client), adapters.refetch({ throwOnError: true })]);
        signal.throwIfAborted();
        action.reconciled();
      });
    } catch {
      /* Safe feedback below. */
    }
  }

  async function remove() {
    if (!deletion || unavailable) throw new Error("Refresh the catalog before deleting.");
    if (action.conflict)
      throw new Error(
        "Cancel this dialog, reload the catalog, and review the current configuration before deleting again.",
      );
    await action.run(async (signal) => {
      if (deletion.kind === "provider")
        await deleteChatProvider({
          path: { providerId: deletion.provider.id },
          query: { revision: deletion.provider.revision },
          headers: sameOriginMutationHeaders,
          signal,
          throwOnError: true,
        });
      else
        await deleteChatModel({
          path: { modelId: deletion.model.id },
          query: { revision: deletion.model.revision },
          headers: sameOriginMutationHeaders,
          signal,
          throwOnError: true,
        });
      signal.throwIfAborted();
      if (deletion.kind === "provider") {
        const providerId = deletion.provider.id;
        client.setQueriesData<ManagedProvider[]>(
          { queryKey: [{ _id: "listChatProviders" }] },
          (previous) => previous?.filter((provider) => provider.id !== providerId),
        );
        const removedModels = {
          queryKey: [{ _id: "listConfiguredChatModels", path: { providerId } }],
        };
        await client.cancelQueries(removedModels);
        client.removeQueries(removedModels);
      }
      await refreshModelCatalog(client);
      signal.throwIfAborted();
    });
  }

  return (
    <SettingsLayout wide>
      <PageHeader
        title="Models"
        icon={<Boxes />}
        description="Manage provider connections, configured models and Chat defaults. New connections are manager-only; Access editing is not available here."
        actions={
          <>
            <Button
              prominence="secondary"
              pending={action.pending && !deletion}
              onClick={() => void reload()}
            >
              Refresh catalog
            </Button>
            <Button
              disabled={
                unavailable || !adapters.data?.length || (providers.data?.length ?? 0) >= 64
              }
              onClick={() => setEditor({ kind: "provider" })}
            >
              Add provider
            </Button>
          </>
        }
      />
      <p className="font-secondary-body text-content-muted">
        {providers.data?.length ?? 0} / 64 providers · {models.length} / 256 Tenant models. Hidden
        models and disabled providers remain editable.
      </p>
      {catalogPending && <p role="status">Loading model catalog…</p>}
      {catalogError && (
        <p role="alert">
          The complete catalog could not be loaded. Displayed records may be stale. Refresh before
          changing configurations or defaults.
        </p>
      )}
      {action.error && !deletion && <p role="alert">{action.error}</p>}
      {!catalogPending && !providers.data?.length && !catalogError && (
        <p role="status">
          No providers configured. Add a provider connection to configure a model.
        </p>
      )}
      <div className="space-y-5">
        {providers.data?.map((provider) => (
          <section
            key={provider.id}
            aria-label={`Provider ${provider.name} ${provider.id}`}
            className="space-y-4 rounded-xl border border-border-subtle p-4 sm:p-5"
          >
            <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-start">
              <div className="min-w-0 space-y-1">
                <h2 className="break-words font-heading-h3">{provider.name}</h2>
                <p className="break-all font-secondary-body text-content-muted">
                  {provider.id} · {provider.adapterType} · revision {provider.revision}
                </p>
                <p className="break-all font-secondary-body text-content-muted">
                  {provider.baseUrl}
                </p>
                <p className="font-secondary-body text-content-muted">
                  {provider.enabled ? "Enabled" : "Disabled"} ·{" "}
                  {provider.credentialConfigured
                    ? "Credential configured (not verified)"
                    : "No credential configured"}{" "}
                  · {provider.isPublic ? "Public" : "Restricted"}
                </p>
              </div>
              <div className="flex shrink-0 flex-wrap gap-2">
                <Button
                  prominence="secondary"
                  size="sm"
                  disabled={unavailable}
                  onClick={() => setEditor({ kind: "provider", initial: provider })}
                >
                  Edit provider
                </Button>
                <Button
                  prominence="secondary"
                  size="sm"
                  disabled={
                    unavailable ||
                    models.length >= 256 ||
                    !adapters.data?.some((adapter) => adapter.type === provider.adapterType)
                  }
                  onClick={() => setEditor({ kind: "model", providerId: provider.id })}
                >
                  Add model
                </Button>
                <Button
                  tone="danger"
                  prominence="tertiary"
                  size="sm"
                  disabled={unavailable}
                  onClick={() => {
                    action.cancel();
                    setDeletion({ kind: "provider", provider });
                  }}
                >
                  Delete provider
                </Button>
              </div>
            </div>
            <ul className="space-y-3">
              {models
                .filter((model) => model.providerId === provider.id)
                .map((model) => (
                  <li
                    key={model.id}
                    className="flex flex-col justify-between gap-3 border-t border-border-subtle pt-3 sm:flex-row sm:items-center"
                  >
                    <div className="min-w-0 space-y-1">
                      <h3 className="break-words font-main-ui-action">{model.displayName}</h3>
                      <p className="break-all font-secondary-body text-content-muted">
                        {model.modelName} · {model.id} · revision {model.revision}
                      </p>
                      <p className="break-words font-secondary-body text-content-muted">
                        {model.visible ? "Visible" : "Hidden"} · {model.settings.tokenizerProfile} ·{" "}
                        {model.settings.contextWindow} context / {model.settings.maxOutputTokens}{" "}
                        output tokens
                      </p>
                      <p className="font-secondary-body text-content-muted">
                        Pricing:{" "}
                        {model.settings.pricing === null
                          ? "Unknown"
                          : `$${model.settings.pricing.inputPerMillion} input / $${model.settings.pricing.outputPerMillion} output per million tokens`}
                      </p>
                    </div>
                    <div className="flex shrink-0 flex-wrap gap-2">
                      <Button
                        prominence="secondary"
                        size="sm"
                        disabled={unavailable}
                        onClick={() =>
                          setEditor({ kind: "model", providerId: provider.id, initial: model })
                        }
                      >
                        Edit model / Validate
                      </Button>
                      <Button
                        tone="danger"
                        prominence="tertiary"
                        size="sm"
                        disabled={unavailable}
                        onClick={() => {
                          action.cancel();
                          setDeletion({ kind: "model", model });
                        }}
                      >
                        Delete model
                      </Button>
                    </div>
                  </li>
                ))}
            </ul>
            {!catalogPending && !models.some((model) => model.providerId === provider.id) && (
              <p className="font-secondary-body text-content-muted">
                No configured models for this provider.
              </p>
            )}
          </section>
        ))}
      </div>
      {!catalogPending && !catalogError && providers.data && adapters.data && (
        <ModelDefaults providers={providers.data} adapters={adapters.data} models={models} />
      )}
      {editor?.kind === "provider" && adapters.data && (
        <ProviderEditor
          initial={editor.initial}
          providers={providers.data ?? []}
          adapters={adapters.data}
          onClose={() => setEditor(null)}
        />
      )}
      {editor?.kind === "model" && modelProvider && (
        <ModelEditor
          initial={editor.initial}
          models={models}
          provider={modelProvider}
          adapter={adapters.data?.find((adapter) => adapter.type === modelProvider.adapterType)}
          onClose={() => setEditor(null)}
        />
      )}
      {deletion && (
        <ConfirmDialog
          open
          onOpenChange={(open) => {
            if (!open) {
              setDeletion(null);
              action.cancel();
            }
          }}
          title={`Delete ${deletion.kind === "provider" ? `provider ${deletion.provider.name}` : `model ${deletion.model.displayName}`}?`}
          description={
            <span>
              {deletion.kind === "provider"
                ? `Provider ${deletion.provider.id} and all its configured models will be removed.`
                : `Model ${deletion.model.id} will be removed.`}{" "}
              Affected Persona defaults are cleared and their selection revisions advance.
              Transcript history is retained. A Tenant default must be replaced first. On a
              conflict, cancel, refresh the catalog and review before trying again.
            </span>
          }
          confirmLabel="Delete configuration"
          pendingLabel="Deleting configuration"
          onConfirm={remove}
          errorMessage={(error) =>
            error instanceof Error ? error.message : "Deletion failed. Refresh before retrying."
          }
        />
      )}
    </SettingsLayout>
  );
}
