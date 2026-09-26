import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import {
  handleListAiUsageLimits,
  handleListGroups,
  handleUpdateAiUsageLimit,
} from "@/lib/hey-api/msw.gen";
import type { AiUsageLimit } from "@/lib/hey-api/types.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { server } from "@/test/msw";
import { UsageLimits } from "./usage-limits";

const tenantLimit: AiUsageLimit = {
  id: "20000000-0000-4000-8000-000000000001",
  scope: "TENANT",
  tokenBudget: 90_000,
  periodDays: 30,
  enabled: true,
  tokensUsed: 45_000,
  costUsed: 0,
};

const groupLimit: AiUsageLimit = {
  ...tenantLimit,
  id: "20000000-0000-4000-8000-000000000002",
  scope: "GROUP",
  groupId: "30000000-0000-4000-8000-000000000001",
  groupName: "Pháp chế",
  tokenBudget: undefined,
  costBudgetUsd: 40,
  costUsed: 12.5,
  tokensUsed: 0,
};

function mount(limits: AiUsageLimit[]) {
  server.use(
    handleListAiUsageLimits({ body: limits }),
    handleListGroups({ body: { items: [], page: 0, size: 100, totalItems: 0, totalPages: 0 } }),
  );
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <UsageLimits />
    </QueryClientProvider>,
  );
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
    const sent: { limitId: string; body: unknown }[] = [];
    server.use(
      handleUpdateAiUsageLimit(async ({ params, request }) => {
        sent.push({ limitId: params.limitId, body: await request.json() });
        return HttpResponse.json({ ...tenantLimit, enabled: false });
      }),
    );
    mount([tenantLimit]);
    await screen.findByText("The whole organization");
    await userEvent.click(screen.getByRole("switch", { name: "Enforced" }));
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]?.limitId).toBe(tenantLimit.id);
    expect(sent[0]?.body).toMatchObject({ enabled: false, tokenBudget: 90_000, periodDays: 30 });
  });

  it("says so when a change to a limit is refused", async () => {
    server.use(
      handleUpdateAiUsageLimit(() =>
        HttpResponse.json({ status: 409, title: "Conflict" }, { status: 409 }),
      ),
    );
    mount([tenantLimit]);
    await screen.findByText("The whole organization");
    await userEvent.click(screen.getByRole("switch", { name: "Enforced" }));
    expect(await screen.findByRole("alert")).toBeVisible();
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
