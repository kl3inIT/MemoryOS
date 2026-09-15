import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Empty, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
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
  changeModelDraft,
  compactTokens,
  findKnownModel,
  millionTokenPrice,
  modelBody,
  modelDraft,
  refreshModelCatalog,
  type InstalledAdapter,
  type ManagedModel,
  type ManagedProvider,
} from "./model-catalog";
import { useModelAction } from "./use-model-action";

/**
 * The provider endpoint reports which models it serves; the installed catalog supplies their limits
 * and prices. A model the catalog does not declare still needs those typed, so it opens the editor.
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
  onManual: (modelName: string) => void;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const client = useQueryClient();
  const action = useModelAction();
  const [selected, setSelected] = useState<string[]>([]);
  const reported = useQuery({
    ...listReportedProviderModelsOptions({ path: { providerId: provider.id } }),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  const configured = new Set(models.map((model) => model.modelName));

  async function add() {
    try {
      await action.run(async (signal) => {
        for (const modelName of selected) {
          const draft = changeModelDraft(
            modelDraft(undefined, adapter),
            "modelName",
            modelName,
            adapter,
          );
          await createChatModel({
            path: { providerId: provider.id },
            body: modelBody(draft),
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
        {reported.data && reported.data.models.length === 0 && (
          <Empty className="gap-3 py-6">
            <EmptyMedia variant="icon">
              <span aria-hidden="true">·</span>
            </EmptyMedia>
            <EmptyTitle className="font-main-ui-action">
              {ui("This endpoint reported no models.")}
            </EmptyTitle>
          </Empty>
        )}
        {reported.data && reported.data.models.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{ui("Model")}</TableHead>
                <TableHead className="text-right">{ui("Context")}</TableHead>
                <TableHead className="hidden text-right sm:table-cell">{ui("In / 1M")}</TableHead>
                <TableHead className="hidden text-right sm:table-cell">{ui("Out / 1M")}</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {reported.data.models.map((modelName) => {
                const known = findKnownModel(adapter, modelName);
                const alreadyConfigured = configured.has(modelName);
                return (
                  <TableRow key={modelName}>
                    <TableCell className="font-main-ui-body">
                      {known && !alreadyConfigured ? (
                        <label className="flex items-center gap-2">
                          <Checkbox
                            checked={selected.includes(modelName)}
                            disabled={action.pending}
                            onCheckedChange={(checked) =>
                              setSelected((current) =>
                                checked === true
                                  ? [...current, modelName]
                                  : current.filter((name) => name !== modelName),
                              )
                            }
                          />
                          {modelName}
                        </label>
                      ) : (
                        modelName
                      )}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {known ? compactTokens(known.contextWindow) : "—"}
                    </TableCell>
                    <TableCell className="hidden text-right tabular-nums sm:table-cell">
                      {known ? millionTokenPrice(known.pricing.inputPerMillion) : "—"}
                    </TableCell>
                    <TableCell className="hidden text-right tabular-nums sm:table-cell">
                      {known ? millionTokenPrice(known.pricing.outputPerMillion) : "—"}
                    </TableCell>
                    <TableCell className="text-right">
                      {alreadyConfigured ? (
                        <StatusBadge tone="success">{ui("Configured")}</StatusBadge>
                      ) : known ? null : (
                        <Button
                          prominence="secondary"
                          size="sm"
                          disabled={action.pending}
                          onClick={() => onManual(modelName)}
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
