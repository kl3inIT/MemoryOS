import { z } from "zod";
import type { AppCopy } from "@/i18n/app-text";
import type { ListMcpEndpointActivityData } from "@/lib/hey-api/types.gen";

type ActivityQuery = NonNullable<ListMcpEndpointActivityData["query"]>;

const mcpEndpointTabs = ["settings", "activity", "insights"] as const;
export type McpEndpointTab = (typeof mcpEndpointTabs)[number];

/** The activity log keeps 90 days, so its longest period is the whole log. */
export const activityPeriodDays = { "1d": 1, "7d": 7, "30d": 30, "90d": 90 } as const;
export type ActivityPeriod = keyof typeof activityPeriodDays;
export const activityPeriodLabels: Record<ActivityPeriod, AppCopy> = {
  "1d": "Last 24 hours",
  "7d": "Last 7 days",
  "30d": "Last 30 days",
  "90d": "Last 90 days",
};

export const activityClients = [
  "CLAUDE",
  "CHATGPT",
  "OTHER",
] as const satisfies readonly NonNullable<ActivityQuery["client"]>[];
export const activityTools = ["search", "search_with_filters", "fetch"] as const;
export const activityOutcomes = [
  "SUCCESS",
  "REFUSED",
  "FAILED",
  "RATE_LIMITED",
] as const satisfies readonly NonNullable<ActivityQuery["outcome"]>[];

export const DEFAULT_MCP_ENDPOINT_SEARCH = { tab: "settings", period: "7d", days: 7 } as const;

/** The page's tab, the activity filters and the insights period in its address; defaults are left out. */
export const mcpEndpointAdminSearchSchema = z.object({
  tab: z.enum(mcpEndpointTabs).default("settings").catch("settings"),
  period: z
    .enum(Object.keys(activityPeriodDays) as [ActivityPeriod, ...ActivityPeriod[]])
    .default("7d")
    .catch("7d"),
  client: z.enum(activityClients).optional().catch(undefined),
  tool: z.enum(activityTools).optional().catch(undefined),
  outcome: z.enum(activityOutcomes).optional().catch(undefined),
  person: z.string().trim().min(1).max(200).optional().catch(undefined),
  days: z
    .union([z.literal(7), z.literal(30)])
    .default(7)
    .catch(7),
});

export type McpEndpointAdminSearch = z.output<typeof mcpEndpointAdminSearchSchema>;

export function activityPeriodStart(period: ActivityPeriod, now = Date.now()) {
  return new Date(now - activityPeriodDays[period] * 86_400_000).toISOString();
}
