import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import type { McpOAuthClientView, McpServerView } from "@/lib/hey-api/types.gen";
import { McpOAuthClients } from "./mcp-oauth-clients";

const listMcpServerOAuthClients = vi.fn();
const discoverMcpServerOAuth = vi.fn();
const registerMcpServerOAuthClient = vi.fn();
const createMcpServerOAuthClient = vi.fn();
const deleteMcpServerOAuthClient = vi.fn();
const startMcpServerOAuthAuthorization = vi.fn();
const disconnectMcpServerOAuth = vi.fn();

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  listMcpServerOAuthClients: (...a: unknown[]) => listMcpServerOAuthClients(...a),
  discoverMcpServerOAuth: (...a: unknown[]) => discoverMcpServerOAuth(...a),
  registerMcpServerOAuthClient: (...a: unknown[]) => registerMcpServerOAuthClient(...a),
  createMcpServerOAuthClient: (...a: unknown[]) => createMcpServerOAuthClient(...a),
  deleteMcpServerOAuthClient: (...a: unknown[]) => deleteMcpServerOAuthClient(...a),
  startMcpServerOAuthAuthorization: (...a: unknown[]) => startMcpServerOAuthAuthorization(...a),
  disconnectMcpServerOAuth: (...a: unknown[]) => disconnectMcpServerOAuth(...a),
}));

const server = (overrides: Partial<McpServerView> = {}): McpServerView => ({
  id: "22222222-2222-4222-8222-222222222222",
  slug: "drive",
  name: "Drive",
  description: null,
  url: "https://drive.example/mcp",
  authType: "OAUTH",
  authPerformer: "PER_USER",
  oauthProviderMode: "AUTO_DISCOVERY",
  oauthScopes: [],
  oauthAdditionalParameters: {},
  headerNames: [],
  sharedCredentialConfigured: false,
  tenantWide: true,
  groupIds: [],
  status: "AWAITING_AUTH",
  lastRefreshedAt: null,
  toolCount: 0,
  enabledToolCount: 0,
  revision: 1,
  ...overrides,
});

const client = (overrides: Partial<McpOAuthClientView> = {}): McpOAuthClientView => ({
  id: "c1",
  label: "Tasco North",
  source: "ADMIN",
  issuer: "https://accounts.google.com",
  clientId: "client-1",
  clientSecretConfigured: true,
  tokenEndpointAuthMethod: "CLIENT_SECRET_POST",
  authorizationEndpoint: "https://accounts.google.com/authorize",
  tokenEndpoint: "https://oauth2.googleapis.com/token",
  revocationEndpoint: null,
  issParameterRequired: false,
  revision: 1,
  ...overrides,
});

function show(view: McpServerView, clients: McpOAuthClientView[] = []) {
  listMcpServerOAuthClients.mockResolvedValue({ data: clients });
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queries}>
      <McpOAuthClients server={view} />
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
});

it("says nobody can connect while the server has no OAuth application", async () => {
  show(server());
  expect(
    await screen.findByText(
      "Chưa có ứng dụng OAuth nào. Người dùng chưa kết nối được máy chủ này.",
    ),
  ).toBeInTheDocument();
});

