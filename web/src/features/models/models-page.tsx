import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowRightLeft,
  Boxes,
  ChevronDown,
  Plug,
  Plus,
  Server,
  Settings2,
  Trash2,
} from "lucide-react";
import { useLayoutEffect, useState, type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { sameOriginMutationHeaders } from "@/lib/api";
import { cn } from "@/lib/utils";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import {
  getChatModelDefaultOptions,
  listChatProviderAdaptersOptions,
  listChatProvidersOptions,
  listConfiguredChatModelsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { deleteChatModel, deleteChatProvider } from "@/lib/hey-api/sdk.gen";
import { ModelDefaults, TenantDefault } from "./model-defaults";
import { ModelEditor } from "./model-editor";
import { ProviderEditor } from "./provider-editor";
import {
  refreshModelCatalog,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
} from "./model-catalog";
import { useModelAction } from "./use-model-action";

type Editor =
  | {
      kind: "provider";
      initial?: ManagedProvider;
      adapterType?: string;
      baseUrl?: string;
      name?: string;
    }
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

function adapterLabel(adapter: InstalledAdapter) {
  return adapter.type === "openai" ? "OpenAI-compatible" : adapter.type;
}
function providerStatus(provider: ManagedProvider) {
  if (!provider.enabled) return { label: "Disabled", variant: "outline" as const };
  if (!provider.credentialConfigured)
    return { label: "No credential", variant: "outline" as const };
  return { label: "Enabled", variant: "secondary" as const };
}

function ConnectionCard({
  provider,
  models,
  adapters,
  isDefault,
  unavailable,
  onEdit,
  onAddModel,
  onEditModel,
  onDelete,
  onDeleteModel,
}: {
  provider: ManagedProvider;
  models: ManagedModel[];
  adapters: InstalledAdapter[];
  isDefault: boolean;
  unavailable: boolean;
  onEdit: () => void;
  onAddModel: () => void;
  onEditModel: (model: ManagedModel) => void;
  onDelete: () => void;
  onDeleteModel: (model: ManagedModel) => void;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  const status = providerStatus(provider);
  const adapter = adapters.find((entry) => entry.type === provider.adapterType);
  return (
    <Card size="sm" className="overflow-visible">
      <div className="flex w-full items-center gap-3 rounded-2xl px-4 py-3">
        <button
          type="button"
          aria-expanded={open}
          aria-label={ui(appText("Provider {{name}}", { name: provider.name }))}
          onClick={() => setOpen((current) => !current)}
          className="flex min-w-0 flex-1 items-center gap-3 rounded-lg text-left transition-colors hover:bg-surface-base"
        >
          <span className="grid size-9 shrink-0 place-items-center rounded-lg border border-border-subtle bg-surface-base text-content-secondary">
            <Boxes className="size-4" aria-hidden="true" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="flex flex-wrap items-center gap-2">
              <span className="break-words font-main-ui-action">{provider.name}</span>
              {isDefault && <Badge variant="default">{ui("Default")}</Badge>}
              <Badge variant={status.variant}>{ui(status.label)}</Badge>
              {!provider.isPublic && <Badge variant="outline">{ui("Restricted")}</Badge>}
            </span>
            <span className="block break-all font-secondary-body text-content-muted">
              {adapterLabel(adapter ?? ({ type: provider.adapterType } as InstalledAdapter))} ·{" "}
              {provider.baseUrl}
            </span>
          </span>
        </button>
        <span className="flex shrink-0 items-center gap-1">
          <IconButton
            prominence="tertiary"
            size="sm"
            aria-label={ui(appText("Edit provider {{name}}", { name: provider.name }))}
            disabled={unavailable}
            onClick={onEdit}
          >
            <Settings2 />
          </IconButton>
          <IconButton
            prominence="tertiary"
            tone="danger"
            size="sm"
            aria-label={ui(appText("Delete provider {{name}}", { name: provider.name }))}
            disabled={unavailable}
            onClick={onDelete}
          >
            <Trash2 />
          </IconButton>
          <IconButton
            prominence="tertiary"
            size="sm"
            aria-label={
              open
                ? ui(appText("Collapse {{name}}", { name: provider.name }))
                : ui(appText("Expand {{name}}", { name: provider.name }))
            }
            aria-expanded={open}
            onClick={() => setOpen((current) => !current)}
          >
            <ChevronDown className={cn("transition-transform", open && "rotate-180")} />
          </IconButton>
        </span>
      </div>
      {open && (
        <CardContent className="space-y-3 border-t border-border-subtle pt-4">
          <p className="break-all font-secondary-body text-content-muted">
            {provider.id} · {ui("revision")} {provider.revision} ·{" "}
            {provider.credentialConfigured
              ? ui("Credential configured (not verified)")
              : ui("No credential configured")}
          </p>
          <ul className="space-y-3">
            {models.map((model) => (
              <li
                key={model.id}
                className="flex flex-col justify-between gap-3 rounded-xl border border-border-subtle p-3 sm:flex-row sm:items-center"
              >
                <div className="min-w-0 space-y-1">
                  <h3 className="break-words font-main-ui-action">{model.displayName}</h3>
                  <p className="break-all font-secondary-body text-content-muted">
                    {model.modelName} · {model.id} · {ui("revision")} {model.revision}
                  </p>
                  <p className="break-words font-secondary-body text-content-muted">
                    {model.visible ? ui("Visible") : ui("Hidden")} ·{" "}
                    {model.settings.tokenizerProfile} ·{" "}
                    {ui(
                      appText("{{context}} context / {{output}} output tokens", {
                        context: model.settings.contextWindow,
                        output: model.settings.maxOutputTokens,
                      }),
                    )}
                  </p>
                  <p className="font-secondary-body text-content-muted">
                    {ui("Pricing")}:{" "}
                    {model.settings.pricing === null
                      ? ui("Unknown")
                      : ui(
                          appText("${{input}} input / ${{output}} output per million tokens", {
                            input: model.settings.pricing.inputPerMillion,
                            output: model.settings.pricing.outputPerMillion,
                          }),
                        )}
                  </p>
                </div>
                <div className="flex shrink-0 flex-wrap gap-2">
                  <Button
                    prominence="secondary"
                    size="sm"
                    disabled={unavailable}
                    onClick={() => onEditModel(model)}
                  >
                    {ui("Edit model / Validate")}
                  </Button>
                  <IconButton
                    tone="danger"
                    prominence="tertiary"
                    size="sm"
                    aria-label={ui(appText("Delete model {{name}}", { name: model.displayName }))}
                    disabled={unavailable}
                    onClick={() => onDeleteModel(model)}
                  >
                    <Trash2 />
                  </IconButton>
                </div>
              </li>
            ))}
          </ul>
          {!models.length && (
            <p className="font-secondary-body text-content-muted">
              {ui("No configured models for this provider.")}
            </p>
          )}
          <Button
            prominence="secondary"
            size="sm"
            disabled={unavailable || !adapter}
            onClick={onAddModel}
          >
            <Plus aria-hidden="true" /> {ui("Add model")}
          </Button>
        </CardContent>
      )}
    </Card>
  );
}

type ProviderPreset = {
  name: string;
  subtitle: string;
  baseUrl: string;
  logo: ReactNode;
};

function NewConnectionCard({
  preset,
  disabled,
  onConnect,
}: {
  preset: ProviderPreset;
  disabled: boolean;
  onConnect: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <button
      type="button"
      disabled={disabled}
      aria-label={ui(appText("Connect {{name}}", { name: preset.name }))}
      onClick={onConnect}
      className="flex items-center gap-3 rounded-2xl border border-border-subtle px-4 py-3 text-left transition-colors hover:bg-surface-base disabled:cursor-not-allowed disabled:opacity-60"
    >
      <span className="grid size-9 shrink-0 place-items-center rounded-lg border border-border-subtle bg-surface-base text-content-primary [&_svg]:size-5">
        {preset.logo}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block font-main-ui-action">{preset.name}</span>
        <span className="block font-secondary-body text-content-muted">{preset.subtitle}</span>
      </span>
      <span className="flex shrink-0 items-center gap-1 font-secondary-body text-content-muted">
        {ui("Connect")} <ArrowRightLeft className="size-4" aria-hidden="true" />
      </span>
    </button>
  );
}

function ModelsAdministration() {
  const ui = useAppTranslation();
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
  const tenantDefault = useQuery({ ...getChatModelDefaultOptions(), retry: false });
  const defaultModelId = tenantDefault.data?.modelConfigurationId ?? null;

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

  const hasProviders = Boolean(providers.data?.length);
  const sortedProviders = [...(providers.data ?? [])].sort((a, b) => {
    const aDefault = models.some((m) => m.providerId === a.id && m.id === defaultModelId);
    const bDefault = models.some((m) => m.providerId === b.id && m.id === defaultModelId);
    return aDefault === bDefault ? 0 : aDefault ? -1 : 1;
  });

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("Models")}
        icon={<Boxes />}
        actions={
          <Button
            prominence="secondary"
            pending={action.pending && !deletion}
            onClick={() => void reload()}
          >
            {ui("Refresh catalog")}
          </Button>
        }
      />

      {/* Default model — Onyx top card */}
      {hasProviders && !catalogError && (
        <Card>
          <CardContent>
            {catalogPending ? (
              <p role="status">{ui("Loading model catalog…")}</p>
            ) : (
              <TenantDefault
                providers={providers.data!}
                models={models}
                adapters={adapters.data!}
              />
            )}
          </CardContent>
        </Card>
      )}

      {catalogPending && <p role="status">{ui("Loading model catalog…")}</p>}
      {catalogError && (
        <p role="alert">
          {ui(
            "The complete catalog could not be loaded. Displayed records may be stale. Refresh before changing configurations or defaults.",
          )}
        </p>
      )}
      {action.error && !deletion && <p role="alert">{ui(action.error)}</p>}

      {/* Available connections — Onyx existing-provider cards */}
      {hasProviders && (
        <section aria-labelledby="available-connections" className="space-y-3">
          <h2 id="available-connections" className="font-heading-h3">
            {ui("Available connections")}
          </h2>
          <div className="flex flex-col gap-2">
            {sortedProviders.map((provider) => (
              <ConnectionCard
                key={provider.id}
                provider={provider}
                models={models.filter((model) => model.providerId === provider.id)}
                adapters={adapters.data ?? []}
                isDefault={models.some(
                  (model) => model.providerId === provider.id && model.id === defaultModelId,
                )}
                unavailable={unavailable}
                onEdit={() => setEditor({ kind: "provider", initial: provider })}
                onAddModel={() => setEditor({ kind: "model", providerId: provider.id })}
                onEditModel={(model) =>
                  setEditor({ kind: "model", providerId: provider.id, initial: model })
                }
                onDelete={() => {
                  action.cancel();
                  setDeletion({ kind: "provider", provider });
                }}
                onDeleteModel={(model) => {
                  action.cancel();
                  setDeletion({ kind: "model", model });
                }}
              />
            ))}
          </div>
        </section>
      )}

      {/* Add connection — Onyx provider grid */}
      {adapters.data?.some((adapter) => adapter.type === "openai") && (
        <section aria-labelledby="add-connection" className="space-y-6">
          <div>
            <h2 id="add-connection" className="font-heading-h3">
              {ui("Add Provider")}
            </h2>
            <p className="font-secondary-body text-content-muted">
              {ui("MemoryOS supports both popular providers and self-hosted models.")}
            </p>
          </div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <NewConnectionCard
              preset={{
                name: "GPT",
                subtitle: "OpenAI",
                baseUrl: "https://api.openai.com/v1",
                logo: <ProviderLogo mark="OPENAI" />,
              }}
              disabled={unavailable || (providers.data?.length ?? 0) >= 64}
              onConnect={() =>
                setEditor({
                  kind: "provider",
                  adapterType: "openai",
                  baseUrl: "https://api.openai.com/v1",
                  name: "GPT",
                })
              }
            />
            <NewConnectionCard
              preset={{
                name: "Claude",
                subtitle: "Anthropic",
                baseUrl: "https://api.anthropic.com/v1",
                logo: <ProviderLogo mark="ANTHROPIC" />,
              }}
              disabled={unavailable || (providers.data?.length ?? 0) >= 64}
              onConnect={() =>
                setEditor({
                  kind: "provider",
                  adapterType: "openai",
                  baseUrl: "https://api.anthropic.com/v1",
                  name: "Claude",
                })
              }
            />
          </div>
          <div>
            <h3 className="font-main-ui-action text-content-secondary">
              {ui("Self-hosted & Custom")}
            </h3>
            <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
              <NewConnectionCard
                preset={{
                  name: "Ollama",
                  subtitle: "Ollama",
                  baseUrl: "http://localhost:11434/v1",
                  logo: <Server />,
                }}
                disabled={unavailable || (providers.data?.length ?? 0) >= 64}
                onConnect={() =>
                  setEditor({
                    kind: "provider",
                    adapterType: "openai",
                    baseUrl: "http://localhost:11434/v1",
                    name: "Ollama",
                  })
                }
              />
              <NewConnectionCard
                preset={{
                  name: "OpenAI-Compatible",
                  subtitle: "OpenAI-Compatible",
                  baseUrl: "",
                  logo: <Plug />,
                }}
                disabled={unavailable || (providers.data?.length ?? 0) >= 64}
                onConnect={() =>
                  setEditor({
                    kind: "provider",
                    adapterType: "openai",
                    name: "OpenAI-Compatible",
                  })
                }
              />
            </div>
          </div>
        </section>
      )}
      {!catalogPending && !adapters.data?.length && !catalogError && (
        <p role="status">{ui("No provider adapters are installed.")}</p>
      )}

      {!catalogPending && !catalogError && providers.data && adapters.data && (
        <ModelDefaults providers={providers.data} adapters={adapters.data} models={models} />
      )}
      {editor?.kind === "provider" && adapters.data && (
        <ProviderEditor
          initial={editor.initial}
          providers={providers.data ?? []}
          adapters={adapters.data}
          preferredAdapterType={editor.adapterType}
          preferredBaseUrl={editor.baseUrl}
          preferredName={editor.name}
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
          title={
            deletion.kind === "provider"
              ? ui(appText("Delete provider {{name}}?", { name: deletion.provider.name }))
              : ui(appText("Delete model {{name}}?", { name: deletion.model.displayName }))
          }
          description={
            <span>
              {deletion.kind === "provider"
                ? ui(
                    appText("Provider {{id}} and all its configured models will be removed.", {
                      id: deletion.provider.id,
                    }),
                  )
                : ui(appText("Model {{id}} will be removed.", { id: deletion.model.id }))}{" "}
              {ui(
                "Affected Persona defaults are cleared and their selection revisions advance. Transcript history is retained. A Tenant default must be replaced first. On a conflict, cancel, refresh the catalog and review before trying again.",
              )}
            </span>
          }
          confirmLabel={ui("Delete configuration")}
          pendingLabel={ui("Deleting configuration")}
          onConfirm={remove}
          errorMessage={(error) =>
            error instanceof Error ? error.message : ui("Deletion failed. Refresh before retrying.")
          }
        />
      )}
    </SettingsLayout>
  );
}
