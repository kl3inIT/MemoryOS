import { useQuery } from "@tanstack/react-query";
import { Activity, ChartColumn, CircleAlert, CircleDollarSign, Gauge, Layers } from "lucide-react";
import { EmptyState } from "@/components/composites/empty-state";
import { StatStrip, StatTile } from "@/components/composites/stat-strip";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Progress } from "@/components/ui/progress";
import { ModelLogo } from "@/features/models/model-logo";
import { DailyChart } from "./daily-chart";
import { ShareBar } from "./ai-costs-page";
import { appText, type AppCopy } from "@/i18n/app-text";
import { formatUiDate, uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getMyAiCostsOptions,
  getMyAiUsageStandingOptions,
  listAvailableChatModelsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { AiCostRow, AiUsageStanding, AvailableModel } from "@/lib/hey-api/types.gen";
import { change, count, money, period, previousPeriod, scopeLabels } from "./ai-costs";

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

/** The budget that binds this person, once their organization sets one: what is left, and when it frees. */
function Budget({ standing }: { standing: AiUsageStanding }) {
  const ui = useAppTranslation();
  const byTokens = standing.tokenBudget ? standing.tokensUsed / standing.tokenBudget : 0;
  const byCost = standing.costBudgetUsd ? standing.costUsed / standing.costBudgetUsd : 0;
  const used = Math.min(1, Math.max(byTokens, byCost));
  const whose =
    standing.scope === "GROUP" && standing.groupName
      ? standing.groupName
      : ui(scopeLabels[standing.scope]);
  return (
    <Card size="sm" role="region" aria-label={ui("Spending limit")} className="max-w-2xl">
      <CardContent>
        <div className="flex flex-col gap-2">
          <p className="font-main-ui-action text-content-primary">
            {ui(appText("Budget: {{whose}}", { whose }))}
          </p>
          <p className="font-secondary-body text-content-secondary tabular-nums">
            {standing.tokenBudget
              ? ui(
                  appText("{{used}} / {{budget}} token", {
                    used: count(standing.tokensUsed),
                    budget: count(standing.tokenBudget),
                  }),
                )
              : null}
            {standing.tokenBudget && standing.costBudgetUsd ? " · " : null}
            {standing.costBudgetUsd
              ? ui(
                  appText("{{used}} of {{budget}}", {
                    used: money(standing.costUsed),
                    budget: money(standing.costBudgetUsd),
                  }),
                )
              : null}
          </p>
          <Progress className="h-1.5" value={Math.round(used * 100)} aria-hidden="true" />
          <p className="font-secondary-body text-content-muted">
            {used >= 1
              ? ui(
                  appText("The budget is spent. It frees again on {{when}}.", {
                    when: formatUiDate(standing.resetsAt, {
                      dateStyle: "medium",
                      timeStyle: "short",
                    }),
                  }),
                )
              : ui(
                  appText("Counted over {{days}} days. It frees again on {{when}}.", {
                    days: standing.periodDays,
                    when: formatUiDate(standing.resetsAt, { dateStyle: "medium" }),
                  }),
                )}
          </p>
        </div>
      </CardContent>
    </Card>
  );
}

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
  const standing = useQuery({ ...getMyAiUsageStandingOptions(), retry: false });
  const summary = usage.data?.summary;
  const spend = change(summary?.cost, before.data?.summary.cost);
  const used = (summary?.calls ?? 0) > 0;

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

      {standing.data ? <Budget standing={standing.data} /> : null}

      {usage.isError ? (
        <EmptyState
          role="alert"
          icon={<CircleAlert />}
          title={ui("Couldn't load usage")}
          detail={ui("Something went wrong fetching your usage. Try again in a moment.")}
          action={
            <Button prominence="secondary" onClick={() => void usage.refetch()}>
              {ui("Try again")}
            </Button>
          }
        />
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
          <Card>
            <CardContent>
              <DailyChart days={usage.data.daily} split="MODEL" range={range} />
            </CardContent>
          </Card>
        </section>
      )}

      {usage.isSuccess && (
        <section aria-labelledby="by-model-heading" className="flex min-w-0 flex-col gap-3">
          <h2 id="by-model-heading" className="font-heading-h3 text-content-primary">
            {ui("By model")}
          </h2>
          {used ? (
            <Card size="sm">
              <CardContent>
                <ModelUsageTable rows={usage.data.models} models={models.data} />
              </CardContent>
            </Card>
          ) : (
            <EmptyState
              icon={<ChartColumn />}
              title={ui("No usage recorded yet")}
              detail={ui("Your model usage and costs will show up here once you start chatting.")}
            />
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

/** Tokens, requests and cost per model this period, each with the price the member pays for it. */
function ModelUsageTable({
  rows,
  models,
}: {
  rows: AiCostRow[];
  models: AvailableModel[] | undefined;
}) {
  const ui = useAppTranslation();
  // As Onyx, each model's bar is proportional to the priciest model of the period.
  const top = Math.max(0, ...rows.map((row) => row.cost));
  return (
    <Table aria-labelledby="by-model-heading" className="min-w-md">
      <TableHeader>
        <TableRow>
          <TableHead>{ui("Model")}</TableHead>
          <TableHead className="text-right whitespace-nowrap">{ui("Requests")}</TableHead>
          <TableHead className="text-right">{ui("Total tokens")}</TableHead>
          <TableHead className="text-right whitespace-nowrap">{ui("Cost")}</TableHead>
          <TableHead className="hidden w-32 sm:table-cell">
            <span className="sr-only">{ui("Share of spend")}</span>
          </TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={row.key}>
            <TableCell>
              <span className="flex min-w-0 items-center gap-2">
                <ModelLogo modelName={row.label} />
                <span className="min-w-0 break-all">{row.label}</span>
              </span>
              {price(models, row.label, ui)}
            </TableCell>
            <TableCell className="text-right">
              <span className="tabular-nums">{count(row.calls)}</span>
            </TableCell>
            <TableCell className="text-right">
              <span className="tabular-nums">
                {row.inputTokens + row.outputTokens > 0
                  ? count(row.inputTokens + row.outputTokens)
                  : "—"}
              </span>
            </TableCell>
            <TableCell className="text-right">
              {row.unknownCostCalls === row.calls ? (
                <span className="text-status-warning-content">{ui("Prices unavailable")}</span>
              ) : (
                <span className="tabular-nums">{money(row.cost)}</span>
              )}
            </TableCell>
            <TableCell className="hidden sm:table-cell">
              <ShareBar percent={top > 0 ? (row.cost / top) * 100 : 0} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
