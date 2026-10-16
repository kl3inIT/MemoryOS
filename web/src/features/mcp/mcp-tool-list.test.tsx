import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { i18n } from "@/i18n";
import {
  handleListMcpServerTools,
  handleListMcpServers,
  handleResetMcpServerToolPolicies,
  handleSetMcpServerToolGroupPolicy,
} from "@/lib/hey-api/msw.gen";
import type { McpToolView } from "@/lib/hey-api/types.gen";
import { server as http } from "@/test/msw";
import { McpToolList } from "./mcp-tool-list";

const SERVER = "44444444-4444-4444-8444-444444444444";

const tool = (overrides: Partial<McpToolView>): McpToolView => ({
  id: "t",
  name: "list_issues",
  modelName: "mcp_linear_list_issues",
  title: null,
  description: "List issues.",
  readOnlyHint: true,
  destructiveHint: false,
  risk: "READ",
  policy: "ALWAYS",
  policyCustomized: false,
  exposable: true,
  snapshotAt: "2026-10-15T03:00:00Z",
  revision: 1,
  ...overrides,
});

function show(tools: McpToolView[]) {
  http.use(handleListMcpServerTools({ body: tools }), handleListMcpServers({ body: [] }));
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queries}>
      <McpToolList serverId={SERVER} />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

it("sets a whole group at once and never offers Always allow for a destructive tool", async () => {
  const changes: unknown[] = [];
  http.use(
    handleSetMcpServerToolGroupPolicy(async ({ request }) => {
      changes.push(await request.json());
      return HttpResponse.json([]);
    }),
  );
  show([
    tool({ id: "t1" }),
    tool({ id: "t2", name: "save_comment", risk: "WRITE", readOnlyHint: false, policy: "ASK" }),
    tool({ id: "t3", name: "save_issue", risk: "DESTRUCTIVE", readOnlyHint: false, policy: "ASK" }),
  ]);

  await userEvent.click(await screen.findByRole("combobox", { name: "save_issue" }));
  const options = within(screen.getByRole("listbox"));
  expect(options.queryByRole("option", { name: "Luôn cho phép" })).not.toBeInTheDocument();
  await userEvent.keyboard("{Escape}");

  await userEvent.click(screen.getByRole("combobox", { name: "Mức cho nhóm Ghi" }));
  await userEvent.click(screen.getByRole("option", { name: "Luôn cho phép" }));
  await waitFor(() => expect(changes).toEqual([{ risk: "WRITE", policy: "ALWAYS" }]));
  expect(screen.queryByRole("button", { name: "Về mặc định" })).not.toBeInTheDocument();
});

it("marks a chosen level and returns every tool to its default", async () => {
  let resets = 0;
  http.use(
    handleResetMcpServerToolPolicies(() => {
      resets += 1;
      return HttpResponse.json([]);
    }),
  );
  show([tool({ id: "t1", policy: "ASK", policyCustomized: true })]);

  expect(await screen.findByText("Đã chỉnh")).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Về mặc định" }));
  await waitFor(() => expect(resets).toBe(1));
});
