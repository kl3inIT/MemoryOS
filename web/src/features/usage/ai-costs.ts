import { appText, type AppCopy } from "@/i18n/app-text";
import { uiLocale } from "@/i18n/format";
import type { AiCostDay, AiCostRow, ListAiCostBreakdownData } from "@/lib/hey-api/types.gen";

export type Dimension = ListAiCostBreakdownData["query"]["by"];
export type Flow = NonNullable<ListAiCostBreakdownData["query"]["flow"]>;
export type PeriodId = "7d" | "30d" | "month" | "lastMonth";
export type Period = { id: PeriodId; from: string; to: string };

const iso = (date: Date) => date.toISOString().slice(0, 10);

/** UTC day ranges, matching how usage is rolled up. */
export function period(id: PeriodId, now = new Date()): Period {
  const today = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const back = (days: number) => new Date(today.getTime() - days * 86_400_000);
  switch (id) {
    case "7d":
      return { id, from: iso(back(6)), to: iso(today) };
    case "30d":
      return { id, from: iso(back(29)), to: iso(today) };
    case "month":
      return {
        id,
        from: iso(new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), 1))),
        to: iso(today),
      };
    case "lastMonth": {
      const first = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth() - 1, 1));
      const last = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), 0));
      return { id, from: iso(first), to: iso(last) };
    }
  }
}

export const periodLabels: Record<PeriodId, AppCopy> = {
  "7d": "Last 7 days",
  "30d": "Last 30 days",
  month: "This month",
  lastMonth: "Last month",
};

export const flowLabels: Record<Flow, AppCopy> = {
  CHAT: "Chat",
  CHAT_NAMING: "Conversation naming",
  DEEP_RESEARCH: "Deep research",
  EMBEDDING_QUERY: "Search embeddings",
  EMBEDDING_INDEXING: "Indexing embeddings",
  IMAGE_GENERATION: "Image generation",
  IMAGE_EDIT: "Image editing",
  SPEECH_TO_TEXT: "Speech to text",
  TEXT_TO_SPEECH: "Read aloud",
};

export const dimensionLabels: Record<Dimension, AppCopy> = {
  ACTOR: "By user",
  GROUP: "By group",
  MODEL: "By model",
  FLOW: "By flow",
  PROVIDER: "By provider",
};

/** USD with enough precision for the small amounts single calls cost. */
export function money(value: number) {
  return new Intl.NumberFormat(uiLocale(), {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: value !== 0 && Math.abs(value) < 1 ? 4 : 2,
  }).format(value);
}

export function count(value: number) {
  return new Intl.NumberFormat(uiLocale(), {
    notation: value >= 100_000 ? "compact" : "standard",
  }).format(value);
}

/** A row's display name: flows are labeled, the system row is named, everything else is data. */
export function rowLabel(dimension: Dimension, row: AiCostRow): AppCopy | string {
  // Copy objects are translated by the caller; plain strings are data such as names and models.
  if (dimension === "FLOW")
    return row.key in flowLabels ? appText(flowLabels[row.key as Flow]) : row.label;
  if (dimension === "ACTOR" && row.key === "SYSTEM") return appText("System work");
  return row.label;
}

export const boundarySeries = ["EXTERNAL", "INTERNAL", "NONE"] as const;

/** One chart row per UTC day with a numeric column per series; models beyond the top five join "other". */
export function chartRows(days: AiCostDay[], split: "BOUNDARY" | "MODEL", range: Period) {
  const totals = new Map<string, number>();
  for (const day of days) totals.set(day.series, (totals.get(day.series) ?? 0) + day.cost);
  const top =
    split === "BOUNDARY"
      ? [...boundarySeries]
      : [...totals.entries()]
          .sort((a, b) => b[1] - a[1])
          .slice(0, 5)
          .map(([series]) => series);
  const series = split === "MODEL" && totals.size > top.length ? [...top, "OTHER"] : top;
  const rows: Record<string, number | string>[] = [];
  for (
    let at = new Date(`${range.from}T00:00:00Z`);
    iso(at) <= range.to;
    at = new Date(at.getTime() + 86_400_000)
  ) {
    const row: Record<string, number | string> = { day: iso(at) };
    for (const key of series) row[key] = 0;
    rows.push(row);
  }
  const byDay = new Map(rows.map((row) => [row.day as string, row]));
  for (const day of days) {
    const row = byDay.get(day.day);
    if (!row) continue;
    const key = top.includes(day.series) ? day.series : "OTHER";
    row[key] = (row[key] as number) + day.cost;
  }
  return { rows, series };
}
