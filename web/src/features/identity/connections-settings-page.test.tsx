import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, it, vi } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { ConnectionsSettingsPage } from "./connections-settings-page";

afterEach(() => vi.unstubAllGlobals());

const server = (id: string, name: string, connectionState: string, authType = "OAUTH") => ({
  id,
  slug: id,
  name,
  description: null,
  url: `https://${id}.example/mcp`,
  authType,
  authPerformer: "PER_USER",
  status: "CONNECTED",
  connectionState,
  oauthClients: [{ id: `${id}-client`, label: "Tasco" }],
  enabledToolCount: 4,
  credentialUpdatedAt: null,
  revision: 1,
});

it("disconnects the member's own account after confirmation and offers Connect where it is missing", async () => {
  const requests: Request[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      requests.push(request);
      if (request.method === "DELETE") return new Response(null, { status: 204 });
      return Response.json([
        server("drive", "Google Drive", "CONNECTED"),
        server("jira", "Jira Tasco", "NOT_CONNECTED"),
      ]);
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
  fireEvent.click(screen.getByRole("button", { name: "Disconnect" }));
  fireEvent.click(await screen.findByRole("button", { name: "Disconnect" }));
  await waitFor(() => expect(requests.some((request) => request.method === "DELETE")).toBe(true));
  const deleted = requests.find((request) => request.method === "DELETE")!;
  expect(new URL(deleted.url).pathname).toBe("/api/mcp/connections/drive/connection");
});
