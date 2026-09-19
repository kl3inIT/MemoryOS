import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ReceiptText } from "lucide-react";
import { useState, type ReactNode } from "react";
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from "recharts";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/radix-select";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { cn } from "@/lib/utils";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { appText } from "@/i18n/app-text";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getAiCostDetailOptions,
  getAiCostSummaryOptions,
  listAiCostBreakdownOptions,
  listAiCostDaysOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { AiCostRow, AiCostSummary } from "@/lib/hey-api/types.gen";
import {
  chartRows,
  count,
  columnLabels,
  dimensionLabels,
  money,
  period,
  periodLabels,
  rowLabel,
  type Dimension,
  type Period,
  type PeriodId,
  seriesColor,
} from "./ai-costs";

const dimensions: Dimension[] = ["ACTOR", "GROUP", "MODEL", "FLOW", "PROVIDER"];
const periods: PeriodId[] = ["7d", "30d", "month", "lastMonth"];

/** Admin › Monitoring › AI costs (Onyx Usage): known spend, what is still unpriced, and who and what spends it. */
export function AiCostsPage() {
  const ui = useAppTranslation();
  const [periodId, setPeriodId] = useState<PeriodId>("month");
  const range = period(periodId);
  const [split, setSplit] = useState<"BOUNDARY" | "MODEL">("BOUNDARY");
  const [dimension, setDimension] = useState<Dimension>("ACTOR");
  const [person, setPerson] = useState<AiCostRow | null>(null);
  const query = { from: range.from, to: range.to };
  const summary = useQuery(getAiCostSummaryOptions({ query }));
  const days = useQuery(listAiCostDaysOptions({ query: { ...query, split } }));
  const rows = useQuery(
    listAiCostBreakdownOptions({ query: { ...query, by: dimension, limit: 50 } }),
  );

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("AI costs")}
        icon={<ReceiptText />}
        description={ui(
          "Monitor workspace spend and review usage by user. Costs are calculated from recorded model usage.",
        )}
        actions={
          <Select value={periodId} onValueChange={(value) => setPeriodId(value as PeriodId)}>
            <SelectTrigger aria-label={ui("Period")} className="w-44">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {periods.map((id) => (
                <SelectItem key={id} value={id}>
                  {ui(periodLabels[id])}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        }
      />

      {summary.isError ? (
        <p role="alert">{ui("Something went wrong fetching your usage. Try again in a moment.")}</p>
      ) : (
        <Summary value={summary.data} />
      )}

      <Card className="min-w-0">
        <CardContent className="min-w-0 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="font-heading-h3">{ui("Daily spend")}</h2>
            <Tabs value={split} onValueChange={(value) => setSplit(value as "BOUNDARY" | "MODEL")}>
              <TabsList>
                <TabsTrigger value="BOUNDARY">{ui("Internal and External")}</TabsTrigger>
                <TabsTrigger value="MODEL">{ui("By model")}</TabsTrigger>
              </TabsList>
            </Tabs>
          </div>
          {days.data ? (
            <DailyChart days={days.data} split={split} range={range} />
          ) : days.isError ? (
            <p role="alert">
              {ui("Something went wrong fetching your usage. Try again in a moment.")}
            </p>
          ) : (
            <p role="status">{ui("Loading daily costs…")}</p>
          )}
        </CardContent>
      </Card>

      <Card className="min-w-0">
        <CardContent className="min-w-0 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="font-heading-h3">{ui("Breakdown")}</h2>
            <Tabs
              value={dimension}
              onValueChange={(value) => setDimension(value as Dimension)}
              className="max-w-full min-w-0 overflow-x-auto"
            >
              <TabsList>
                {dimensions.map((value) => (
                  <TabsTrigger key={value} value={value}>
                    {ui(dimensionLabels[value])}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>
          </div>
          {dimension === "GROUP" && (
            <p className="font-secondary-body text-content-muted">
              {ui(
                "A person counts in every Group they belong to, so Group totals can exceed the total.",
              )}
            </p>
          )}
          {rows.data ? (
            <Breakdown
              dimension={dimension}
              rows={rows.data}
              onSelect={dimension === "ACTOR" ? setPerson : undefined}
            />
          ) : rows.isError ? (
            <p role="alert">
              {ui("Something went wrong fetching your usage. Try again in a moment.")}
            </p>
          ) : (
            <p role="status">{ui("Loading breakdown…")}</p>
          )}
        </CardContent>
      </Card>

      <PersonDialog person={person} range={range} onClose={() => setPerson(null)} />
    </SettingsLayout>
  );
}

function Summary({ value, compact }: { value?: AiCostSummary; compact?: boolean }) {
  const ui = useAppTranslation();
  const external =
    value && value.cost > 0 ? Math.round((value.externalCost / value.cost) * 100) : 0;
  return (
    <div
      className={
        compact
          ? "grid grid-cols-2 gap-px overflow-hidden rounded-2xl border border-border-subtle bg-border-subtle"
          : "grid grid-cols-2 gap-px overflow-hidden rounded-2xl border border-border-subtle bg-border-subtle lg:grid-cols-5"
      }
    >
      <Tile label={ui("Est. spend")} value={value ? money(value.cost) : "—"}>
        {value && value.cost > 0
          ? ui(appText("{{percent}}% External", { percent: external }))
          : null}
      </Tile>
      <Tile label={ui("Requests")} value={value ? count(value.calls) : "—"} />
      <Tile
        label={ui("Total tokens")}
        value={value ? count(value.inputTokens + value.outputTokens) : "—"}
      >
        {value
          ? ui(
              appText("{{input}} in · {{output}} out · {{cached}} cache reads", {
                input: count(value.inputTokens),
                output: count(value.outputTokens),
                cached: count(value.cacheReadTokens),
              }),
            )
          : null}
      </Tile>
      {!compact && (
        <Tile label={ui("Active users")} value={value ? count(value.activePeople) : "—"} />
      )}
      <Tile
        wide={!compact}
        label={ui("Prices unavailable")}
        value={value ? count(value.unknownCostCalls) : "—"}
        tone={value && value.unknownCostCalls > 0 ? "warning" : undefined}
      >
        {value && value.unknownCostCalls > 0 ? (
          <Link to="/admin/models" className="underline underline-offset-2">
            {ui("Model prices")}
          </Link>
        ) : (
          ui("All requests priced")
        )}
      </Tile>
    </div>
  );
}

function Tile({
  label,
  value,
  tone,
  wide,
  children,
}: {
  label: string;
  value: string;
  tone?: "warning";
  wide?: boolean;
  children?: ReactNode;
}) {
  return (
    <div className={cn("space-y-1 bg-surface-base px-4 py-3", wide && "col-span-2 lg:col-span-1")}>
      <p className="font-secondary-body text-content-muted">{label}</p>
      <p
        className={cn(
          "font-heading-h3 tabular-nums",
          tone === "warning" && "text-status-warning-content",
        )}
      >
        {value}
      </p>
      {children && <p className="font-secondary-body text-content-muted">{children}</p>}
    </div>
  );
}

function DailyChart({
  days,
  split,
  range,
}: {
  days: Parameters<typeof chartRows>[0];
  split: "BOUNDARY" | "MODEL";
  range: Period;
}) {
  const ui = useAppTranslation();
  if (!days.length) return <p role="status">{ui("No usage recorded for this period.")}</p>;
  const { rows, series } = chartRows(days, split, range);
  const names: Record<string, string> = {
    EXTERNAL: ui("External"),
    INTERNAL: ui("Internal"),
    NONE: ui("Outside the model catalog"),
    OTHER: ui("Other models"),
  };
  const config: ChartConfig = Object.fromEntries(
    series.map((key, index) => [
      `s${index}`,
      { label: names[key] ?? key, color: seriesColor(key, index) },
    ]),
  );
  const data = rows.map((row) =>
    Object.fromEntries([
      [
        "day",
        formatUiDate(`${row.day as string}T00:00:00Z`, {
          day: "2-digit",
          month: "2-digit",
          timeZone: "UTC",
        }),
      ],
      ...series.map((key, index) => [`s${index}`, row[key]]),
    ]),
  );
  return (
    <ChartContainer config={config} className="aspect-auto h-64 w-full">
      <BarChart accessibilityLayer data={data}>
        <CartesianGrid vertical={false} />
        <XAxis dataKey="day" tickLine={false} axisLine={false} tickMargin={8} minTickGap={16} />
        <YAxis
          tickLine={false}
          axisLine={false}
          width={64}
          tickFormatter={(value: number) => money(value)}
        />
        <ChartTooltip
          content={
            <ChartTooltipContent
              className="min-w-48"
              formatter={(value, name) => (
                <div className="flex w-full justify-between gap-4">
                  <span className="text-content-muted">{config[name as string]?.label}</span>
                  <span className="tabular-nums">{money(Number(value))}</span>
                </div>
              )}
            />
          }
        />
        <ChartLegend
          content={
            <ChartLegendContent className="flex-wrap gap-x-4 gap-y-1 [&>div]:whitespace-nowrap" />
          }
        />
        {series.map((_, index) => (
          <Bar
            key={index}
            dataKey={`s${index}`}
            stackId="cost"
            fill={`var(--color-s${index})`}
            radius={index === series.length - 1 ? [4, 4, 0, 0] : 0}
          />
        ))}
      </BarChart>
    </ChartContainer>
  );
}

function Breakdown({
  dimension,
  rows,
  onSelect,
  compact,
}: {
  dimension: Dimension;
  rows: AiCostRow[];
  onSelect?: (row: AiCostRow) => void;
  compact?: boolean;
}) {
  const ui = useAppTranslation();
  if (!rows.length) return <p role="status">{ui("No usage recorded for this period.")}</p>;
  const top = Math.max(...rows.map((row) => row.cost), 0);
  return (
    // Relative: the screen-reader-only header label must stay inside the scroll area on narrow screens.
    <div className="relative overflow-x-auto">
      <table className={compact ? "w-full text-left" : "w-full text-left sm:min-w-[40rem]"}>
        <thead className="font-secondary-body text-content-muted">
          <tr>
            <th className="py-2 pr-3 font-normal">{ui(columnLabels[dimension])}</th>
            <th className="px-3 py-2 text-right font-normal whitespace-nowrap">{ui("Requests")}</th>
            {!compact && (
              <th className="hidden px-3 py-2 text-right font-normal sm:table-cell">
                {ui("Total tokens")}
              </th>
            )}
            <th className="px-3 py-2 text-right font-normal whitespace-nowrap">{ui("Cost")}</th>
            {!compact && (
              <th className="hidden w-40 py-2 pl-3 font-normal sm:table-cell">
                <span className="sr-only">{ui("Share of cost")}</span>
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const label = rowLabel(dimension, row);
            const name = typeof label === "string" ? label : ui(label);
            const detail =
              dimension === "PROVIDER" && row.detail
                ? ui(row.detail === "INTERNAL" ? "Internal" : "External")
                : row.detail;
            return (
              <tr key={row.key} className="border-t border-border-subtle">
                <td className="py-2 pr-3">
                  {onSelect ? (
                    <button
                      type="button"
                      className="text-left hover:underline"
                      onClick={() => onSelect(row)}
                      aria-label={ui(appText("View AI costs of {{name}}", { name }))}
                    >
                      {name}
                    </button>
                  ) : (
                    name
                  )}
                  {detail && dimension !== "ACTOR" && (
                    <span className="block font-secondary-body text-content-muted">{detail}</span>
                  )}
                </td>
                <td className="px-3 py-2 text-right tabular-nums">{count(row.calls)}</td>
                {!compact && (
                  <td className="hidden px-3 py-2 text-right tabular-nums sm:table-cell">
                    {count(row.inputTokens + row.outputTokens)}
                  </td>
                )}
                <td className="px-3 py-2 text-right tabular-nums">
                  {money(row.cost)}
                  {row.unknownCostCalls > 0 && (
                    <span className="block font-secondary-body text-status-warning-content">
                      {ui(
                        appText("Prices unavailable ({{count}})", { count: row.unknownCostCalls }),
                      )}
                    </span>
                  )}
                </td>
                {!compact && (
                  <td className="hidden py-2 pl-3 sm:table-cell">
                    <div className="h-2 rounded-full bg-surface-sunken" aria-hidden="true">
                      <div
                        className="h-2 rounded-full bg-chart-1"
                        style={{ width: `${top > 0 ? Math.max(2, (row.cost / top) * 100) : 0}%` }}
                      />
                    </div>
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function PersonDialog({
  person,
  range,
  onClose,
}: {
  person: AiCostRow | null;
  range: Period;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const system = person?.key === "SYSTEM";
  const detail = useQuery({
    ...getAiCostDetailOptions({
      query: {
        from: range.from,
        to: range.to,
        ...(system ? { system: true } : { actorId: person?.key }),
      },
    }),
    enabled: person !== null,
  });
  const name = person ? rowLabel("ACTOR", person) : "";
  return (
    <Dialog open={person !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{typeof name === "string" ? name : ui(name)}</DialogTitle>
          <DialogDescription>
            {[person?.detail, ui(periodLabels[range.id])].filter(Boolean).join(" · ")}
          </DialogDescription>
        </DialogHeader>
        <div className="min-w-0 space-y-6">
          {detail.data ? (
            <>
              <Summary value={detail.data.summary} compact />
              <DailyChart days={detail.data.daily} split="MODEL" range={range} />
              <Section title={ui("By model")} dimension="MODEL" rows={detail.data.models} />
              <Section title={ui("By flow")} dimension="FLOW" rows={detail.data.flows} />
              <Section
                title={ui("By provider")}
                dimension="PROVIDER"
                rows={detail.data.providers}
              />
            </>
          ) : detail.isError ? (
            <p role="alert">
              {ui("Something went wrong fetching your usage. Try again in a moment.")}
            </p>
          ) : (
            <p role="status">{ui("Loading AI costs…")}</p>
          )}
          <Button prominence="secondary" onClick={onClose}>
            {ui("Done")}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function Section({
  title,
  dimension,
  rows,
}: {
  title: string;
  dimension: Dimension;
  rows: AiCostRow[];
}) {
  return (
    <section className="space-y-2">
      <h3 className="font-main-ui-action">{title}</h3>
      <Breakdown dimension={dimension} rows={rows} compact />
    </section>
  );
}
