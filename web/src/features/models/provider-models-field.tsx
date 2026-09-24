import { ModelLogo } from "./model-logo";
import { Brain, Eye, RefreshCw, Search, Wrench } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
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
import { listReportedProviderModels } from "@/lib/hey-api/sdk.gen";
import type { ProviderTestInput } from "@/lib/hey-api/types.gen";
import {
  compactTokens,
  millionTokenPrice,
  type ManagedModel,
  type ReportedModel,
} from "./model-catalog";
import { useModelMutation } from "./model-mutation";

/**
 * The models of the provider being edited, listed inside its own form as Onyx lists them: the endpoint is read with
 * the endpoint and key currently typed, saved or not, and the chosen models are created when the form is saved. The
 * key is read at click time and sent directly, never through a query cache.
 */
export function ProviderModelsField({
  connection,
  configured,
  selected,
  onSelected,
  disabled,
  listOnOpen = false,
}: {
  connection: () => ProviderTestInput | null;
  configured: ManagedModel[];
  selected: ReportedModel[];
  onSelected: (models: ReportedModel[]) => void;
  disabled?: boolean;
  /** Opened from the connection's "Fetch models" button: the endpoint is read at once and the list brought into view. */
  listOnOpen?: boolean;
}) {
  const ui = useAppTranslation();
  const [reported, setReported] = useState<ReportedModel[] | null>(null);
  const listing = useModelMutation(async (signal) => {
    // The endpoint and key are read at the press, never held as mutation variables.
    const body = connection();
    if (!body) return;
    const result = await listReportedProviderModels({
      body,
      signal,
    });
    signal.throwIfAborted();
    setReported(result.data.models);
    onSelected([]);
  });
  const [query, setQuery] = useState("");
  const already = new Set(configured.map((model) => model.modelName));
  const all = useMemo(() => reported ?? [], [reported]);
  const shown = useMemo(() => {
    const terms = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
    return terms.length
      ? all.filter((model) => terms.every((term) => model.modelName.toLowerCase().includes(term)))
      : all;
  }, [all, query]);
  const selectable = useMemo(
    () => shown.filter((model) => !already.has(model.modelName)),
    // `already` is derived from the configured models on every render; its contents drive this list.
    [shown, configured], // eslint-disable-line react-hooks/exhaustive-deps
  );

  async function refresh() {
    if (connection() === null || listing.pending) return;
    // A second press replaces the first listing rather than racing it, as Onyx's refetch button does.
    listing.cancel();
    try {
      await listing.run();
    } catch {
      /* Safe action-local feedback only. */
    }
  }

  const field = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!listOnOpen) return;
    field.current?.scrollIntoView({ block: "nearest" });
    // A second run (React's development double effect) replaces the first listing rather than racing it.
    void refresh();
    // On opening only; later listings are the owner's own press.
  }, [listOnOpen]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div ref={field} className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="font-main-ui-action">{ui("Models")}</p>
          {reported === null ? null : (
            <p className="text-xs text-content-muted">
              {selected.length > 0
                ? ui(
                    appText("{{shown}} of {{total}} models · {{selected}} selected", {
                      shown: shown.length,
                      total: all.length,
                      selected: selected.length,
                    }),
                  )
                : ui(
                    appText("{{shown}} of {{total}} models", {
                      shown: shown.length,
                      total: all.length,
                    }),
                  )}
            </p>
          )}
        </div>
        <Button
          prominence="secondary"
          pending={listing.pending}
          disabled={disabled || connection() === null || listing.pending}
          onClick={() => void refresh()}
        >
          <RefreshCw aria-hidden="true" />
          {reported === null ? ui("List models") : ui("Refresh")}
        </Button>
      </div>
      {listing.error && <p role="alert">{ui(listing.error)}</p>}
      {reported !== null && all.length === 0 && !listing.pending && (
        <p className="text-sm text-content-muted">{ui("This endpoint reported no models.")}</p>
      )}
      {all.length > 0 && (
        <>
          <div className="relative">
            <Search
              className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-content-muted"
              aria-hidden="true"
            />
            <Input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={ui("Search models…")}
              aria-label={ui("Search models")}
              className="pl-8"
            />
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              prominence="tertiary"
              size="sm"
              disabled={disabled || listing.pending || selectable.length === 0}
              onClick={() =>
                onSelected([
                  ...selected,
                  ...selectable.filter(
                    (model) => !selected.some((one) => one.modelName === model.modelName),
                  ),
                ])
              }
            >
              {ui("Select all shown")}
            </Button>
            <Button
              prominence="tertiary"
              size="sm"
              disabled={disabled || listing.pending || selected.length === 0}
              onClick={() => onSelected([])}
            >
              {ui("Clear selection")}
            </Button>
          </div>
          <div className="max-h-[40dvh] overflow-y-auto rounded-lg border border-border-subtle">
            <Table>
              <TableHeader className="sticky top-0 bg-surface-subtle">
                <TableRow>
                  <TableHead>{ui("Model")}</TableHead>
                  <TableHead className="text-right">{ui("Context")}</TableHead>
                  <TableHead className="hidden text-right sm:table-cell">{ui("Output")}</TableHead>
                  <TableHead className="hidden text-right md:table-cell">{ui("In / 1M")}</TableHead>
                  <TableHead className="hidden text-right md:table-cell">
                    {ui("Out / 1M")}
                  </TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {shown.map((model) => {
                  const configuredAlready = already.has(model.modelName);
                  return (
                    <TableRow key={model.modelName}>
                      <TableCell className="max-w-44 font-main-ui-body sm:max-w-72">
                        {!configuredAlready ? (
                          <label className="flex min-w-0 items-center gap-2">
                            <Checkbox
                              checked={selected.some((one) => one.modelName === model.modelName)}
                              disabled={disabled || listing.pending}
                              onCheckedChange={(checked) =>
                                onSelected(
                                  checked === true
                                    ? [...selected, model]
                                    : selected.filter((one) => one.modelName !== model.modelName),
                                )
                              }
                            />
                            <ModelName model={model} />
                          </label>
                        ) : (
                          // A checked, locked box keeps configured names aligned with selectable ones.
                          <span className="flex min-w-0 items-center gap-2">
                            <Checkbox checked disabled aria-label={ui("Configured")} />
                            <ModelName model={model} />
                          </span>
                        )}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {compactTokens(model.contextWindow)}
                        {model.source === "none" && (
                          <span className="block text-xs text-content-muted">{ui("Default")}</span>
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
                        {configuredAlready ? (
                          <StatusBadge tone="success">{ui("Configured")}</StatusBadge>
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
    </div>
  );
}

/** The model id with the capabilities the endpoint or catalog published, as compact icons. */
function ModelName({ model }: { model: ReportedModel }) {
  const ui = useAppTranslation();
  const capabilities: [boolean, typeof Wrench, string][] = [
    [model.capabilities.toolCalling, Wrench, ui("Tools")],
    [model.capabilities.vision, Eye, ui("Vision")],
    [model.capabilities.reasoning, Brain, ui("Reasoning")],
  ];
  return (
    <span className="flex min-w-0 items-center gap-2">
      <ModelLogo modelName={model.modelName} className="size-4 shrink-0" />
      <span className="truncate" title={model.modelName}>
        {model.modelName}
      </span>
      <span className="flex shrink-0 items-center gap-1 text-content-muted">
        {capabilities.map(
          ([enabled, Icon, label]) =>
            enabled && <Icon key={label} className="size-3.5" aria-label={label} />,
        )}
      </span>
    </span>
  );
}
