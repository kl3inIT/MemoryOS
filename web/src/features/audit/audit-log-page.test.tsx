import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { AuditEvent } from "@/lib/hey-api/types.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { changeRows } from "./audit-actions";
import { AuditLogPage } from "./audit-log-page";

afterEach(() => vi.unstubAllGlobals());

const event = (overrides: Partial<AuditEvent>): AuditEvent => ({
  id: "7f000000-0000-4000-8000-000000000001",
  occurredAt: "2026-09-21T03:14:00Z",
  action: "llm_provider.update",
  eventClass: "API_ACTIVITY",
  outcome: "SUCCESS",
  actorId: "10000000-0000-4000-8000-000000000001",
  actorLabel: "Trần Thu Hà",
  actorEmail: "ha@tasco.vn",
  resourceType: "LLM_PROVIDER",
  resourceId: "20000000-0000-4000-8000-000000000001",
  resourceLabel: "OpenAI",
  details: {
    before: { dataBoundary: "INTERNAL", enabled: true },
    after: { dataBoundary: "EXTERNAL", enabled: true },
    credentialChange: "REPLACE",
  },
  traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
  endpoint: "PUT /api/chat/providers/20000000-0000-4000-8000-000000000001",
  sourceIp: "10.0.4.12",
  ...overrides,
});

function mount(pages: { items: AuditEvent[]; nextCursor: string | null }[] | "error") {
  const requested: URL[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      const url = new URL(request.url);
      requested.push(url);
      if (pages === "error") return new Response(null, { status: 403 });
      const page = url.searchParams.get("cursor") ? pages[1]! : pages[0]!;
      return Response.json(page);
    }),
  );
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <AuditLogPage />
    </QueryClientProvider>,
  );
  return requested;
}

describe("audit log", () => {
  it("lists events with a readable action beside its code, and opens one with what changed", async () => {
    mount([{ items: [event({})], nextCursor: null }]);
    const row = (await screen.findByText("OpenAI")).closest("tr")!;
    expect(within(row).queryByText("llm_provider.update")).toBeNull();
    expect(within(row).getByText("Trần Thu Hà")).toBeVisible();
    expect(within(row).getByRole("button")).toHaveTextContent("Updated a model provider OpenAI");
    expect(within(row).getByText("10.0.4.12")).toBeVisible();
    // A success carries no mark; only an event that did not succeed does.
    expect(within(row).queryByText("Succeeded")).toBeNull();
    await userEvent.click(within(row).getByRole("button", { name: /^Updated a model provider/ }));
    const panel = await screen.findByRole("dialog");
    expect(within(panel).getByText("10.0.4.12")).toBeVisible();
    // Only the field that changed is shown before and after.
    const changes = within(panel).getByRole("table");
    expect(within(changes).getByText("Data boundary")).toBeVisible();
    expect(within(changes).getByText("Internal")).toBeVisible();
    expect(within(changes).getByText("External")).toBeVisible();
    expect(within(changes).queryByText("Enabled")).toBeNull();
    expect(within(panel).getByText("Replaced")).toBeVisible();
  });

  it("pages to older events with the cursor it was given, and back", async () => {
    const requested = mount([
      { items: [event({})], nextCursor: "older" },
      {
        items: [event({ id: "7f000000-0000-4000-8000-000000000002", action: "auth.login" })],
        nextCursor: null,
      },
    ]);
    await screen.findByText("Updated a model provider");
    await userEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("Signed in")).toBeVisible();
    expect(screen.queryByText("Updated a model provider")).toBeNull();
    expect(requested.at(-1)!.searchParams.get("cursor")).toBe("older");
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Previous" }));
    expect(await screen.findByText("Updated a model provider")).toBeVisible();
  });

  it("exports exactly what the filters select", async () => {
    mount([{ items: [event({})], nextCursor: null }]);
    const link = await screen.findByRole("link", { name: "Export CSV" });
    const href = new URL(link.getAttribute("href")!, "http://memoryos.test");
    expect(href.pathname).toBe("/api/audit/export");
    expect(href.searchParams.get("from")).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(href.searchParams.has("size")).toBe(false);
  });

  it("offers a retry when the log cannot be read", async () => {
    mount("error");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The audit log could not be loaded.",
    );
  });

  it("shows an unknown action by its code rather than failing", async () => {
    mount([{ items: [event({ action: "future.action", details: {} })], nextCursor: null }]);
    expect(await screen.findByRole("button", { name: /^future\.action/ })).toBeVisible();
  });

  it("compares a changed setting field by field", () => {
    expect(changeRows({ a: 1, b: [1, 2] }, { a: 1, b: [1, 3] })).toEqual([
      { field: "b", before: "1, 2", after: "1, 3" },
    ]);
    expect(changeRows("Kế toán", "Tài chính")).toEqual([
      { field: "", before: "Kế toán", after: "Tài chính" },
    ]);
  });
});
