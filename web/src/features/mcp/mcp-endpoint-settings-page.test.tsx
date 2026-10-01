import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { expect, it } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import {
  handleGetMcpEndpointConnection,
  handleListMcpClientGrants,
  handleRevokeMcpClientGrant,
} from "@/lib/hey-api/msw.gen";
import type { McpClientGrantResponse } from "@/lib/hey-api/types.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { server } from "@/test/msw";
import { McpEndpointSettingsPage } from "./mcp-endpoint-settings-page";

const CLAUDE = "https://claude.ai/oauth/mcp-oauth-client-metadata";
const claude: McpClientGrantResponse = {
  clientId: CLAUDE,
  client: "CLAUDE",
  name: "Claude",
  grantedAt: "2026-10-01T09:30:00Z",
};
const chatGpt: McpClientGrantResponse = {
  clientId: "memoryos-chatgpt",
  client: "CHATGPT",
  name: "ChatGPT",
  grantedAt: "2026-09-28T02:15:00Z",
};

function renderPage() {
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <ActionNotifications>
        <McpEndpointSettingsPage />
      </ActionNotifications>
    </QueryClientProvider>,
  );
}

it("gives the URL with each client's steps and revokes Claude by its URL client ID after confirmation", async () => {
  const revoked: Array<string | null> = [];
  server.use(
    handleGetMcpEndpointConnection({
      body: { available: true, url: "https://memoryos.example.vn/mcp" },
    }),
    handleListMcpClientGrants({ body: [claude] }),
    handleRevokeMcpClientGrant(({ request }) => {
      revoked.push(new URL(request.url).searchParams.get("clientId"));
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const user = userEvent.setup();
  renderPage();

  expect(await screen.findByLabelText("MCP URL")).toHaveValue("https://memoryos.example.vn/mcp");
  expect(screen.getByText("Paste the MCP URL above and choose Add.")).toBeVisible();
  await user.click(screen.getByRole("tab", { name: "ChatGPT" }));
  expect(
    await screen.findByText(
      "In ChatGPT, open Settings › Apps, choose MemoryOS and choose Connect.",
    ),
  ).toBeVisible();

  const apps = screen.getByRole("region", { name: "Authorized apps" });
  expect(within(apps).getByText("Claude")).toBeVisible();
  await user.click(within(apps).getByRole("button", { name: "Revoke" }));
  const dialog = await screen.findByRole("alertdialog");
  await user.click(within(dialog).getByRole("button", { name: "Revoke" }));
  await waitFor(() => expect(revoked).toEqual([CLAUDE]));
});

it("shows one status line while the endpoint is off and still lists what can be revoked", async () => {
  server.use(
    handleGetMcpEndpointConnection({ body: { available: false, url: null } }),
    handleListMcpClientGrants({ body: [chatGpt] }),
  );
  renderPage();

  expect(
    await screen.findByText("Your administrator has not turned on MemoryOS MCP."),
  ).toBeVisible();
  expect(screen.queryByLabelText("MCP URL")).toBeNull();
  expect(screen.queryByRole("tab")).toBeNull();
  const apps = screen.getByRole("region", { name: "Authorized apps" });
  expect(await within(apps).findByRole("button", { name: "Revoke" })).toBeVisible();
});
