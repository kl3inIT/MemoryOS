import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Brain, Eye, Pencil, Search, Wrench } from "lucide-react";
import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Checkbox } from "@/components/ui/checkbox";
import { Empty, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Input } from "@/components/ui/input";
import { StatusBadge } from "@/components/ui/status-badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { sameOriginMutationHeaders } from "@/lib/api";
import { listReportedProviderModelsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { createChatModel } from "@/lib/hey-api/sdk.gen";
import { CatalogDialog } from "./catalog-dialog";
import {
  compactTokens,
  millionTokenPrice,
  modelBody,
  refreshModelCatalog,
  reportedDraft,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
  type ReportedModel,
} from "./model-catalog";
import { useModelAction } from "./use-model-action";

/**
 * The provider endpoint reports which models it serves, with the limits, capabilities and prices it publishes
 * (OpenRouter, 9Router, vLLM, Mistral, Groq) or the installed catalog declares by name (OpenAI, Anthropic, Gemini,
 * xAI, DeepSeek). As Onyx, every model is added without typing: an unknown output limit sends no cap, and a model
 * nobody describes takes Onyx's defaults, marked for review with an option to edit before adding (MEM-130).
 */
export function ModelDiscovery({
  provider,
  adapter,
  models,
  onManual,
  onClose,
}: {
  provider: ManagedProvider;
  adapter?: InstalledAdapter;
  models: ManagedModel[];
  onManual: (reported: ReportedModel) => void;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const client = useQueryClient();
  const action = useModelAction();
  const [selected, setSelected] = useState<string[]>([]);
  const [query, setQuery] = useState("");
  const reported = useQuery({
    ...listReportedProviderModelsOptions({ path: { providerId: provider.id } }),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  const configured = new Set(models.map((model) => model.modelName));
  const reportedModels = reported.data?.models;
  const all = useMemo(() => reportedModels ?? [], [reportedModels]);
  const shown = useMemo(() => {
    const terms = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
    return terms.length
      ? all.filter((model) => terms.every((term) => model.modelName.toLowerCase().includes(term)))
      : all;
  }, [all, query]);

  async function add() {
    const chosen = all.filter((model) => selected.includes(model.modelName));
    try {
      await action.run(async (signal) => {
        for (const model of chosen) {
          await createChatModel({
            path: { providerId: provider.id },
            body: modelBody(reportedDraft(model, adapter)),
            headers: sameOriginMutationHeaders,
            signal,
            throwOnError: true,
          });
          signal.throwIfAborted();
        }
        await refreshModelCatalog(client);
        signal.throwIfAborted();
        onClose();
      });
    } catch {
      /* Safe action-local feedback only. */
    }
  }

  return (
    <CatalogDialog
      title={ui("Models reported by the provider")}
      description={provider.name}
      onClose={() => {
        action.cancel();
        onClose();
      }}
    >
      <div className="space-y-4">
        {reported.isPending && <p role="status">{ui("Loading…")}</p>}
        {reported.isError && <p role="alert">{ui("The provider did not answer.")}</p>}
        {reported.data && all.length === 0 && (
          <Empty className="gap-3 py-6">
            <EmptyMedia variant="icon">
              <span aria-hidden="true">·</span>
            </EmptyMedia>
            <EmptyTitle className="font-main-ui-action">
              {ui("This endpoint reported no models.")}
            </EmptyTitle>
          </Empty>
        )}
        {all.length > 0 && (
          <>
            <div className="relative">
              <Search
                className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-content-muted"
                aria-hidden="true"
              />
              <Input
                autoFocus
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder={ui("Search models…")}
                aria-label={ui("Search models")}
                className="pl-8"
              />
            </div>
            <p className="text-xs text-content-muted">
              {ui(
                appText("{{shown}} of {{total}} models", {
                  shown: shown.length,
                  total: all.length,
                }),
              )}
            </p>
            <div className="max-h-[55dvh] overflow-y-auto rounded-lg border border-border-subtle">
              <Table>
                <TableHeader className="sticky top-0 bg-surface-subtle">
                  <TableRow>
                    <TableHead>{ui("Model")}</TableHead>
                    <TableHead className="text-right">{ui("Context")}</TableHead>
                    <TableHead className="hidden text-right sm:table-cell">
                      {ui("Output")}
                    </TableHead>
                    <TableHead className="hidden text-right md:table-cell">
                      {ui("In / 1M")}
                    </TableHead>
                    <TableHead className="hidden text-right md:table-cell">
                      {ui("Out / 1M")}
                    </TableHead>
                    <TableHead />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {shown.map((model) => {
                    const alreadyConfigured = configured.has(model.modelName);
                    return (
                      <TableRow key={model.modelName}>
                        <TableCell className="max-w-44 font-main-ui-body sm:max-w-72">
                          {!alreadyConfigured ? (
                            <label className="flex min-w-0 items-center gap-2">
                              <Checkbox
                                checked={selected.includes(model.modelName)}
                                disabled={action.pending}
                                onCheckedChange={(checked) =>
                                  setSelected((current) =>
                                    checked === true
                                      ? [...current, model.modelName]
                                      : current.filter((name) => name !== model.modelName),
                                  )
                                }
                              />
                              <ModelName model={model} />
                            </label>
                          ) : (
                            <ModelName model={model} />
                          )}
                        </TableCell>
                        <TableCell className="text-right tabular-nums">
                          {compactTokens(model.contextWindow)}
                          {model.source === "none" && (
                            <span className="block text-xs text-content-muted">
                              {ui("Default")}
                            </span>
                          )}
                        </TableCell>
                        <TableCell className="hidden text-right tabular-nums sm:table-cell">
                          {model.maxOutputTokens == null ? (
                            <span className="text-content-muted">{ui("Default")}</span>
                          ) : (
                            compactTokens(model.maxOutputTokens)
                          )}
                        </TableCell>
                        <TableCell className="hidden text-right tabular-nums md:table-cell">
                          {millionTokenPrice(model.pricing?.inputPerMillion) ?? "—"}
                        </TableCell>
                        <TableCell className="hidden text-right tabular-nums md:table-cell">
                          {millionTokenPrice(model.pricing?.outputPerMillion) ?? "—"}
                        </TableCell>
                        <TableCell className="text-right">
                          {alreadyConfigured ? (
                            <StatusBadge tone="success">{ui("Configured")}</StatusBadge>
                          ) : model.source === "none" ? (
                            <IconButton
                              prominence="tertiary"
                              size="sm"
                              aria-label={ui("Edit first")}
                              title={ui("Edit first")}
                              disabled={action.pending}
                              onClick={() => onManual(model)}
                            >
                              <Pencil />
                            </IconButton>
                          ) : null}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </div>
          </>
        )}
        {action.error && <p role="alert">{ui(action.error)}</p>}
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
            pending={action.pending}
            disabled={selected.length === 0 || action.pending}
            onClick={() => void add()}
          >
            {selected.length === 0
              ? ui("Add model")
              : ui(appText("Add {{count}} models", { count: selected.length }))}
          </Button>
        </div>
      </div>
    </CatalogDialog>
  );
}

/** The model id with the capabilities the endpoint or catalog published, as compact icons. */
function ModelName({ model }: { model: ReportedModel }) {
  const ui = useAppTranslation();
  const capabilities = model.capabilities;
  const flags = [
    capabilities.toolCalling && { icon: Wrench, label: ui("Tool calling") },
    capabilities.vision && { icon: Eye, label: ui("Vision input") },
    capabilities.reasoning && { icon: Brain, label: ui("Reasoning") },
  ].filter((flag) => flag !== false);
  return (
    <span className="flex min-w-0 flex-col">
      <span className="truncate" title={model.modelName}>
        {model.modelName}
      </span>
      {flags.length > 0 && (
        <span className="flex gap-1.5 text-content-muted">
          {flags.map(({ icon: Icon, label }) => (
            <Icon key={label} className="size-3.5" aria-label={label} role="img">
              <title>{label}</title>
            </Icon>
          ))}
        </span>
      )}
    </span>
  );
}
