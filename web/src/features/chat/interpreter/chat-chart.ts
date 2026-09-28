import { z } from "zod";

/** Bounds applied before rendering; larger charts show their PNG. */
const MAX_SERIES = 20;
export const MAX_POINTS = 1000;

const text = z.string().max(500).nullish();
const value = z.union([z.number(), z.string().max(200)]);

const axes = {
  title: text,
  x_label: text,
  y_label: text,
  x_unit: text,
  y_unit: text,
};

const pointSeries = z.object({
  label: z.string().max(200),
  points: z.array(z.tuple([value, value])).max(MAX_POINTS),
});

const lineChart = z.object({
  type: z.literal("line"),
  elements: z.array(pointSeries).max(MAX_SERIES),
  ...axes,
});
const scatterChart = z.object({
  type: z.literal("scatter"),
  elements: z.array(pointSeries).max(MAX_SERIES),
  ...axes,
});
const barChart = z.object({
  type: z.literal("bar"),
  elements: z
    .array(z.object({ label: z.string().max(200), group: z.string().max(200), value: z.number() }))
    .max(MAX_POINTS),
  ...axes,
});
const pieChart = z.object({
  type: z.literal("pie"),
  title: text,
  elements: z
    .array(
      z.object({ label: z.string().max(200), angle: z.number(), radius: z.number().optional() }),
    )
    .max(MAX_SERIES * 5),
});

const simpleChart = z.discriminatedUnion("type", [lineChart, scatterChart, barChart, pieChart]);
export type SimpleChart = z.infer<typeof simpleChart>;

const superChart = z.object({
  type: z.literal("superchart"),
  title: text,
  elements: z.array(z.unknown()).max(12),
});

/** The E2B chart model this app can draw; anything else (box plots, unknown, invalid data) shows the PNG. */
export type DrawableChart = { title?: string | null; charts: SimpleChart[] };

export function parseChart(data: unknown): DrawableChart | undefined {
  const simple = simpleChart.safeParse(data);
  if (simple.success) return nonEmpty({ title: simple.data.title, charts: [simple.data] });
  const combined = superChart.safeParse(data);
  if (!combined.success) return undefined;
  const charts: SimpleChart[] = [];
  for (const element of combined.data.elements) {
    const parsed = simpleChart.safeParse(element);
    // One subplot the app cannot draw would misrepresent the figure; the PNG shows all of it.
    if (!parsed.success) return undefined;
    charts.push(parsed.data);
  }
  return nonEmpty({ title: combined.data.title, charts });
}

function nonEmpty(chart: DrawableChart): DrawableChart | undefined {
  return chart.charts.length && chart.charts.every((item) => item.elements.length)
    ? chart
    : undefined;
}

export type SeriesRow = Record<string, string | number>;

/** Line and scatter series merged on their x value, in first-seen order; keys are `s0`, `s1`… */
export function pointRows(elements: z.infer<typeof pointSeries>[]): SeriesRow[] {
  const rows = new Map<string, SeriesRow>();
  elements.forEach((series, index) => {
    for (const [x, y] of series.points) {
      const key = String(x);
      const row = rows.get(key) ?? { x };
      row[`s${index}`] = typeof y === "number" ? y : Number(y);
      rows.set(key, row);
    }
  });
  return [...rows.values()];
}

/** Bars grouped by label with one key per group (the E2B `group` is the series). */
export function barRows(elements: Extract<SimpleChart, { type: "bar" }>["elements"]): {
  rows: SeriesRow[];
  groups: string[];
} {
  const groups: string[] = [];
  const rows = new Map<string, SeriesRow>();
  for (const bar of elements) {
    let index = groups.indexOf(bar.group);
    if (index < 0) index = groups.push(bar.group) - 1;
    const row = rows.get(bar.label) ?? { x: bar.label };
    row[`s${index}`] = bar.value;
    rows.set(bar.label, row);
  }
  return { rows: [...rows.values()], groups };
}
