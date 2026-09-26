import { render, screen, within } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import {
  handleGetMyAiCosts,
  handleGetMyAiUsageStanding,
  handleListAvailableChatModels,
} from "@/lib/hey-api/msw.gen";
import type { AiUsageStanding, AvailableModel } from "@/lib/hey-api/types.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { server } from "@/test/msw";
import { seriesColor } from "./ai-costs";
import { MyUsagePage } from "./my-usage-page";

const summary = (calls: number) => ({
  cost: calls ? 3.12 : 0,
  externalCost: 0,
  calls,
  unknownCostCalls: calls ? 3 : 0,
  inputTokens: calls ? 1_700_000 : 0,
  outputTokens: calls ? 250_000 : 0,
  cacheReadTokens: calls ? 310_000 : 0,
  imageCount: 0,
  audioSeconds: 0,
  activePeople: calls ? 1 : 0,
});
const row = (label: string, calls: number, cost: number, unknown = 0) => ({
  key: label,
  label,
  detail: "OpenAI",
  calls,
  unknownCostCalls: unknown,
  inputTokens: unknown === calls ? 0 : 1_000_000,
  outputTokens: unknown === calls ? 0 : 200_000,
  cost,
});
const models: AvailableModel[] = [
  {
    id: "luna",
    providerId: "p",
    providerName: "OpenAI",
    modelName: "gpt-5.6-luna",
    displayName: "GPT-5.6 Luna",
    capabilities: { streaming: true, toolCalling: true, vision: true, reasoning: true },
    contextWindow: 922000,
    maxOutputTokens: 128000,
    pricing: { inputPerMillion: 1.25, outputPerMillion: 10, cachedInputPerMillion: 0.125 },
    isDefault: true,
  },
  {
    id: "local",
    providerId: "q",
    providerName: "vLLM nội bộ",
    modelName: "gpt-oss-local",
    displayName: "gpt-oss-local",
    capabilities: { streaming: true, toolCalling: true, vision: false, reasoning: false },
    contextWindow: 32000,
    maxOutputTokens: null,
    pricing: null,
    isDefault: false,
  },
];

function mount(calls: number, standing: AiUsageStanding | null = null) {
  const requested: string[] = [];
  server.use(
    handleGetMyAiUsageStanding(() => HttpResponse.json(standing)),
    handleGetMyAiCosts(({ request }) => {
      requested.push(new URL(request.url).pathname);
      return HttpResponse.json({
        summary: summary(calls),
        daily: [],
        models: calls ? [row("gpt-5.6-luna", 409, 3.12), row("gpt-image-1", 3, 0, 3)] : [],
        flows: [],
        providers: [],
      });
    }),
    handleListAvailableChatModels({ body: models }),
  );
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <MyUsagePage />
    </QueryClientProvider>,
  );
  return requested;
}

describe("personal usage", () => {
  it("shows the member's own spend, tokens per model and the prices of the models they may use", async () => {
    const requested = mount(412);
    expect((await screen.findAllByText("$3.12"))[0]).toBeVisible();
    expect(requested).toContain("/api/ai-costs/mine");
    expect(screen.getByText("No budget set")).toBeVisible();
    expect(screen.getByText("1.7M in · 250K out · 310K cache reads")).toBeVisible();
    const byModel = screen.getByRole("table", { name: "By model" });
    expect(within(byModel).getByText("gpt-image-1").closest("tr")).toHaveTextContent(
      "Prices unavailable",
    );
    // Each model carries its own price where its usage is listed.
    expect(within(byModel).getByText("gpt-5.6-luna").closest("tr")).toHaveTextContent(
      "$1.25 in · $10.00 out",
    );
  });

  it("shows the budget that binds the member, and when it frees, once one is set", async () => {
    mount(1, {
      scope: "PERSON",
      tokenBudget: 1000,
      tokensUsed: 900,
      costUsed: 0,
      periodDays: 30,
      resetsAt: "2026-10-01T00:00:00Z",
    });
    const budget = await screen.findByRole("region", { name: "Spending limit" });
    expect(budget).toHaveTextContent("Each person");
    expect(budget).toHaveTextContent("900 / 1,000 token");
  });

  it("says nothing about a budget when the organization sets no limit", async () => {
    mount(1);
    await screen.findByText("gpt-5.6-luna");
    expect(screen.queryByRole("region", { name: "Spending limit" })).toBeNull();
  });

  it("explains an empty period instead of an empty table", async () => {
    mount(0);
    expect(await screen.findByText("No usage recorded yet")).toBeVisible();
    expect(screen.getByText("$0.00")).toBeVisible();
  });
});

describe("AI cost series colours", () => {
  it("keep a meaning's colour whatever its position and fold other and uncatalogued spend into neutral", () => {
    expect(seriesColor("EXTERNAL", 2)).toBe("var(--chart-1)");
    expect(seriesColor("INTERNAL", 0)).toBe("var(--chart-3)");
    expect(seriesColor("NONE", 1)).toBe("var(--chart-neutral)");
    expect(seriesColor("OTHER", 5)).toBe("var(--chart-neutral)");
    expect(seriesColor("gpt-5.1", 0)).toBe("var(--chart-1)");
    expect(seriesColor("gemma", 4)).toBe("var(--chart-5)");
  });
});
