import { useQuery } from "@tanstack/react-query";
import { StatStrip, StatTile } from "@/components/composites/stat-strip";
import { DailyChart } from "./daily-chart";
import { UsageLimits } from "./usage-limits";
import { UsageReports } from "./usage-reports";
import { Link } from "@tanstack/react-router";
import {
  Activity,
  CircleDollarSign,
  Layers,
  ReceiptText,
  TriangleAlert,
  Users,
} from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import {
  getAiCostDetailOptions,
  getAiCostSummaryOptions,
  listAiCostBreakdownOptions,
  listAiCostDaysOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { AiCostRow, AiCostSummary } from "@/lib/hey-api/types.gen";
import {
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
  change,
  previousPeriod,
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
  const before = useQuery(getAiCostSummaryOptions({ query: previousPeriod(range) }));
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
        <Summary value={summary.data} previous={before.data} loading={summary.isPending} />
      )}

      <Card className="min-w-0">
        <CardContent className="min-w-0">
          <div className="flex min-w-0 flex-col gap-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2 className="font-heading-h3">{ui("Daily spend")}</h2>
              <Tabs
                value={split}
                onValueChange={(value) => setSplit(value as "BOUNDARY" | "MODEL")}
              >
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
          </div>
        </CardContent>
      </Card>

      <Card className="min-w-0">
        <CardContent className="min-w-0">
          <div className="flex min-w-0 flex-col gap-4">
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
          </div>
        </CardContent>
      </Card>

      <UsageLimits />

      <UsageReports />

      <PersonDialog person={person} range={range} onClose={() => setPerson(null)} />
    </SettingsLayout>
  );
}

function Summary({
  value,
  previous,
  loading = false,
  compact,
}: {
  value?: AiCostSummary;
  previous?: AiCostSummary;
  loading?: boolean;
  compact?: boolean;
}) {
  const ui = useAppTranslation();
  const external =
    value && value.cost > 0 ? Math.round((value.externalCost / value.cost) * 100) : 0;
  const spend = change(value?.cost, previous?.cost);
  return (
    <StatStrip columns={compact ? 2 : 5}>
      <StatTile
        icon={<CircleDollarSign />}
        iconClass="text-chart-1"
        loading={loading}
        label={ui("Est. spend")}
        value={value ? money(value.cost) : "—"}
        trend={
          spend
            ? {
                direction: spend.direction,
                label: ui(appText("{{change}} vs previous period", { change: spend.percent })),
              }
            : undefined
        }
      >
        {value && value.cost > 0
          ? ui(appText("{{percent}}% External", { percent: external }))
          : null}
      </StatTile>
      <StatTile
        icon={<Activity />}
        iconClass="text-chart-3"
        loading={loading}
        label={ui("Requests")}
        value={value ? count(value.calls) : "—"}
      />
      <StatTile
        icon={<Layers />}
        iconClass="text-chart-2"
        loading={loading}
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
      </StatTile>
      {!compact && (
        <StatTile
          icon={<Users />}
          iconClass="text-chart-5"
          loading={loading}
          label={ui("Active users")}
          value={value ? count(value.activePeople) : "—"}
        />
      )}
      <StatTile
        icon={<TriangleAlert />}
        iconClass={
          value && value.unknownCostCalls > 0 ? "text-status-warning-content" : "text-chart-4"
        }
        loading={loading}
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
      </StatTile>
    </StatStrip>
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
    <Table className={cn("text-left", !compact && "sm:min-w-160")}>
      <TableHeader>
        <TableRow>
          <TableHead className="pr-3 pl-0">{ui(columnLabels[dimension])}</TableHead>
          <TableHead className="px-3 text-right whitespace-nowrap">{ui("Requests")}</TableHead>
          {!compact && (
            <TableHead className="hidden px-3 text-right sm:table-cell">
              {ui("Total tokens")}
            </TableHead>
          )}
          <TableHead className="px-3 text-right whitespace-nowrap">{ui("Cost")}</TableHead>
          {!compact && (
            <TableHead className="hidden w-40 pr-0 pl-3 sm:table-cell">
              <span className="sr-only">{ui("Share of cost")}</span>
            </TableHead>
          )}
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => {
          const label = rowLabel(dimension, row);
          const name = typeof label === "string" ? label : ui(label);
          const detail =
            dimension === "PROVIDER" && row.detail
              ? ui(row.detail === "INTERNAL" ? "Internal" : "External")
              : row.detail;
          const share = top > 0 ? (row.cost / top) * 100 : 0;
          return (
            <TableRow key={row.key}>
              <TableCell className="pr-3 pl-0">
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
              </TableCell>
              <TableCell className="px-3 text-right">
                <span className="tabular-nums">{count(row.calls)}</span>
              </TableCell>
              {!compact && (
                <TableCell className="hidden px-3 text-right sm:table-cell">
                  <span className="tabular-nums">{count(row.inputTokens + row.outputTokens)}</span>
                </TableCell>
              )}
              <TableCell className="px-3 text-right">
                <span className="tabular-nums">{money(row.cost)}</span>
                {row.unknownCostCalls > 0 && (
                  <span className="block font-secondary-body text-status-warning-content">
                    {ui(appText("Prices unavailable ({{count}})", { count: row.unknownCostCalls }))}
                  </span>
                )}
              </TableCell>
              {!compact && (
                <TableCell className="hidden pr-0 pl-3 sm:table-cell">
                  <ShareBar percent={share} />
                </TableCell>
              )}
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
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
        <div className="flex min-w-0 flex-col gap-6">
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
          <Button prominence="secondary" className="self-start" onClick={onClose}>
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
    <section className="flex flex-col gap-2">
      <h3 className="font-main-ui-action">{title}</h3>
      <Breakdown dimension={dimension} rows={rows} compact />
    </section>
  );
}

/** A row's share of the largest row, drawn as a bar and read as a percentage. */
export function ShareBar({ percent }: { percent: number }) {
  const ui = useAppTranslation();
  return (
    <>
      <span className="sr-only">
        {ui(appText("{{percent}}%", { percent: Math.round(percent) }))}
      </span>
      <div className="h-2 rounded-full bg-surface-sunken" aria-hidden="true">
        <div
          className="h-2 rounded-full bg-chart-1"
          style={{ width: `${percent > 0 ? Math.max(2, percent) : 0}%` }}
        />
      </div>
    </>
  );
}
