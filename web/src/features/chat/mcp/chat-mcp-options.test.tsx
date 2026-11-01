import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { HttpResponse } from "msw";
import { i18n } from "@/i18n/index";
import {
  handleListMcpConnections,
  handleStartMcpConnectionAuthorization,
} from "@/lib/hey-api/msw.gen";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ChatMcpServers } from "./chat-mcp-options";

const startMcpConnectionAuthorization = vi.fn();

const connection = (overrides: Partial<McpConnection>): McpConnection => ({
  id: "11111111-1111-4111-8111-111111111111",
  slug: "drive",
  name: "Drive",
  description: null,
  url: "https://drive.example/mcp",
  authType: "OAUTH",
  authPerformer: "PER_USER",
  status: "CONNECTED",
  connectionState: "CONNECTED",
  oauthClients: [],
  enabledToolCount: 3,
  credentialUpdatedAt: null,
  revision: 1,
  ...overrides,
});

function show(connections: McpConnection[], onChange = vi.fn(), selected: string[] = []) {
  server.use(
    handleListMcpConnections({ body: connections }),
    handleStartMcpConnectionAuthorization(() => {
      startMcpConnectionAuthorization();
      return HttpResponse.json({});
    }),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // The submenu links to Settings › Connections, so it renders inside a router.
  const rootRoute = createRootRoute();
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path: "/",
    component: () => (
      <ChatMcpServers
        selected={selected}
        onChange={onChange}
        onBack={vi.fn()}
        sessionId="session-7"
      />
    ),
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([route]),
    history: createMemoryHistory({ initialEntries: ["/"] }),
  });
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return onChange;
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
});

it("selects a connected server for the turn by its row", async () => {
  const onChange = show([connection({})]);
  const row = await screen.findByRole("button", { name: "Drive" });
  expect(row).toHaveAttribute("aria-pressed", "false");
  await userEvent.click(row);
  expect(onChange).toHaveBeenCalledWith(["11111111-1111-4111-8111-111111111111"]);
});

it("marks a selected server and clears it from the same row", async () => {
  const onChange = show([connection({})], vi.fn(), ["11111111-1111-4111-8111-111111111111"]);
  const row = await screen.findByRole("button", { name: "Drive" });
  expect(row).toHaveAttribute("aria-pressed", "true");
  await userEvent.click(row);
  expect(onChange).toHaveBeenCalledWith([]);
});

it("lists servers still to connect after the usable ones, under their own heading", async () => {
  show([
    connection({
      id: "22222222-2222-4222-8222-222222222222",
      name: "Gmail",
      connectionState: "REAUTH_REQUIRED",
      oauthClients: [{ id: "c1", label: "Org A" }],
    }),
    connection({}),
  ]);
  const usable = await screen.findByRole("button", { name: "Drive" });
  const heading = screen.getByText("Chưa kết nối");
  expect(usable.compareDocumentPosition(heading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(heading.compareDocumentPosition(screen.getByText("Gmail"))).toBe(
    Node.DOCUMENT_POSITION_FOLLOWING,
  );
  expect(screen.queryByRole("button", { name: "Gmail" })).not.toBeInTheDocument();
});

it("offers Connect instead of a selectable row until the User has connected", async () => {
  show([
    connection({ connectionState: "NOT_CONNECTED", oauthClients: [{ id: "c1", label: "Org A" }] }),
  ]);
  expect(await screen.findByRole("button", { name: "Kết nối" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Drive" })).not.toBeInTheDocument();
});

it("asks which account to use only when the server has several", async () => {
  show([
    connection({
      connectionState: "NOT_CONNECTED",
      oauthClients: [
        { id: "c1", label: "Org A" },
        { id: "c2", label: "Org B" },
      ],
    }),
  ]);
  await userEvent.click(await screen.findByRole("button", { name: "Kết nối" }));
  expect(await screen.findByRole("menuitem", { name: "Org A" })).toBeInTheDocument();
  expect(screen.getByRole("menuitem", { name: "Org B" })).toBeInTheDocument();
  expect(startMcpConnectionAuthorization).not.toHaveBeenCalled();
});

it("cannot be selected when the administrator enabled no tools", async () => {
  show([connection({ enabledToolCount: 0 })]);
  expect(await screen.findByRole("button", { name: /Drive/ })).toBeDisabled();
});

it("says an OAuth server is waiting on an administrator when it has no client", async () => {
  show([connection({ connectionState: "NOT_CONNECTED", oauthClients: [] })]);
  expect(await screen.findByText("Chờ quản trị viên")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Kết nối" })).not.toBeInTheDocument();
});
