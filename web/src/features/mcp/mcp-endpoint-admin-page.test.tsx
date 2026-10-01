import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { expect, it } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import {
  handleGetMcpEndpointSettings,
  handleUpdateMcpEndpointSettings,
} from "@/lib/hey-api/msw.gen";
import type { McpEndpointSettingsResponse } from "@/lib/hey-api/types.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { server } from "@/test/msw";
import { McpEndpointAdminPage } from "./mcp-endpoint-admin-page";

const settings: McpEndpointSettingsResponse = {
  configured: true,
  enabled: false,
  revision: 4,
  url: "https://memoryos.example.vn/mcp",
  chatGpt: { clientId: "memoryos-chatgpt", clientSecret: "c0ffee-secret" },
};

function renderPage() {
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <ActionNotifications>
        <McpEndpointAdminPage />
      </ActionNotifications>
    </QueryClientProvider>,
  );
}

it("turns the endpoint on at the revision it read and keeps ChatGPT's secret masked until revealed", async () => {
  const saved: unknown[] = [];
  server.use(
    handleGetMcpEndpointSettings({ body: settings }),
    handleUpdateMcpEndpointSettings(async ({ request }) => {
      saved.push(await request.json());
      return HttpResponse.json({ ...settings, enabled: true, revision: 5 });
    }),
  );
  const user = userEvent.setup();
  renderPage();

  expect(await screen.findByLabelText("MCP URL")).toHaveValue("https://memoryos.example.vn/mcp");
  expect(screen.getByLabelText("Client ID")).toHaveValue("memoryos-chatgpt");
  const secret = screen.getByLabelText("Client secret");
  expect(secret).toHaveAttribute("type", "password");
  await user.click(screen.getByRole("button", { name: "Show Client secret" }));
  expect(secret).toHaveAttribute("type", "text");

  await user.click(screen.getByRole("switch", { name: "Turn on MemoryOS MCP" }));
  await waitFor(() => expect(saved).toEqual([{ enabled: true, revision: 4 }]));
});

it("locks the page on a deployment without an endpoint URL", async () => {
  server.use(
    handleGetMcpEndpointSettings({
      body: { configured: false, enabled: false, revision: 0, url: null, chatGpt: null },
    }),
  );
  renderPage();

  expect(await screen.findByText("This server has no MCP URL configured.")).toBeVisible();
  expect(screen.queryByRole("switch")).toBeNull();
});