it("offers discovery only for an auto-discovery server", async () => {
  show(server({ oauthProviderMode: "KNOWN_PROVIDER" }));
  expect(await screen.findByRole("button", { name: "Nhập ứng dụng" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Dò máy chủ OAuth" })).not.toBeInTheDocument();
});

it("requires a label before registering a discovered authorization server", async () => {
  discoverMcpServerOAuth.mockResolvedValue({
    data: {
      resource: "https://drive.example/mcp",
      suggestedScopes: [],
      authorizationServers: [
        {
          issuer: "https://accounts.google.com",
          authorizationEndpoint: "https://accounts.google.com/authorize",
          tokenEndpoint: "https://oauth2.googleapis.com/token",
          registrationEndpoint: "https://accounts.google.com/register",
          revocationEndpoint: null,
          issParameterSupported: true,
          registrationAvailable: true,
          metadataDocumentAvailable: false,
        },
      ],
    },
  });
  show(server());
  await userEvent.click(await screen.findByRole("button", { name: "Dò máy chủ OAuth" }));
  const register = await screen.findByRole("button", { name: "Tự đăng ký (DCR)" });
  expect(register).toBeDisabled();
  await userEvent.type(screen.getByLabelText("Nhãn cho ứng dụng này"), "Tasco North");
  expect(register).toBeEnabled();
  await userEvent.click(register);
  expect(registerMcpServerOAuthClient).toHaveBeenCalledWith(
    expect.objectContaining({
      body: { issuer: "https://accounts.google.com", label: "Tasco North", source: "REGISTERED" },
    }),
  );
});

it("says so when a discovered server supports neither registration route", async () => {
  discoverMcpServerOAuth.mockResolvedValue({
    data: {
      resource: "https://drive.example/mcp",
      suggestedScopes: [],
      authorizationServers: [
        {
          issuer: "https://accounts.google.com",
          authorizationEndpoint: "https://accounts.google.com/authorize",
          tokenEndpoint: "https://oauth2.googleapis.com/token",
          registrationEndpoint: null,
          revocationEndpoint: null,
          issParameterSupported: false,
          registrationAvailable: false,
          metadataDocumentAvailable: false,
        },
      ],
    },
  });
  show(server());
  await userEvent.click(await screen.findByRole("button", { name: "Dò máy chủ OAuth" }));
  expect(
    await screen.findByText("Máy chủ này không tự đăng ký được. Hãy nhập ứng dụng thủ công."),
  ).toBeInTheDocument();
});

it("offers the administrator's own connect only on a shared-connection server", async () => {
  show(server({ authPerformer: "PER_USER" }), [client()]);
  expect(await screen.findByText("Tasco North")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Kết nối" })).not.toBeInTheDocument();
});

it("connects and disconnects the shared connection", async () => {
  startMcpServerOAuthAuthorization.mockResolvedValue({
    data: { authorizationUrl: "https://as/auth" },
  });
  show(server({ authPerformer: "ADMIN", sharedCredentialConfigured: true }), [client()]);
  await userEvent.click(await screen.findByRole("button", { name: "Kết nối" }));
  expect(startMcpServerOAuthAuthorization).toHaveBeenCalledWith(
    expect.objectContaining({ body: { oauthClientId: "c1" } }),
  );
  await userEvent.click(screen.getByRole("button", { name: "Ngắt kết nối dùng chung" }));
  expect(disconnectMcpServerOAuth).toHaveBeenCalled();
});

it("sends a pasted application with its secret posted in the token request", async () => {
  show(server());
  await userEvent.click(await screen.findByRole("button", { name: "Nhập ứng dụng" }));
  await userEvent.type(screen.getByLabelText("Nhãn"), "Tasco North");
  await userEvent.type(screen.getByLabelText("Issuer"), "https://accounts.google.com");
  await userEvent.type(screen.getByLabelText("Client ID"), "client-1");
  await userEvent.type(screen.getByLabelText("Client secret"), "s3cret");
  await userEvent.type(
    screen.getByLabelText("Điểm cuối cấp quyền"),
    "https://accounts.google.com/authorize",
  );
  await userEvent.type(
    screen.getByLabelText("Điểm cuối lấy token"),
    "https://oauth2.googleapis.com/token",
  );
  await userEvent.click(screen.getByRole("button", { name: "Lưu" }));
  expect(createMcpServerOAuthClient).toHaveBeenCalledWith(
    expect.objectContaining({
      body: expect.objectContaining({
        label: "Tasco North",
        tokenEndpointAuthMethod: "CLIENT_SECRET_POST",
        clientSecret: { action: "REPLACE", value: "s3cret" },
      }),
    }),
  );
});
