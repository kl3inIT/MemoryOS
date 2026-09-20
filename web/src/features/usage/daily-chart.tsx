import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from "recharts";
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { chartRows, money, seriesColor, type Period } from "./ai-costs";

/** Daily spend, stacked by data boundary or by model; shared by the tenant ledger and a member's own usage. */
export function DailyChart({
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
