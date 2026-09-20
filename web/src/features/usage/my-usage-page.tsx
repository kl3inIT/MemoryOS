import { useQuery } from "@tanstack/react-query";
import { Activity, ChartColumn, CircleDollarSign, Gauge, Layers } from "lucide-react";
import { StatStrip, StatTile } from "@/components/composites/stat-strip";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Button } from "@/components/ui/button";
import { ChatModelLogo } from "@/features/chat/chat-model-logo";
import { DailyChart } from "./daily-chart";
import { appText, type AppCopy } from "@/i18n/app-text";
import { formatUiDate, uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getMyAiCostsOptions,
  listAvailableChatModelsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { AvailableModel } from "@/lib/hey-api/types.gen";
import { change, count, money, period, previousPeriod } from "./ai-costs";

/** The model's own price under its name: input and output per million tokens, as the prices table showed them. */
function price(models: AvailableModel[] | undefined, label: string, ui: (copy: AppCopy) => string) {
  const pricing = models?.find(
    (model) => model.modelName === label || model.displayName === label,
  )?.pricing;
  if (!pricing) return null;
  return (
    <span className="mt-0.5 block font-secondary-body text-content-muted">
      {ui(
        appText("{{input}} in · {{output}} out", {
          input: perMillion(pricing.inputPerMillion),
          output: perMillion(pricing.outputPerMillion),
        }),
      )}
    </span>
  );
}

const perMillion = (value: number | null | undefined) =>
  value == null
    ? "—"
    : new Intl.NumberFormat(uiLocale(), {
        style: "currency",
        currency: "USD",
        minimumFractionDigits: 2,
        maximumFractionDigits: 3,
      }).format(value);

const day = (iso: string) =>
  formatUiDate(`${iso}T00:00:00Z`, { day: "numeric", month: "numeric", timeZone: "UTC" });

/** Onyx Settings › Usage: the member's own spend this period, tokens per model and the prices they pay. */
export function MyUsagePage() {
  const ui = useAppTranslation();
  const range = period("month");
  const usage = useQuery({
    ...getMyAiCostsOptions({ query: { from: range.from, to: range.to } }),
    retry: false,
  });
  const before = useQuery({
    ...getMyAiCostsOptions({ query: previousPeriod(range) }),
    retry: false,
  });
  const models = useQuery({ ...listAvailableChatModelsOptions(), retry: false });
  const summary = usage.data?.summary;
  const spend = change(summary?.cost, before.data?.summary.cost);
  const used = (summary?.calls ?? 0) > 0;
  // As Onyx, each model's bar is proportional to the priciest model of the period.
  const top = Math.max(0, ...(usage.data?.models ?? []).map((row) => row.cost));

  return (
    <SettingsLayout>
      <PageHeader
        icon={<ChartColumn />}
        title={ui("Usage")}
        description={ui(
          appText(
            "This month ({{from}} – {{to}}). Costs are estimated from recorded model usage.",
            {
              from: day(range.from),
              to: day(range.to),
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
        <StatStrip columns={4}>
          <StatTile
            icon={<CircleDollarSign />}
            iconClass="text-chart-1"
            loading={usage.isPending}
            label={ui("Est. spend")}
            value={summary ? money(summary.cost) : "—"}
            trend={
              spend
                ? {
                    direction: spend.direction,
                    label: ui(appText("{{change}} vs previous period", { change: spend.percent })),
                  }
                : undefined
            }
          />
          <StatTile
            icon={<Activity />}
            iconClass="text-chart-3"
            loading={usage.isPending}
            label={ui("Requests")}
            value={summary ? count(summary.calls) : "—"}
          />
          <StatTile
            icon={<Layers />}
            iconClass="text-chart-2"
            loading={usage.isPending}
            label={ui("Total tokens")}
            value={summary ? count(summary.inputTokens + summary.outputTokens) : "—"}
            hint={
              summary
                ? ui(
                    appText("{{input}} in · {{output}} out · {{cached}} cache reads", {
                      input: count(summary.inputTokens),
                      output: count(summary.outputTokens),
                      cached: count(summary.cacheReadTokens),
                    }),
                  )
                : undefined
            }
          />
          <StatTile
            icon={<Gauge />}
            iconClass="text-chart-4"
            label={ui("Budget")}
            value={ui("No budget set")}
            hint={ui("Your administrator has not set a spending limit for you.")}
          />
        </StatStrip>
      )}

      {usage.isSuccess && usage.data.daily.length > 0 && (
        <section aria-labelledby="daily-heading" className="flex min-w-0 flex-col gap-3">
          <h2 id="daily-heading" className="font-heading-h3 text-content-primary">
            {ui("Daily spend")}
          </h2>
          <div className="rounded-2xl border border-border-subtle bg-surface-raised p-4">
            <DailyChart days={usage.data.daily} split="MODEL" range={range} />
          </div>
        </section>
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
                    <th className="hidden w-32 px-4 py-2 sm:table-cell">
                      <span className="sr-only">{ui("Share of spend")}</span>
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
                        {price(models.data, row.label, ui)}
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
                      <td className="hidden py-2.5 pr-4 sm:table-cell">
                        <div className="h-2 rounded-full bg-surface-sunken" aria-hidden="true">
                          <div
                            className="h-2 rounded-full bg-chart-1"
                            style={{
                              width: `${top > 0 ? Math.max(2, (row.cost / top) * 100) : 0}%`,
                            }}
                          />
                        </div>
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

      <p className="font-secondary-body text-content-muted">
        {ui(
          "Costs are estimates from the tokens the model reports and the prices your administrator set, not a provider invoice.",
        )}
      </p>
    </SettingsLayout>
  );
}
