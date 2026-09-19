import { render, screen, within } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { seriesColor } from "./ai-costs";
import { MyUsagePage } from "./my-usage-page";

afterEach(() => vi.unstubAllGlobals());

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
const models = [
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

function mount(calls: number) {
  const requested: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      requested.push(new URL(request.url).pathname);
      if (request.url.includes("/api/ai-costs/mine"))
        return Response.json({
          summary: summary(calls),
          daily: [],
          models: calls ? [row("gpt-5.6-luna", 409, 3.12), row("gpt-image-1", 3, 0, 3)] : [],
          flows: [],
          providers: [],
        });
      return Response.json(models);
    }),
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
    const prices = (await screen.findByText("GPT-5.6 Luna")).closest("tr")!;
    expect(prices).toHaveTextContent("Default");
    expect(prices).toHaveTextContent("$0.125");
    expect(screen.getByText("gpt-oss-local").closest("tr")).toHaveTextContent("Prices unavailable");
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
