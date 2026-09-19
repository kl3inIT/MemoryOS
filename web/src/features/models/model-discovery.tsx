import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
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
 * (OpenRouter, vLLM, Mistral, Groq) or the installed catalog declares by name (OpenAI, Anthropic, Gemini, xAI,
 * DeepSeek). A complete model is added as reported; an incomplete one opens the editor with what is known (MEM-130).
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
                        <TableCell className="max-w-72 font-main-ui-body">
                          {model.complete && !alreadyConfigured ? (
                            <label className="flex items-center gap-2">
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
                              <span className="truncate" title={model.modelName}>
                                {model.modelName}
                              </span>
                            </label>
                          ) : (
                            <span className="block truncate" title={model.modelName}>
                              {model.modelName}
                            </span>
                          )}
                        </TableCell>
                        <TableCell className="text-right tabular-nums">
                          {model.contextWindow == null ? "—" : compactTokens(model.contextWindow)}
                        </TableCell>
                        <TableCell className="hidden text-right tabular-nums sm:table-cell">
                          {model.maxOutputTokens == null
                            ? "—"
                            : compactTokens(model.maxOutputTokens)}
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
                          ) : model.complete ? null : (
                            <Button
                              prominence="secondary"
                              size="sm"
                              disabled={action.pending}
                              onClick={() => onManual(model)}
                            >
                              {ui("Add manually")}
                            </Button>
                          )}
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
