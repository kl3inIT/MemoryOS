import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Boxes,
  Brain,
  ChevronDown,
  Eye,
  ListPlus,
  Plug,
  PlugZap,
  Route,
  Plus,
  Server,
  Settings2,
  Trash2,
  Wrench,
} from "lucide-react";
import { useLayoutEffect, useState, type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Empty, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { IconButton } from "@/components/ui/icon-button";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { StatusBadge } from "@/components/ui/status-badge";
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { sameOriginMutationHeaders } from "@/lib/api";
import { cn } from "@/lib/utils";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ProviderCard, providerTileClassName } from "@/components/provider-logos/provider-card";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { hasProviderMark, type ProviderMark } from "@/components/provider-logos/provider-marks";
import {
  getChatModelDefaultOptions,
  listChatProviderAdaptersOptions,
  listChatProvidersOptions,
  listConfiguredChatModelsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { deleteChatModel, deleteChatProvider } from "@/lib/hey-api/sdk.gen";
import { TaskModels, TenantDefault } from "./model-defaults";
import { ModelEditor } from "./model-editor";
import { ModelDiscovery } from "./model-discovery";
import { ProviderEditor } from "./provider-editor";
import { DataBoundaryTag } from "./data-boundary";
import {
  type ReportedModel,
  compactTokens,
  millionTokenPrice,
  refreshModelCatalog,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
} from "./model-catalog";
import { ChatModelLogo } from "@/features/chat/chat-model-logo";
import { useProviderTest } from "./provider-test";
import { useModelAction } from "./use-model-action";

type Editor =
  | {
      kind: "provider";
      initial?: ManagedProvider;
      adapterType?: string;
      baseUrl?: string;
      name?: string;
    }
  | {
      kind: "model";
      providerId: string;
      initial?: ManagedModel;
      modelName?: string;
      reported?: ReportedModel;
    }
  | { kind: "discovery"; providerId: string };
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

/** Brand marks are display only; the adapter type still selects the protocol. */
function providerMark(provider: ManagedProvider): ProviderMark | null {
  // OpenAI-compatible vendors share the `openai` adapter, so the endpoint names the brand first.
  const baseUrl = provider.baseUrl.toLowerCase();
  if (/anthropic|claude/.test(baseUrl)) return "ANTHROPIC";
  if (baseUrl.includes("openrouter.ai")) return "OPENROUTER";
  if (baseUrl.includes("9router")) return "NINEROUTER";
  if (/ollama|:11434\b/.test(baseUrl)) return "OLLAMA";
  const adapter = provider.adapterType.toUpperCase();
  return hasProviderMark(adapter) ? adapter : null;
}
function providerStatus(provider: ManagedProvider) {
  if (!provider.enabled) return { label: "Disabled", tone: "neutral" as const };
  if (!provider.credentialConfigured) return { label: "No credential", tone: "warning" as const };
  return { label: "Enabled", tone: "success" as const };
}

/** Declared capabilities, so a row reads without opening the editor. */
function Capabilities({
  capabilities,
}: {
  capabilities: ManagedModel["settings"]["capabilities"];
}) {
  const ui = useAppTranslation();
  const declared = [
    { key: "toolCalling", label: "Tool calling", icon: Wrench, on: capabilities.toolCalling },
    { key: "vision", label: "Vision input", icon: Eye, on: capabilities.vision },
    { key: "reasoning", label: "Reasoning", icon: Brain, on: capabilities.reasoning },
  ].filter((entry) => entry.on);
  if (declared.length === 0) return null;
  return (
    <TooltipProvider>
      <span className="flex items-center gap-1">
        {declared.map(({ key, label, icon: Icon }) => (
          <Tooltip key={key}>
            <TooltipTrigger className="flex items-center">
              <Icon aria-label={ui(label)} className="size-3.5 text-content-muted" />
            </TooltipTrigger>
            <TooltipContent>{ui(label)}</TooltipContent>
          </Tooltip>
        ))}
      </span>
    </TooltipProvider>
  );
}

function ConnectionCard({
  provider,
  models,
  adapters,
  isDefault,
  defaultModelId,
  unavailable,
  onEdit,
  onAddModel,
  onDiscover,
  onEditModel,
  onDelete,
  onDeleteModel,
}: {
  provider: ManagedProvider;
  models: ManagedModel[];
  adapters: InstalledAdapter[];
  isDefault: boolean;
  defaultModelId: string | null;
  unavailable: boolean;
  onEdit: () => void;
  onAddModel: () => void;
  onDiscover: () => void;
  onEditModel: (model: ManagedModel) => void;
  onDelete: () => void;
  onDeleteModel: (model: ManagedModel) => void;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  const status = providerStatus(provider);
  const adapter = adapters.find((entry) => entry.type === provider.adapterType);
  const mark = providerMark(provider);
  const connection = useProviderTest();
  return (
    <Card size="sm" className="gap-0 overflow-visible py-0">
      <div className="flex w-full items-center gap-3 rounded-2xl px-4 py-3">
        <button
          type="button"
          aria-expanded={open}
          aria-label={ui(appText("Provider {{name}}", { name: provider.name }))}
          onClick={() => setOpen((current) => !current)}
          className="flex min-w-0 flex-1 items-center gap-3 rounded-lg text-left transition-colors hover:bg-surface-base"
        >
          <span className={cn(providerTileClassName, "text-content-secondary")}>
            {mark ? <ProviderLogo mark={mark} /> : <Server className="size-4" aria-hidden="true" />}
          </span>
          <span className="min-w-0 flex-1">
            <span className="flex flex-wrap items-center gap-2">
              <span className="break-words font-main-ui-action">{provider.name}</span>
              {isDefault && <Badge variant="secondary">{ui("Default")}</Badge>}
              {status.label !== "Enabled" && (
                <StatusBadge tone={status.tone}>{ui(status.label)}</StatusBadge>
              )}
              {!provider.isPublic && <Badge variant="outline">{ui("Restricted")}</Badge>}
              <DataBoundaryTag boundary={provider.dataBoundary} />
            </span>
            <span className="block break-all font-secondary-body text-content-muted">
              {provider.baseUrl}
            </span>
          </span>
        </button>
        <span className="flex shrink-0 items-center gap-1">
          <span className="mr-1 hidden font-secondary-body tabular-nums text-content-muted sm:inline">
            {ui(appText("{{count}} models", { count: models.length }))}
          </span>
          <IconButton
            prominence="tertiary"
            size="sm"
            aria-label={ui(appText("Test connection {{name}}", { name: provider.name }))}
            disabled={unavailable || connection.pending || !provider.credentialConfigured}
            onClick={() =>
              void connection.run({
                adapterType: provider.adapterType,
                baseUrl: provider.baseUrl,
                providerId: provider.id,
                credential: { action: "KEEP" },
              })
            }
          >
            <PlugZap />
          </IconButton>
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
      {connection.outcome && (
        <p
          role={connection.outcome.ok ? "status" : "alert"}
          className={cn(
            "mx-4 mb-3 font-secondary-body",
            connection.outcome.ok ? "text-status-success-content" : "text-status-danger-content",
          )}
        >
          {ui(connection.outcome.message)}
        </p>
      )}
      {open && (
        <CardContent className="space-y-3 border-t border-border-subtle pt-4 pb-4">
          {models.length > 0 ? (
            <Table className="min-w-lg">
              <TableCaption className="sr-only">
                {ui(appText("Models on {{name}}", { name: provider.name }))}
              </TableCaption>
              <TableHeader>
                <TableRow>
                  <TableHead>{ui("Model")}</TableHead>
                  <TableHead className="text-right">{ui("Context")}</TableHead>
                  <TableHead className="text-right">{ui("Max output")}</TableHead>
                  <TableHead className="text-right">{ui("In / 1M")}</TableHead>
                  <TableHead className="text-right">{ui("Out / 1M")}</TableHead>
                  <TableHead className="w-24 text-right">
                    <span className="sr-only">{ui("Actions")}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {models.map((model) => (
                  <TableRow key={model.id}>
                    <TableCell>
                      <span className="flex flex-wrap items-center gap-2">
                        <ChatModelLogo modelName={model.modelName} />
                        <span className="font-main-ui-action text-content-primary">
                          {model.displayName}
                        </span>
                        {model.id === defaultModelId && (
                          <Badge variant="secondary">{ui("Default")}</Badge>
                        )}
                        {!model.visible && <StatusBadge tone="neutral">{ui("Hidden")}</StatusBadge>}
                      </span>
                      <span className="flex flex-wrap items-center gap-2 font-secondary-body text-content-muted">
                        <span className="break-all">{model.modelName}</span>
                        <Capabilities capabilities={model.settings.capabilities} />
                      </span>
                    </TableCell>
                    <TableCell className="text-right tabular-nums whitespace-nowrap">
                      {compactTokens(model.settings.contextWindow)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums whitespace-nowrap">
                      {model.settings.maxOutputTokens == null
                        ? ui("Provider default")
                        : compactTokens(model.settings.maxOutputTokens)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums whitespace-nowrap">
                      {millionTokenPrice(model.settings.pricing?.inputPerMillion) ?? ui("Unknown")}
                    </TableCell>
                    <TableCell className="text-right tabular-nums whitespace-nowrap">
                      {millionTokenPrice(model.settings.pricing?.outputPerMillion) ?? ui("Unknown")}
                    </TableCell>
                    <TableCell>
                      <span className="flex items-center justify-end gap-1">
                        <IconButton
                          prominence="tertiary"
                          size="sm"
                          aria-label={ui(
                            appText("Edit model {{name}}", { name: model.displayName }),
                          )}
                          disabled={unavailable}
                          onClick={() => onEditModel(model)}
                        >
                          <Settings2 />
                        </IconButton>
                        <IconButton
                          tone="danger"
                          prominence="tertiary"
                          size="sm"
                          aria-label={ui(
                            appText("Delete model {{name}}", { name: model.displayName }),
                          )}
                          disabled={unavailable}
                          onClick={() => onDeleteModel(model)}
                        >
                          <Trash2 />
                        </IconButton>
                      </span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <Empty className="gap-3 py-6">
              <EmptyMedia variant="icon">
                <Boxes />
              </EmptyMedia>
              <EmptyTitle className="font-main-ui-action">
                {ui("No configured models for this provider.")}
              </EmptyTitle>
            </Empty>
          )}
          <div className="flex flex-wrap gap-2">
            <Button
              prominence="secondary"
              size="sm"
              disabled={
                unavailable || !adapter || !provider.enabled || !provider.credentialConfigured
              }
              onClick={onDiscover}
            >
              <ListPlus aria-hidden="true" /> {ui("Fetch models from the provider")}
            </Button>
            <Button
              prominence="secondary"
              size="sm"
              disabled={unavailable || !adapter}
              onClick={onAddModel}
            >
              <Plus aria-hidden="true" /> {ui("Add model")}
            </Button>
          </div>
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
    <ProviderCard
      logo={preset.logo}
      name={preset.name}
      description={ui(preset.subtitle)}
      actions={
        <Button
          size="sm"
          prominence="secondary"
          disabled={disabled}
          aria-label={ui(appText("Connect {{name}}", { name: preset.name }))}
          onClick={onConnect}
        >
          {ui("Connect")}
        </Button>
      }
    />
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
    editor?.kind === "model" || editor?.kind === "discovery"
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

      {/* Models by task — the Onyx default card plus its per-flow defaults */}
      {hasProviders && !catalogError && (
        <section aria-labelledby="task-models" className="space-y-3">
          <h2 id="task-models" className="font-heading-h3">
            {ui("Models by task")}
          </h2>
          <Card>
            <CardContent className="divide-y divide-border-subtle [&>*]:py-4 [&>*:first-child]:pt-0 [&>*:last-child]:pb-0">
              {catalogPending ? (
                <p role="status">{ui("Loading model catalog…")}</p>
              ) : (
                <>
                  <TenantDefault
                    providers={providers.data!}
                    models={models}
                    adapters={adapters.data!}
                  />
                  <TaskModels
                    providers={providers.data!}
                    models={models}
                    adapters={adapters.data!}
                  />
                </>
              )}
            </CardContent>
          </Card>
        </section>
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
                defaultModelId={defaultModelId}
                unavailable={unavailable}
                onEdit={() => setEditor({ kind: "provider", initial: provider })}
                onAddModel={() => setEditor({ kind: "model", providerId: provider.id })}
                onDiscover={() => setEditor({ kind: "discovery", providerId: provider.id })}
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
                subtitle: "GPT models from OpenAI.",
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
                subtitle: "Claude models from Anthropic.",
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
              {ui("Gateways & Routers")}
            </h3>
            <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
              <NewConnectionCard
                preset={{
                  name: "9Router",
                  subtitle: "One key routed to several vendors.",
                  baseUrl: "",
                  logo: <ProviderLogo mark="NINEROUTER" />,
                }}
                disabled={unavailable || (providers.data?.length ?? 0) >= 64}
                onConnect={() =>
                  setEditor({ kind: "provider", adapterType: "openai", name: "9Router" })
                }
              />
              <NewConnectionCard
                preset={{
                  name: "OpenRouter",
                  subtitle: "Hosted marketplace of models from many vendors.",
                  baseUrl: "https://openrouter.ai/api/v1",
                  logo: <ProviderLogo mark="OPENROUTER" />,
                }}
                disabled={unavailable || (providers.data?.length ?? 0) >= 64}
                onConnect={() =>
                  setEditor({
                    kind: "provider",
                    adapterType: "openai",
                    baseUrl: "https://openrouter.ai/api/v1",
                    name: "OpenRouter",
                  })
                }
              />
              <NewConnectionCard
                preset={{
                  name: "LiteLLM Proxy",
                  subtitle: "Self-hosted proxy in front of your own provider keys.",
                  baseUrl: "http://localhost:4000/v1",
                  logo: <Route />,
                }}
                disabled={unavailable || (providers.data?.length ?? 0) >= 64}
                onConnect={() =>
                  setEditor({
                    kind: "provider",
                    adapterType: "openai",
                    baseUrl: "http://localhost:4000/v1",
                    name: "LiteLLM Proxy",
                  })
                }
              />
            </div>
          </div>
          <div>
            <h3 className="font-main-ui-action text-content-secondary">
              {ui("Self-hosted & Custom")}
            </h3>
            <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
              <NewConnectionCard
                preset={{
                  name: "Ollama",
                  subtitle: "Open-weight models running on your own machine or server.",
                  baseUrl: "http://localhost:11434/v1",
                  logo: <ProviderLogo mark="OLLAMA" />,
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
                  name: "LM Studio",
                  subtitle:
                    "Local models served by the LM Studio desktop app or its headless server.",
                  baseUrl: "http://localhost:1234/v1",
                  logo: <ProviderLogo mark="LM_STUDIO" />,
                }}
                disabled={unavailable || (providers.data?.length ?? 0) >= 64}
                onConnect={() =>
                  setEditor({
                    kind: "provider",
                    adapterType: "openai",
                    baseUrl: "http://localhost:1234/v1",
                    name: "LM Studio",
                  })
                }
              />
              <NewConnectionCard
                preset={{
                  name: "OpenAI-Compatible",
                  subtitle: "Any endpoint that speaks the OpenAI API, such as vLLM or a gateway.",
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
          key={editor.reported?.modelName ?? editor.modelName ?? editor.initial?.id ?? "new"}
          initial={editor.initial}
          modelName={editor.modelName}
          reported={editor.reported}
          models={models}
          provider={modelProvider}
          adapter={adapters.data?.find((adapter) => adapter.type === modelProvider.adapterType)}
          onClose={() => setEditor(null)}
        />
      )}
      {editor?.kind === "discovery" && modelProvider && (
        <ModelDiscovery
          provider={modelProvider}
          adapter={adapters.data?.find((adapter) => adapter.type === modelProvider.adapterType)}
          models={models.filter((model) => model.providerId === modelProvider.id)}
          onManual={(reported) =>
            setEditor({ kind: "model", providerId: modelProvider.id, reported })
          }
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
            deletion.kind === "provider"
              ? ui(
                  "Every configured model on this provider is removed. Affected Persona defaults are cleared, task models fall back to the conversation model and Chat history is kept. Replace a Tenant default first.",
                )
              : ui(
                  "Affected Persona defaults are cleared, task models fall back to the conversation model and Chat history is kept. Replace a Tenant default first.",
                )
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
