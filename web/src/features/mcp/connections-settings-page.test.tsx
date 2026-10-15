import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { expect, it } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { handleDisconnectMcpConnection, handleListMcpConnections } from "@/lib/hey-api/msw.gen";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { server } from "@/test/msw";
import { ConnectionsSettingsPage } from "./connections-settings-page";

const connection = (
  id: string,
  name: string,
  connectionState: McpConnection["connectionState"],
): McpConnection => ({
  id,
  slug: id,
  name,
  description: null,
  url: `https://${id}.example/mcp`,
  authType: "OAUTH",
  authPerformer: "PER_USER",
  status: "CONNECTED",
  connectionState,
  oauthClients: [{ id: `${id}-client`, label: "Tasco" }],
  enabledToolCount: 4,
  credentialUpdatedAt: null,
  revision: 1,
});

it("disconnects the member's own account after confirmation and offers Connect where it is missing", async () => {
  const disconnected: string[] = [];
  server.use(
    handleListMcpConnections({
      body: [
        connection("drive", "Google Drive", "CONNECTED"),
        connection("jira", "Jira Tasco", "NOT_CONNECTED"),
      ],
    }),
    handleDisconnectMcpConnection(({ params }) => {
      disconnected.push(params.serverId);
      return new HttpResponse(null, { status: 204 });
    }),
  );
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <ActionNotifications>
        <ConnectionsSettingsPage />
      </ActionNotifications>
    </QueryClientProvider>,
  );
  await screen.findByText("Jira Tasco");
  expect(screen.getByRole("button", { name: "Connect" })).toBeVisible();
  await userEvent.click(screen.getByRole("button", { name: "Actions for Google Drive" }));
  await userEvent.click(await screen.findByRole("menuitem", { name: "Disconnect" }));
  await userEvent.click(await screen.findByRole("button", { name: "Disconnect" }));
  await waitFor(() => expect(disconnected).toEqual(["drive"]));
});

it("offers Reconnect for a rejected credential and one menu for several organizations", async () => {
  const rejected = connection("drive", "Google Drive", "REAUTH_REQUIRED");
  const several = {
    ...connection("jira", "Jira Tasco", "NOT_CONNECTED"),
    oauthClients: [
      { id: "north", label: "Tasco North" },
      { id: "south", label: "Tasco South" },
    ],
  };
  server.use(handleListMcpConnections({ body: [rejected, several] }));
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <ConnectionsSettingsPage />
    </QueryClientProvider>,
  );
  expect(await screen.findByRole("button", { name: "Reconnect" })).toBeVisible();
  expect(screen.getByRole("button", { name: "Connect" })).toBeVisible();
  expect(screen.queryByRole("button", { name: "Tasco North" })).not.toBeInTheDocument();
});
