import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  Pie,
  PieChart,
  XAxis,
  YAxis,
} from "recharts";
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { getChatFileArtifactChart } from "@/lib/hey-api/sdk.gen";
import { barRows, parseChart, pointRows, type SimpleChart } from "./chat-chart";
import { fileArtifactUrl, type GeneratedFile } from "./chat-code";

// The design tokens' --chart-* scale is neutral, which cannot tell series apart; these hues stay distinct and
// readable on light and dark surfaces.
const COLORS = [
  "#3b82f6",
  "#f59e0b",
  "#10b981",
  "#ef4444",
  "#8b5cf6",
  "#06b6d4",
  "#ec4899",
  "#84cc16",
];

function color(index: number): string {
  return COLORS[index % COLORS.length]!;
}

/**
 * A figure run_python left open, drawn from its captured chart data (E2B chart model) with the PNG as the static
 * view and the fallback for anything this app cannot draw.
 */
export function ChatChartCard({ file }: { file: GeneratedFile }) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [view, setView] = useState<"interactive" | "static">("interactive");
  const chart = useQuery({
    queryKey: ["chat-generated-chart", actorId, authorizationVersion, file.id],
    staleTime: Infinity,
    retry: false,
    queryFn: async ({ signal }) =>
      parseChart(
        (
          await getChatFileArtifactChart({
            path: { artifactId: file.id },
            signal,
            throwOnError: true,
          })
        ).data,
      ) ?? null,
  });
  const drawable = chart.data ?? undefined;
  const title =
    drawable?.title ?? drawable?.charts[0]?.title ?? file.filename.replace(/\.png$/i, "");
  const interactive = drawable && view === "interactive";
  return (
    <figure
      data-slot="generated-chart"
      className="mt-3 w-full max-w-2xl rounded-xl border border-border-subtle bg-surface-base p-4"
    >
      <div className="mb-3 flex items-center gap-3">
        <figcaption className="min-w-0 flex-1 truncate text-sm font-medium" title={title}>
          {title}
        </figcaption>
        {drawable && (
          <Tabs value={view} onValueChange={(next) => setView(next as typeof view)}>
            <TabsList aria-label={ui("Kiểu hiển thị biểu đồ")}>
              <TabsTrigger value="interactive">{ui("Tương tác")}</TabsTrigger>
              <TabsTrigger value="static">{ui("Ảnh tĩnh")}</TabsTrigger>
            </TabsList>
          </Tabs>
        )}
      </div>
      {interactive ? (
        <div className="grid gap-4">
          {drawable.charts.map((item, index) => (
            <ChartView key={index} chart={item} showTitle={drawable.charts.length > 1} />
          ))}
        </div>
      ) : (
        <img
          src={fileArtifactUrl(file.id)}
          alt={title}
          className="max-h-96 w-full rounded-lg bg-white object-contain"
        />
      )}
    </figure>
  );
}

function ChartView({ chart, showTitle }: { chart: SimpleChart; showTitle: boolean }) {
  return (
    <div>
      {showTitle && chart.title && (
        <p className="mb-1 text-xs text-content-secondary">{chart.title}</p>
      )}
      <Drawn chart={chart} />
    </div>
  );
}

function Drawn({ chart }: { chart: SimpleChart }) {
  if (chart.type === "pie") {
    const config: ChartConfig = Object.fromEntries(
      chart.elements.map((slice, index) => [
        `p${index}`,
        { label: slice.label, color: color(index) },
      ]),
    );
    const data = chart.elements.map((slice, index) => ({
      key: `p${index}`,
      label: slice.label,
      value: slice.angle,
    }));
    return (
      <ChartContainer config={config} className="mx-auto aspect-square max-h-72">
        <PieChart accessibilityLayer>
          <ChartTooltip
            content={<ChartTooltipContent nameKey="key" hideLabel className="min-w-44" />}
          />
          <Pie
            data={data}
            dataKey="value"
            nameKey="key"
            stroke="var(--surface-base)"
            strokeWidth={2}
          >
            {data.map((slice, index) => (
              <Cell key={slice.key} fill={color(index)} />
            ))}
          </Pie>
          <ChartLegend content={<ChartLegendContent nameKey="key" />} className="flex-wrap" />
        </PieChart>
      </ChartContainer>
    );
  }
  const bar = chart.type === "bar" ? barRows(chart.elements) : undefined;
  const labels = bar
    ? bar.groups
    : chart.type === "bar"
      ? []
      : chart.elements.map((series) => series.label);
  const rows = bar ? bar.rows : chart.type === "bar" ? [] : pointRows(chart.elements);
  const config: ChartConfig = Object.fromEntries(
    labels.map((label, index) => [
      `s${index}`,
      { label: label || chart.y_label || `#${index + 1}`, color: color(index) },
    ]),
  );
  const legend = labels.length > 1 && <ChartLegend content={<ChartLegendContent />} />;
  const axes = (
    <>
      <CartesianGrid vertical={false} />
      <XAxis dataKey="x" tickLine={false} axisLine={false} tickMargin={8} minTickGap={16} />
      <YAxis tickLine={false} axisLine={false} width={56} />
      <ChartTooltip content={<ChartTooltipContent className="min-w-44" />} />
      {legend}
    </>
  );
  return (
    <div>
      <ChartContainer config={config} className="aspect-video max-h-80 w-full">
        {chart.type === "bar" ? (
          <BarChart accessibilityLayer data={rows}>
            {axes}
            {labels.map((_, index) => (
              <Bar key={index} dataKey={`s${index}`} fill={`var(--color-s${index})`} radius={4} />
            ))}
          </BarChart>
        ) : (
          <LineChart accessibilityLayer data={rows}>
            {axes}
            {labels.map((_, index) => (
              <Line
                key={index}
                type="monotone"
                dataKey={`s${index}`}
                stroke={`var(--color-s${index})`}
                // Scatter series are points without a line.
                strokeWidth={chart.type === "scatter" ? 0 : 2}
                dot={chart.type === "scatter" || rows.length <= 40}
              />
            ))}
          </LineChart>
        )}
      </ChartContainer>
      {(chart.x_label || chart.y_label) && (
        <p className="mt-1 text-center text-xs text-content-muted">
          {[chart.x_label, chart.y_label].filter(Boolean).join(" · ")}
        </p>
      )}
    </div>
  );
}
