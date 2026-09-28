import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { i18n } from "@/i18n";
import {
  handleCreateMcpServerOAuthClient,
  handleDisconnectMcpServerOAuth,
  handleDiscoverMcpServerOAuth,
  handleListMcpServerOAuthClients,
  handleListMcpServers,
  handleRegisterMcpServerOAuthClient,
  handleStartMcpServerOAuthAuthorization,
} from "@/lib/hey-api/msw.gen";
import type {
  McpOAuthAuthorizationServer,
  McpOAuthClientView,
  McpServerView,
} from "@/lib/hey-api/types.gen";
import { server as http } from "@/test/msw";
import { McpOAuthClients } from "./mcp-oauth-clients";

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

const authorizationServer = (
  overrides: Partial<McpOAuthAuthorizationServer> = {},
): McpOAuthAuthorizationServer => ({
  issuer: "https://accounts.google.com",
  authorizationEndpoint: "https://accounts.google.com/authorize",
  tokenEndpoint: "https://oauth2.googleapis.com/token",
  registrationEndpoint: "https://accounts.google.com/register",
  revocationEndpoint: null,
  issParameterSupported: true,
  registrationAvailable: true,
  metadataDocumentAvailable: false,
  ...overrides,
});

function show(view: McpServerView, clients: McpOAuthClientView[] = []) {
  http.use(
    handleListMcpServerOAuthClients({ body: clients }),
    // A change rereads the server list beside the clients.
    handleListMcpServers({ body: [view] }),
  );
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queries}>
      <McpOAuthClients server={view} />
    </QueryClientProvider>,
  );
}

function discovering(authorizationServers: McpOAuthAuthorizationServer[]) {
  http.use(
    handleDiscoverMcpServerOAuth({
      body: { resource: "https://drive.example/mcp", suggestedScopes: [], authorizationServers },
    }),
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
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
  const registered: unknown[] = [];
  discovering([authorizationServer()]);
  http.use(
    handleRegisterMcpServerOAuthClient(async ({ request }) => {
      registered.push(await request.json());
      return HttpResponse.json(client({ source: "REGISTERED" }));
    }),
  );
  show(server());
  await userEvent.click(await screen.findByRole("button", { name: "Dò máy chủ OAuth" }));
  const register = await screen.findByRole("button", { name: "Tự đăng ký (DCR)" });
  expect(register).toBeDisabled();
  await userEvent.type(screen.getByLabelText("Nhãn cho ứng dụng này"), "Tasco North");
  expect(register).toBeEnabled();
  await userEvent.click(register);
  await waitFor(() =>
    expect(registered).toEqual([
      { issuer: "https://accounts.google.com", label: "Tasco North", source: "REGISTERED" },
    ]),
  );
});

it("says so when a discovered server supports neither registration route", async () => {
  discovering([
    authorizationServer({
      registrationEndpoint: null,
      issParameterSupported: false,
      registrationAvailable: false,
    }),
  ]);
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
  const started: unknown[] = [];
  let disconnected = false;
  http.use(
    handleStartMcpServerOAuthAuthorization(async ({ request }) => {
      started.push(await request.json());
      return HttpResponse.json({ authorizationUrl: "https://as/auth" });
    }),
    handleDisconnectMcpServerOAuth(() => {
      disconnected = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  show(server({ authPerformer: "ADMIN", sharedCredentialConfigured: true }), [client()]);
  await userEvent.click(await screen.findByRole("button", { name: "Kết nối" }));
  await waitFor(() => expect(started).toEqual([{ oauthClientId: "c1" }]));
  await userEvent.click(screen.getByRole("button", { name: "Ngắt kết nối dùng chung" }));
  await waitFor(() => expect(disconnected).toBe(true));
});

it("sends a pasted application with its secret posted in the token request", async () => {
  const created: unknown[] = [];
  http.use(
    handleCreateMcpServerOAuthClient(async ({ request }) => {
      created.push(await request.json());
      return HttpResponse.json(client());
    }),
  );
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
  await waitFor(() => expect(created).toHaveLength(1));
  expect(created[0]).toMatchObject({
    label: "Tasco North",
    tokenEndpointAuthMethod: "CLIENT_SECRET_POST",
    clientSecret: { action: "REPLACE", value: "s3cret" },
  });
});

it("shows an alert instead of an empty list when the load fails", async () => {
  http.use(handleListMcpServerOAuthClients(() => new HttpResponse(null, { status: 503 })));
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queries}>
      <McpOAuthClients server={server()} />
    </QueryClientProvider>,
  );
  expect(await screen.findByRole("alert")).toBeInTheDocument();
  expect(
    screen.queryByText("Chưa có ứng dụng OAuth nào. Người dùng chưa kết nối được máy chủ này."),
  ).not.toBeInTheDocument();
});
