import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
  stripSearchParams,
} from "@tanstack/react-router";
import { HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import {
  handleAddMcpTrustedApp,
  handleGetMcpEndpointInsights,
  handleGetMcpEndpointSettings,
  handleListMcpEndpointActivity,
  handleListMcpTrustedApps,
  handleRemoveMcpTrustedApp,
  handleSetMcpTrustedAppEnabled,
  handleUpdateMcpEndpointSettings,
} from "@/lib/hey-api/msw.gen";
import type {
  McpEndpointCallResponse,
  McpEndpointSettingsResponse,
  McpTrustedAppListResponse,
  McpTrustedAppResponse,
} from "@/lib/hey-api/types.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { server } from "@/test/msw";
import { McpEndpointAdminPage } from "./mcp-endpoint-admin-page";
import {
  DEFAULT_MCP_ENDPOINT_SEARCH,
  mcpEndpointAdminSearchSchema,
} from "./mcp-endpoint-admin-search";

const settings: McpEndpointSettingsResponse = {
  configured: true,
  enabled: false,
  revision: 4,
  url: "https://memoryos.example.vn/mcp",
};

const claude: McpTrustedAppResponse = {
  id: "a0000000-0000-4000-8000-000000000001",
  preset: "CLAUDE",
  name: "Claude",
  clientIdHosts: ["claude.ai", "claude.com"],
  documentHosts: ["claude.ai", "claude.com", "localhost", "127.0.0.1"],
  enabled: true,
  builtIn: true,
  revision: 1,
};
const chatGpt: McpTrustedAppResponse = {
  id: "a0000000-0000-4000-8000-000000000002",
  preset: "CHATGPT",
  name: "ChatGPT",
  clientIdHosts: ["chatgpt.com"],
  documentHosts: ["chatgpt.com", "persistent.oaistatic.com"],
  enabled: true,
  builtIn: true,
  revision: 3,
};
const agent: McpTrustedAppResponse = {
  id: "a0000000-0000-4000-8000-000000000003",
  preset: "CUSTOM",
  name: "Trợ lý Tasco",
  clientIdHosts: ["agent.tasco.vn"],
  documentHosts: ["agent.tasco.vn"],
  enabled: true,
  builtIn: false,
  revision: 2,
};
const trusted: McpTrustedAppListResponse = { manageable: true, apps: [claude, chatGpt, agent] };

const call = (overrides: Partial<McpEndpointCallResponse>): McpEndpointCallResponse => ({
  id: "c0000000-0000-4000-8000-000000000001",
  occurredAt: "2026-10-01T03:14:09Z",
  actorId: "10000000-0000-4000-8000-000000000001",
  actorName: "Trần Thu Hà",
  actorEmail: "ha@tasco.vn",
  client: "CLAUDE",
  clientName: "Claude",
  tool: "search",
  outcome: "SUCCESS",
  ...overrides,
});

let router: ReturnType<typeof endpointRouter> | undefined;

function endpointRouter(path: string) {
  const root = createRootRoute();
  const authenticated = createRoute({ getParentRoute: () => root, id: "_authenticated" });
  const admin = createRoute({ getParentRoute: () => authenticated, path: "admin" });
  const endpoint = createRoute({
    getParentRoute: () => admin,
    path: "mcp-endpoint",
    validateSearch: mcpEndpointAdminSearchSchema,
    search: { middlewares: [stripSearchParams(DEFAULT_MCP_ENDPOINT_SEARCH)] },
    component: McpEndpointAdminPage,
  });
  return createRouter({
    routeTree: root.addChildren([authenticated.addChildren([admin.addChildren([endpoint])])]),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
}

function mount(path = "/admin/mcp-endpoint") {
  router = endpointRouter(path);
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <ActionNotifications>
        <RouterProvider router={router} />
      </ActionNotifications>
    </QueryClientProvider>,
  );
}

describe("MemoryOS MCP administration", () => {
  it("turns the endpoint on at the revision it read and gives its URL", async () => {
    const saved: unknown[] = [];
    server.use(
      handleGetMcpEndpointSettings({ body: settings }),
      handleListMcpTrustedApps({ body: trusted }),
      handleUpdateMcpEndpointSettings(async ({ request }) => {
        saved.push(await request.json());
        return HttpResponse.json({ ...settings, enabled: true, revision: 5 });
      }),
    );
    const user = userEvent.setup();
    mount();

    expect(await screen.findByLabelText("MCP URL")).toHaveValue("https://memoryos.example.vn/mcp");
    expect(screen.queryByLabelText("Client secret")).toBeNull();
    await user.click(screen.getByRole("switch", { name: "Turn on MemoryOS MCP" }));
    await waitFor(() => expect(saved).toEqual([{ enabled: true, revision: 4 }]));
  });

  it("locks the page on a deployment without an endpoint URL", async () => {
    server.use(
      handleGetMcpEndpointSettings({
        body: { configured: false, enabled: false, revision: 0, url: null },
      }),
    );
    mount();

    expect(await screen.findByText("This server has no MCP URL configured.")).toBeVisible();
    expect(screen.queryByRole("switch")).toBeNull();
    expect(screen.queryByRole("tab")).toBeNull();
  });

  it("asks before it stops trusting an app, and turns one back on without asking", async () => {
    const changes: unknown[] = [];
    server.use(
      handleGetMcpEndpointSettings({ body: settings }),
      handleListMcpTrustedApps({
        body: { manageable: true, apps: [claude, { ...chatGpt, enabled: false }, agent] },
      }),
      handleSetMcpTrustedAppEnabled(async ({ request, params }) => {
        const body = (await request.json()) as { enabled: boolean };
        changes.push({ app: params.appId, ...body });
        return HttpResponse.json({ ...claude, enabled: body.enabled, revision: 2 });
      }),
    );
    const user = userEvent.setup();
    mount();

    const apps = await screen.findByRole("region", { name: "Trusted apps" });
    expect(await within(apps).findByText("claude.ai, claude.com")).toBeVisible();
    await user.click(await within(apps).findByRole("switch", { name: "Claude" }));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent("Every connection through Claude will be revoked.");
    expect(changes).toEqual([]);
    await user.click(within(dialog).getByRole("button", { name: "Stop trusting" }));
    await waitFor(() => expect(changes).toEqual([{ app: claude.id, enabled: false, revision: 1 }]));

    await user.click(within(apps).getByRole("switch", { name: "ChatGPT" }));
    await waitFor(() => expect(changes).toHaveLength(2));
    expect(changes[1]).toEqual({ app: chatGpt.id, enabled: true, revision: 3 });
    // Only an app of the organization's own can be removed.
    expect(within(apps).getAllByRole("button", { name: /^Remove / })).toHaveLength(1);
  });

  it("adds an app by its domains once every domain is one, and removes it after confirmation", async () => {
    const added: unknown[] = [];
    const removed: Array<string | null> = [];
    server.use(
      handleGetMcpEndpointSettings({ body: settings }),
      handleListMcpTrustedApps({ body: trusted }),
      handleAddMcpTrustedApp(async ({ request }) => {
        added.push(await request.json());
        return HttpResponse.json(agent);
      }),
      handleRemoveMcpTrustedApp(({ request }) => {
        removed.push(new URL(request.url).searchParams.get("revision"));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    mount();

    await user.click(await screen.findByRole("button", { name: "Add app" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("Name"), "Copilot Studio");
    await user.type(
      within(dialog).getByLabelText("Client ID domains"),
      "Copilot.Example.com, bad_host",
    );
    expect(within(dialog).getByText("“bad_host” is not a domain.")).toBeVisible();
    expect(within(dialog).getByRole("button", { name: "Add" })).toBeDisabled();
    await user.clear(within(dialog).getByLabelText("Client ID domains"));
    await user.type(
      within(dialog).getByLabelText("Client ID domains"),
      "copilot.example.com *.copilot.example.com",
    );
    await user.type(within(dialog).getByLabelText(/^Other domains/), "localhost");
    await user.click(within(dialog).getByRole("button", { name: "Add" }));
    await waitFor(() =>
      expect(added).toEqual([
        {
          name: "Copilot Studio",
          clientIdHosts: ["copilot.example.com", "*.copilot.example.com"],
          documentHosts: ["localhost"],
        },
      ]),
    );

    await user.click(screen.getByRole("button", { name: "Remove Trợ lý Tasco" }));
    const confirm = await screen.findByRole("alertdialog");
    await user.click(within(confirm).getByRole("button", { name: "Remove" }));
    await waitFor(() => expect(removed).toEqual(["2"]));
  });

  it("shows the apps but changes none while this server has no account to manage them", async () => {
    server.use(
      handleGetMcpEndpointSettings({ body: settings }),
      handleListMcpTrustedApps({ body: { ...trusted, manageable: false } }),
    );
    mount();

    expect(
      await screen.findByText("This server has no account configured to manage trusted apps."),
    ).toBeVisible();
    for (const name of ["Claude", "ChatGPT", "Trợ lý Tasco"]) {
      expect(screen.getByRole("switch", { name })).toBeDisabled();
    }
    expect(screen.queryByRole("button", { name: "Add app" })).toBeNull();
    expect(screen.queryByRole("button", { name: /^Remove / })).toBeNull();
  });

  it("reads the activity filters from the address and writes a new one there", async () => {
    const requested: URL[] = [];
    server.use(
      handleGetMcpEndpointSettings({ body: settings }),
      handleListMcpEndpointActivity(({ request }) => {
        requested.push(new URL(request.url));
        return HttpResponse.json({
          calls: [
            call({ outcome: "RATE_LIMITED", tool: "search_with_filters" }),
            call({
              id: "c0000000-0000-4000-8000-000000000002",
              client: "OTHER",
              clientName: "agent.tasco.vn",
              tool: "fetch",
            }),
          ],
          next: null,
        });
      }),
    );
    const user = userEvent.setup();
    mount("/admin/mcp-endpoint?tab=activity&outcome=RATE_LIMITED");

    const table = await screen.findByRole("table", { name: "MemoryOS MCP activity" });
    expect(requested.at(-1)!.searchParams.get("outcome")).toBe("RATE_LIMITED");
    expect(requested.at(-1)!.searchParams.get("from")).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(within(table).getByText("Rate limited")).toBeVisible();
    expect(within(table).getByText("agent.tasco.vn")).toBeVisible();
    expect(within(table).getAllByText("Trần Thu Hà")).toHaveLength(2);

    await user.click(screen.getByRole("combobox", { name: "App" }));
    await user.click(await screen.findByRole("option", { name: "ChatGPT" }));
    await waitFor(() => expect(requested.at(-1)!.searchParams.get("client")).toBe("CHATGPT"));
    expect(router?.state.location.search).toMatchObject({
      tab: "activity",
      outcome: "RATE_LIMITED",
      client: "CHATGPT",
    });
  });

  it("counts calls, people, failures and refusals for rate, and by app and tool", async () => {
    const requested: URL[] = [];
    server.use(
      handleGetMcpEndpointSettings({ body: settings }),
      handleGetMcpEndpointInsights(({ request }) => {
        const url = new URL(request.url);
        requested.push(url);
        return HttpResponse.json({
          days: Number(url.searchParams.get("days")),
          calls: 1284,
          people: 37,
          failed: 3,
          rateLimited: 0,
          apps: [
            {
              key: "https://claude.ai/oauth/mcp-oauth-client-metadata",
              name: "Claude",
              calls: 902,
              people: 30,
            },
            {
              key: "https://chatgpt.com/oauth/client.json",
              name: "ChatGPT",
              calls: 382,
              people: 11,
            },
          ],
          tools: [
            { key: "search", name: "search", calls: 811, people: 36 },
            { key: "fetch", name: "fetch", calls: 473, people: 29 },
          ],
          daily: [{ day: "2026-10-01", calls: 214, people: 19 }],
        });
      }),
    );
    const user = userEvent.setup();
    mount("/admin/mcp-endpoint?tab=insights");

    expect(await screen.findByText("1,284")).toBeVisible();
    expect(requested.at(-1)!.searchParams.get("days")).toBe("7");
    expect(screen.getByText("37")).toBeVisible();
    const byTool = screen.getByText("By tool").closest("[data-slot=card]") as HTMLElement;
    expect(within(byTool).getByText("search")).toBeVisible();
    expect(within(byTool).getByText("811")).toBeVisible();

    await user.click(screen.getByRole("combobox", { name: "Period" }));
    await user.click(await screen.findByRole("option", { name: "Last 30 days" }));
    await waitFor(() => expect(requested.at(-1)!.searchParams.get("days")).toBe("30"));
    expect(router?.state.location.searchStr).toBe("?tab=insights&days=30");
  });
});
