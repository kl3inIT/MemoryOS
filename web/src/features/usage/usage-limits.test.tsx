import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { UsageLimits } from "./usage-limits";

afterEach(() => vi.unstubAllGlobals());

const tenantLimit = {
  id: "20000000-0000-4000-8000-000000000001",
  scope: "TENANT",
  groupId: null,
  groupName: null,
  tokenBudget: 90_000,
  costBudgetUsd: null,
  periodDays: 30,
  enabled: true,
  tokensUsed: 45_000,
  costUsed: 0,
};

const groupLimit = {
  ...tenantLimit,
  id: "20000000-0000-4000-8000-000000000002",
  scope: "GROUP",
  groupId: "30000000-0000-4000-8000-000000000001",
  groupName: "Pháp chế",
  tokenBudget: null,
  costBudgetUsd: 40,
  costUsed: 12.5,
  tokensUsed: 0,
};

function mount(limits: unknown[]) {
  const sent: { method: string; path: string; body: unknown }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const url = new URL(request.url);
      const body =
        request.method === "GET"
          ? null
          : await request
              .clone()
              .json()
              .catch(() => null);
      sent.push({ method: request.method, path: url.pathname, body });
      if (url.pathname.startsWith("/api/groups")) return Response.json({ items: [], total: 0 });
      if (request.method === "GET") return Response.json(limits);
      return Response.json(limits[0] ?? null);
    }),
  );
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <UsageLimits />
    </QueryClientProvider>,
  );
  return sent;
}

describe("spending limits", () => {
  it("reads a limit beside what has been spent against it", async () => {
    mount([tenantLimit, groupLimit]);
    const tenant = (await screen.findByText("The whole organization")).closest("tr")!;
    expect(within(tenant).getByText("45,000 / 90,000 token")).toBeVisible();
    expect(within(tenant).getByText("30 days")).toBeVisible();
    const group = screen.getByText("Pháp chế").closest("tr")!;
    expect(within(group).getByText("$12.50 of $40.00")).toBeVisible();
  });

  it("says nothing caps spending until a limit is added", async () => {
    mount([]);
    expect(await screen.findByText("No limit is set.")).toBeVisible();
  });

  it("switching a limit off keeps its budget", async () => {
    const sent = mount([tenantLimit]);
    await screen.findByText("The whole organization");
    await userEvent.click(screen.getByRole("switch", { name: "Enforced" }));
    const off = sent.find((call) => call.method === "PUT")!;
    expect(off.path).toBe(`/api/ai-costs/limits/${tenantLimit.id}`);
    expect(off.body).toMatchObject({ enabled: false, tokenBudget: 90_000, periodDays: 30 });
  });

  it("refuses to save a limit with no budget at all", async () => {
    mount([]);
    await screen.findByText("No limit is set.");
    await userEvent.click(screen.getByRole("button", { name: "Add a limit" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText("Token budget"), "5000");
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeEnabled();
  });
});
