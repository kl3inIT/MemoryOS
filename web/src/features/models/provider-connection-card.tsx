import {
  Boxes,
  Brain,
  ChevronDown,
  Eye,
  ListPlus,
  Plus,
  PlugZap,
  Server,
  Settings2,
  Trash2,
  Wrench,
} from "lucide-react";
import { useState } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { providerTileClassName } from "@/components/provider-logos/provider-card";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";
import { hasProviderMark, type ProviderMark } from "@/components/provider-logos/provider-marks";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { IconButton } from "@/components/ui/icon-button";
import { Separator } from "@/components/ui/separator";
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
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { DataBoundaryTag } from "./data-boundary";
import {
  compactTokens,
  millionTokenPrice,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
} from "./model-catalog";
import { ModelLogo } from "./model-logo";
import { useProviderTest } from "./provider-test";

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

export type ConnectionActions = {
  onEdit: () => void;
  onAddModel: () => void;
  onDiscover: () => void;
  onEditModel: (model: ManagedModel) => void;
  onDelete: () => void;
  onDeleteModel: (model: ManagedModel) => void;
};

/** One saved connection, collapsed to its identity and actions, expanded to its configured models. */
export function ProviderConnectionCard({
  provider,
  models,
  adapters,
  isDefault,
  defaultModelId,
  unavailable,
  actions,
}: {
  provider: ManagedProvider;
  models: ManagedModel[];
  adapters: InstalledAdapter[];
  isDefault: boolean;
  defaultModelId: string | null;
  unavailable: boolean;
  actions: ConnectionActions;
}) {
  const ui = useAppTranslation();
  const [open, setOpen] = useState(false);
  const status = providerStatus(provider);
  const adapter = adapters.find((entry) => entry.type === provider.adapterType);
  const mark = providerMark(provider);
  const connection = useProviderTest();
  const toggle = () => setOpen((current) => !current);
  return (
    <Card size="sm" className="overflow-visible">
      <CardContent>
        <div className="flex w-full items-center gap-3">
          <button
            type="button"
            aria-expanded={open}
            aria-label={ui(appText("Provider {{name}}", { name: provider.name }))}
            onClick={toggle}
            className="flex min-w-0 flex-1 items-center gap-3 rounded-lg text-left transition-colors hover:bg-surface-base"
          >
            <span className={cn(providerTileClassName, "text-content-secondary")}>
              {mark ? (
                <ProviderLogo mark={mark} />
              ) : (
                <Server className="size-4" aria-hidden="true" />
              )}
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
              onClick={actions.onEdit}
            >
              <Settings2 />
            </IconButton>
            <IconButton
              prominence="tertiary"
              tone="danger"
              size="sm"
              aria-label={ui(appText("Delete provider {{name}}", { name: provider.name }))}
              disabled={unavailable}
              onClick={actions.onDelete}
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
              onClick={toggle}
            >
              <ChevronDown className={cn("transition-transform", open && "rotate-180")} />
            </IconButton>
          </span>
        </div>
      </CardContent>
      {connection.outcome && (
        <CardContent>
          <Alert
            variant={connection.outcome.ok ? "success" : "destructive"}
            role={connection.outcome.ok ? "status" : "alert"}
          >
            <AlertDescription>{ui(connection.outcome.message)}</AlertDescription>
          </Alert>
        </CardContent>
      )}
      {open && (
        <>
          <Separator />
          <CardContent>
            <div className="flex flex-col gap-3">
              {models.length > 0 ? (
                <ProviderModelsTable
                  provider={provider}
                  models={models}
                  defaultModelId={defaultModelId}
                  unavailable={unavailable}
                  actions={actions}
                />
              ) : (
                <EmptyState
                  icon={<Boxes />}
                  title={ui("No configured models for this provider.")}
                />
              )}
              <div className="flex flex-wrap gap-2">
                <Button
                  prominence="secondary"
                  size="sm"
                  disabled={
                    unavailable || !adapter || !provider.enabled || !provider.credentialConfigured
                  }
                  onClick={actions.onDiscover}
                >
                  <ListPlus data-icon="inline-start" aria-hidden="true" />
                  {ui("Fetch models from the provider")}
                </Button>
                <Button
                  prominence="secondary"
                  size="sm"
                  disabled={unavailable || !adapter}
                  onClick={actions.onAddModel}
                >
                  <Plus data-icon="inline-start" aria-hidden="true" />
                  {ui("Add model")}
                </Button>
              </div>
            </div>
          </CardContent>
        </>
      )}
    </Card>
  );
}

function ProviderModelsTable({
  provider,
  models,
  defaultModelId,
  unavailable,
  actions,
}: {
  provider: ManagedProvider;
  models: ManagedModel[];
  defaultModelId: string | null;
  unavailable: boolean;
  actions: ConnectionActions;
}) {
  const ui = useAppTranslation();
  return (
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
                <ModelLogo modelName={model.modelName} />
                <span className="font-main-ui-action text-content-primary">
                  {model.displayName}
                </span>
                {model.id === defaultModelId && <Badge variant="secondary">{ui("Default")}</Badge>}
                {!model.visible && <StatusBadge tone="neutral">{ui("Hidden")}</StatusBadge>}
              </span>
              <span className="flex flex-wrap items-center gap-2 font-secondary-body text-content-muted">
                <span className="break-all">{model.modelName}</span>
                <Capabilities capabilities={model.settings.capabilities} />
              </span>
            </TableCell>
            <TableCell className="text-right whitespace-nowrap">
              <span className="tabular-nums">{compactTokens(model.settings.contextWindow)}</span>
            </TableCell>
            <TableCell className="text-right whitespace-nowrap">
              <span className="tabular-nums">
                {model.settings.maxOutputTokens == null
                  ? ui("Provider default")
                  : compactTokens(model.settings.maxOutputTokens)}
              </span>
            </TableCell>
            <TableCell className="text-right whitespace-nowrap">
              <span className="tabular-nums">
                {millionTokenPrice(model.settings.pricing?.inputPerMillion) ?? ui("Unknown")}
              </span>
            </TableCell>
            <TableCell className="text-right whitespace-nowrap">
              <span className="tabular-nums">
                {millionTokenPrice(model.settings.pricing?.outputPerMillion) ?? ui("Unknown")}
              </span>
            </TableCell>
            <TableCell>
              <span className="flex items-center justify-end gap-1">
                <IconButton
                  prominence="tertiary"
                  size="sm"
                  aria-label={ui(appText("Edit model {{name}}", { name: model.displayName }))}
                  disabled={unavailable}
                  onClick={() => actions.onEditModel(model)}
                >
                  <Settings2 />
                </IconButton>
                <IconButton
                  tone="danger"
                  prominence="tertiary"
                  size="sm"
                  aria-label={ui(appText("Delete model {{name}}", { name: model.displayName }))}
                  disabled={unavailable}
                  onClick={() => actions.onDeleteModel(model)}
                >
                  <Trash2 />
                </IconButton>
              </span>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
