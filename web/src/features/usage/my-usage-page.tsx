import { useQuery } from "@tanstack/react-query";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Button } from "@/components/ui/button";
import { ChatModelLogo } from "@/features/chat/chat-model-logo";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getMyAiCostsOptions,
  listAvailableChatModelsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { AvailableModel } from "@/lib/hey-api/types.gen";
import { count, money, period } from "./ai-costs";

const perMillion = (value: number | null | undefined) =>
  value == null
    ? "—"
    : new Intl.NumberFormat(undefined, {
        style: "currency",
        currency: "USD",
        minimumFractionDigits: 2,
        maximumFractionDigits: 3,
      }).format(value);

/** Onyx Settings › Usage: the member's own spend this period, tokens per model and the prices they pay. */
export function MyUsagePage() {
  const ui = useAppTranslation();
  const range = period("month");
  const usage = useQuery({
    ...getMyAiCostsOptions({ query: { from: range.from, to: range.to } }),
    retry: false,
  });
  const models = useQuery({ ...listAvailableChatModelsOptions(), retry: false });
  const summary = usage.data?.summary;
  const used = (summary?.calls ?? 0) > 0;

  return (
    <SettingsLayout>
      <PageHeader
        eyebrow={ui("Settings")}
        title={ui("Usage")}
        description={ui(
          appText(
            "This month ({{from}} – {{to}}). Costs are estimated from recorded model usage.",
            {
              from: range.from,
              to: range.to,
            },
          ),
        )}
      />

      {usage.isError ? (
        <div role="alert" className="flex max-w-2xl flex-col items-start gap-2">
          <p className="font-main-ui-action text-content-primary">{ui("Couldn't load usage")}</p>
          <p className="text-content-muted">
            {ui("Something went wrong fetching your usage. Try again in a moment.")}
          </p>
          <Button prominence="secondary" onClick={() => void usage.refetch()}>
            {ui("Try again")}
          </Button>
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          <section
            aria-labelledby="budget-heading"
            className="min-w-0 rounded-2xl border border-border-subtle bg-surface-raised p-4"
          >
            <h2 id="budget-heading" className="font-secondary-body text-content-muted">
              {ui("Budget")}
            </h2>
            <p className="mt-1 font-heading-h3 text-content-primary">{ui("No budget set")}</p>
            <p className="font-secondary-body text-content-muted">
              {ui("Your administrator has not set a spending limit for you.")}
            </p>
          </section>
          <section
            aria-labelledby="period-heading"
            aria-busy={usage.isPending}
            className="min-w-0 rounded-2xl border border-border-subtle bg-surface-raised p-4"
          >
            <h2 id="period-heading" className="font-secondary-body text-content-muted">
              {ui("Usage this period")}
            </h2>
            <p className="mt-1 font-heading-h3 tabular-nums text-content-primary">
              {summary ? ui(appText("{{amount}} spent", { amount: money(summary.cost) })) : "—"}
            </p>
            {summary && (
              <>
                <p className="font-secondary-body tabular-nums text-content-muted">
                  {ui(
                    appText("{{calls}} requests · {{tokens}} tokens", {
                      calls: count(summary.calls),
                      tokens: count(summary.inputTokens + summary.outputTokens),
                    }),
                  )}
                </p>
                <p className="font-secondary-body tabular-nums text-content-muted">
                  {ui(
                    appText("{{input}} in · {{output}} out · {{cached}} cache reads", {
                      input: count(summary.inputTokens),
                      output: count(summary.outputTokens),
                      cached: count(summary.cacheReadTokens),
                    }),
                  )}
                </p>
              </>
            )}
          </section>
        </div>
      )}

      {usage.isSuccess && (
        <section aria-labelledby="by-model-heading" className="flex min-w-0 flex-col gap-3">
          <h2 id="by-model-heading" className="font-heading-h3 text-content-primary">
            {ui("By model")}
          </h2>
          {used ? (
            <div className="relative overflow-x-auto rounded-2xl border border-border-subtle bg-surface-raised">
              <table
                aria-labelledby="by-model-heading"
                className="w-full min-w-md font-main-ui-body tabular-nums"
              >
                <thead className="font-secondary-body text-content-muted">
                  <tr className="border-b border-border-subtle">
                    <th className="px-4 py-2 text-left font-normal">{ui("Model")}</th>
                    <th className="px-4 py-2 text-right font-normal whitespace-nowrap">
                      {ui("Requests")}
                    </th>
                    <th className="px-4 py-2 text-right font-normal">{ui("Total tokens")}</th>
                    <th className="px-4 py-2 text-right font-normal whitespace-nowrap">
                      {ui("Cost")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {usage.data.models.map((row) => (
                    <tr key={row.key} className="border-b border-border-subtle last:border-0">
                      <td className="px-4 py-2.5">
                        <span className="flex min-w-0 items-center gap-2">
                          <ChatModelLogo modelName={row.label} />
                          <span className="min-w-0 break-all">{row.label}</span>
                        </span>
                      </td>
                      <td className="px-4 py-2.5 text-right">{count(row.calls)}</td>
                      <td className="px-4 py-2.5 text-right">
                        {row.inputTokens + row.outputTokens > 0
                          ? count(row.inputTokens + row.outputTokens)
                          : "—"}
                      </td>
                      <td className="px-4 py-2.5 text-right">
                        {row.unknownCostCalls === row.calls ? (
                          <span className="text-status-warning-content">
                            {ui("Prices unavailable")}
                          </span>
                        ) : (
                          money(row.cost)
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="rounded-2xl border border-border-subtle bg-surface-raised p-4">
              <p className="font-main-ui-action text-content-primary">
                {ui("No usage recorded yet")}
              </p>
              <p className="text-content-muted">
                {ui("Your model usage and costs will show up here once you start chatting.")}
              </p>
            </div>
          )}
        </section>
      )}

      <ModelPrices models={models.data} />
      <p className="font-secondary-body text-content-muted">
        {ui(
          "Costs are estimates from the tokens the model reports and the prices your administrator set, not a provider invoice.",
        )}
      </p>
    </SettingsLayout>
  );
}

/** Onyx "Model prices": USD per 1M tokens for every model the member may use, the default marked. */
function ModelPrices({ models }: { models: AvailableModel[] | undefined }) {
  const ui = useAppTranslation();
  if (!models?.length) return null;
  return (
    <section aria-labelledby="prices-heading" className="flex min-w-0 flex-col gap-3">
      <div>
        <h2 id="prices-heading" className="font-heading-h3 text-content-primary">
          {ui("Model prices")}
        </h2>
        <p className="text-content-muted">
          {ui("USD per 1M tokens (input · output · cache) for every available model")}
        </p>
      </div>
      <div className="relative overflow-x-auto rounded-2xl border border-border-subtle bg-surface-raised">
        <table
          aria-labelledby="prices-heading"
          className="w-full min-w-md font-main-ui-body tabular-nums"
        >
          <thead className="font-secondary-body text-content-muted">
            <tr className="border-b border-border-subtle">
              <th className="px-4 py-2 text-left font-normal">{ui("Model")}</th>
              <th className="px-4 py-2 text-right font-normal">{ui("Input")}</th>
              <th className="px-4 py-2 text-right font-normal">{ui("Output")}</th>
              <th className="px-4 py-2 text-right font-normal">{ui("Cache-read")}</th>
            </tr>
          </thead>
          <tbody>
            {models.map((model) => (
              <tr key={model.id} className="border-b border-border-subtle last:border-0">
                <td className="px-4 py-2.5">
                  <span className="flex min-w-0 flex-wrap items-center gap-2">
                    <ChatModelLogo modelName={model.modelName} />
                    <span className="min-w-0 break-all">{model.displayName}</span>
                    {model.isDefault && (
                      <span className="rounded-full bg-surface-sunken px-2 py-px font-secondary-body text-content-muted">
                        {ui("Default")}
                      </span>
                    )}
                  </span>
                </td>
                {model.pricing ? (
                  <>
                    <td className="px-4 py-2.5 text-right">
                      {perMillion(model.pricing.inputPerMillion)}
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      {perMillion(model.pricing.outputPerMillion)}
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      {perMillion(
                        model.pricing.cachedInputPerMillion ?? model.pricing.inputPerMillion,
                      )}
                    </td>
                  </>
                ) : (
                  <td colSpan={3} className="px-4 py-2.5 text-right text-status-warning-content">
                    {ui("Prices unavailable")}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
