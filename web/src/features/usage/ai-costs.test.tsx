import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import type * as RouterModule from "@tanstack/react-router";
import type { ReactNode } from "react";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import {
  handleGetAiCostDetail,
  handleGetAiCostSummary,
  handleListAiCostBreakdown,
  handleListAiCostDays,
  handleListAiUsageLimits,
  handleListUsageReports,
} from "@/lib/hey-api/msw.gen";
import type { AiCostDay, AiCostRow, AiCostSummary } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { chartRows, period } from "./ai-costs";
import { AiCostsPage } from "./ai-costs-page";

vi.mock("@tanstack/react-router", async (importOriginal) => ({
  ...(await importOriginal<typeof RouterModule>()),
  Link: ({ children }: { children: ReactNode }) => <a href="/admin/models">{children}</a>,
}));

describe("AI cost periods and daily series", () => {
  it("counts whole UTC days for each period", () => {
    const now = new Date("2026-09-19T23:30:00Z");
    expect(period("7d", now)).toMatchObject({ from: "2026-09-13", to: "2026-09-19" });
    expect(period("month", now)).toMatchObject({ from: "2026-09-01", to: "2026-09-19" });
    expect(period("lastMonth", now)).toMatchObject({ from: "2026-08-01", to: "2026-08-31" });
  });

  it("fills empty days and folds models beyond the top five into other", () => {
    const day = (date: string, series: string, cost: number): AiCostDay => ({
      day: date,
      series,
      cost,
      calls: 1,
      inputTokens: 1,
      outputTokens: 1,
      cacheReadTokens: 0,
    });
    const range = { id: "7d" as const, from: "2026-09-17", to: "2026-09-19" };
    const models = ["a", "b", "c", "d", "e", "f"].map((name, index) =>
      day("2026-09-18", name, 6 - index),
    );
    const { rows, series } = chartRows(models, "MODEL", range);
    expect(rows).toHaveLength(3);
    expect(series).toEqual(["a", "b", "c", "d", "e", "OTHER"]);
    expect(rows[1].OTHER).toBe(1);
    expect(rows[0].a).toBe(0);
    const boundary = chartRows([day("2026-09-19", "EXTERNAL", 2)], "BOUNDARY", range);
    expect(boundary.series).toEqual(["EXTERNAL", "INTERNAL", "NONE"]);
    expect(boundary.rows[2].EXTERNAL).toBe(2);
  });
});

describe("AI costs page", () => {
  it("shows known and unpriced spend, ranks people and opens a person's detail", async () => {
    const breakdowns: (string | null)[] = [];
    const summary: AiCostSummary = {
      cost: 41.2,
      externalCost: 30.9,
      calls: 3812,
      unknownCostCalls: 214,
      inputTokens: 1_500_000,
      outputTokens: 340_000,
      cacheReadTokens: 200_000,
      imageCount: 4,
      audioSeconds: 60,
      activePeople: 27,
    };
    const row: AiCostRow = {
      key: "00000000-0000-0000-0000-000000000011",
      label: "Trần Thu Hà",
      detail: "ha@tasco.vn",
      calls: 412,
      unknownCostCalls: 3,
      inputTokens: 1_800_000,
      outputTokens: 300_000,
      cost: 6.8,
    };
    server.use(
      handleGetAiCostSummary({ body: summary }),
      handleListAiCostDays({ body: [] }),
      handleListAiCostBreakdown(({ request }) => {
        breakdowns.push(new URL(request.url).searchParams.get("by"));
        return HttpResponse.json([row]);
      }),
      handleListUsageReports({ body: [] }),
      handleListAiUsageLimits({ body: [] }),
      handleGetAiCostDetail({
        body: {
          summary: { ...summary, cost: 6.8, calls: 412 },
          daily: [],
          models: [{ ...row, key: "gpt-5.1|OpenAI", label: "gpt-5.1", detail: "OpenAI" }],
          flows: [{ ...row, key: "CHAT_NAMING", label: "CHAT_NAMING", detail: null }],
          providers: [],
        },
      }),
    );
    const client = createMemoryOsQueryClient();
    render(
      <QueryClientProvider client={client}>
        <AiCostsPage />
      </QueryClientProvider>,
    );
    expect(await screen.findByText("$41.20")).toBeInTheDocument();
    expect(screen.getByText("75% External")).toBeInTheDocument();
    expect(screen.getByText("214")).toBeInTheDocument();
    expect(screen.getByText("Model prices")).toBeInTheDocument();
    expect(screen.getByText("No usage recorded for this period.")).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "View AI costs of Trần Thu Hà" }));
    const sheet = await screen.findByRole("dialog");
    expect(await within(sheet).findByText("Conversation naming")).toBeInTheDocument();
    expect(within(sheet).getByText("gpt-5.1")).toBeInTheDocument();
    await waitFor(() => expect(breakdowns).toContain("ACTOR"));
    client.clear();
  });
});
