import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import type { McpConnection } from "@/lib/hey-api/types.gen";
import { ChatMcpServers } from "./chat-mcp-options";

const listMcpConnections = vi.fn();
const startMcpConnectionAuthorization = vi.fn();
const saveMcpConnectionApiKey = vi.fn();

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  listMcpConnections: (...args: unknown[]) => listMcpConnections(...args),
  startMcpConnectionAuthorization: (...args: unknown[]) => startMcpConnectionAuthorization(...args),
  saveMcpConnectionApiKey: (...args: unknown[]) => saveMcpConnectionApiKey(...args),
}));

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
  listMcpConnections.mockResolvedValue({ data: connections });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ChatMcpServers
        selected={selected}
        onChange={onChange}
        onBack={vi.fn()}
        sessionId="session-7"
      />
    </QueryClientProvider>,
  );
  return onChange;
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
});

it("selects a connected server for the turn", async () => {
  const onChange = show([connection({})]);
  const toggle = await screen.findByRole("switch", { name: "Drive" });
  await userEvent.click(toggle);
  expect(onChange).toHaveBeenCalledWith(["11111111-1111-4111-8111-111111111111"]);
});

it("offers Connect instead of a switch until the User has connected", async () => {
  show([
    connection({ connectionState: "NOT_CONNECTED", oauthClients: [{ id: "c1", label: "Org A" }] }),
  ]);
  expect(await screen.findByRole("button", { name: "Kết nối" })).toBeInTheDocument();
  expect(screen.queryByRole("switch")).not.toBeInTheDocument();
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
  expect(screen.getByRole("button", { name: "Org A" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Org B" })).toBeInTheDocument();
  expect(startMcpConnectionAuthorization).not.toHaveBeenCalled();
});

it("cannot be selected when the administrator enabled no tools", async () => {
  show([connection({ enabledToolCount: 0 })]);
  expect(await screen.findByRole("switch", { name: "Drive" })).toBeDisabled();
});

it("says an OAuth server is waiting on an administrator when it has no client", async () => {
  show([connection({ connectionState: "NOT_CONNECTED", oauthClients: [] })]);
  expect(await screen.findByText("Chờ quản trị viên")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Kết nối" })).not.toBeInTheDocument();
});
