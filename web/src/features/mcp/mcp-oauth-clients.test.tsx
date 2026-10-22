import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { i18n } from "@/i18n";
import {
  handleCreateMcpServerOAuthClient,
  handleDeleteMcpServerOAuthClient,
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
import { useOAuthSetup } from "./mcp-oauth-setup";

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
  personaIds: [],
  publicBots: false,
  status: "CREATED",
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

/** The server row starts adding the first application; its button is the step under test here. */
function Harness({ view }: { view: McpServerView }) {
  const setup = useOAuthSetup(view);
  return (
    <>
      <button type="button" onClick={setup.begin}>
        start
      </button>
      <McpOAuthClients server={view} setup={setup} />
    </>
  );
}

function show(view: McpServerView, clients: McpOAuthClientView[] = []) {
  http.use(
    handleListMcpServerOAuthClients({ body: clients }),
    // A change rereads the server list beside the clients.
    handleListMcpServers({ body: [view] }),
  );
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queries}>
      <Harness view={view} />
    </QueryClientProvider>,
  );
}

function discovering(authorizationServers: McpOAuthAuthorizationServer[]) {
  let calls = 0;
  http.use(
    handleDiscoverMcpServerOAuth(() => {
      calls += 1;
      return HttpResponse.json({
        resource: "https://drive.example/mcp",
        suggestedScopes: [],
        authorizationServers,
      });
    }),
  );
  return () => calls;
}

function creating() {
  const created: unknown[] = [];
  http.use(
    handleCreateMcpServerOAuthClient(async ({ request }) => {
      created.push(await request.json());
      return HttpResponse.json(client());
    }),
  );
  return created;
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

it("says the server has no OAuth application yet", async () => {
  show(server());
  expect(await screen.findByText("Chưa có ứng dụng OAuth nào.")).toBeInTheDocument();
});

it("creates the application automatically, named after the server, preferring the metadata document", async () => {
  const registered: unknown[] = [];
  discovering([authorizationServer({ metadataDocumentAvailable: true })]);
  http.use(
    handleRegisterMcpServerOAuthClient(async ({ request }) => {
      registered.push(await request.json());
      return HttpResponse.json(client({ source: "METADATA_DOCUMENT" }));
    }),
  );
  show(server());
  await userEvent.click(await screen.findByRole("button", { name: "start" }));
  expect(await screen.findByLabelText("Nhãn")).toHaveValue("Drive");
  await userEvent.click(screen.getByRole("button", { name: "Tạo tự động" }));
  await waitFor(() =>
    expect(registered).toEqual([
      { issuer: "https://accounts.google.com", label: "Drive", source: "METADATA_DOCUMENT" },
    ]),
  );
});

it("opens the manual form with the discovered endpoints when registration is impossible", async () => {
  const created = creating();
  discovering([
    authorizationServer({
      registrationEndpoint: null,
      registrationAvailable: false,
      revocationEndpoint: "https://oauth2.googleapis.com/revoke",
    }),
  ]);
  show(server());
  await userEvent.click(await screen.findByRole("button", { name: "start" }));
  await userEvent.type(await screen.findByLabelText("Client ID"), "client-1");
  await userEvent.type(screen.getByLabelText(/Client secret/), "s3cret");
  expect(screen.queryByLabelText("Issuer")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Lưu" }));
  await waitFor(() => expect(created).toHaveLength(1));
  expect(created[0]).toMatchObject({
    label: "Drive",
    issuer: "https://accounts.google.com",
    clientId: "client-1",
    authorizationEndpoint: "https://accounts.google.com/authorize",
    tokenEndpoint: "https://oauth2.googleapis.com/token",
    revocationEndpoint: "https://oauth2.googleapis.com/revoke",
    tokenEndpointAuthMethod: "CLIENT_SECRET_POST",
    clientSecret: { action: "REPLACE", value: "s3cret" },
  });
});

it("names a label another OAuth application of the server already uses", async () => {
  discovering([authorizationServer({ registrationEndpoint: null, registrationAvailable: false })]);
  http.use(
    handleCreateMcpServerOAuthClient(() =>
      HttpResponse.json(
        { status: 409, title: "Conflict", code: "MCP_OAUTH_CLIENT_LABEL_TAKEN" },
        { status: 409 },
      ),
    ),
  );
  show(server());
  await userEvent.click(await screen.findByRole("button", { name: "start" }));
  await userEvent.type(await screen.findByLabelText("Client ID"), "client-1");
  await userEvent.click(screen.getByRole("button", { name: "Lưu" }));
  expect(
    await screen.findByText(
      "Ứng dụng này đã có một ứng dụng OAuth mang nhãn đó. Hãy đặt nhãn khác.",
    ),
  ).toBeInTheDocument();
  expect(screen.queryByText(/Mục này đã thay đổi/)).not.toBeInTheDocument();
});

it("goes straight to the manual form for a server with a known provider", async () => {
  const calls = discovering([authorizationServer()]);
  show(server({ oauthProviderMode: "KNOWN_PROVIDER" }));
  await userEvent.click(await screen.findByRole("button", { name: "start" }));
  expect(await screen.findByLabelText("Issuer")).toBeInTheDocument();
  expect(calls()).toBe(0);
});

it("offers the administrator's own connect only on a shared-connection server", async () => {
  show(server({ authPerformer: "PER_USER" }), [client()]);
  expect(await screen.findByText("Tasco North")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Kết nối" })).not.toBeInTheDocument();
});

it("connects the shared account and asks before disconnecting it", async () => {
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
  await userEvent.click(await screen.findByRole("button", { name: "Kết nối lại" }));
  await waitFor(() => expect(started).toEqual([{ oauthClientId: "c1" }]));
  await userEvent.click(screen.getByRole("button", { name: "Ngắt kết nối dùng chung" }));
  expect(disconnected).toBe(false);
  await userEvent.click(await screen.findByRole("button", { name: "Ngắt kết nối" }));
  await waitFor(() => expect(disconnected).toBe(true));
});

it("asks before deleting an application", async () => {
  let deleted = false;
  http.use(
    handleDeleteMcpServerOAuthClient(() => {
      deleted = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  show(server(), [client()]);
  await userEvent.click(await screen.findByRole("button", { name: "Xóa Tasco North" }));
  expect(deleted).toBe(false);
  expect(await screen.findByText("Xóa ứng dụng Tasco North?")).toBeInTheDocument();
  const dialog = screen.getByRole("alertdialog");
  await userEvent.click(
    [...dialog.querySelectorAll("button")].find((button) => button.textContent === "Xóa")!,
  );
  await waitFor(() => expect(deleted).toBe(true));
});

it("shows an alert instead of an empty list when the load fails", async () => {
  http.use(handleListMcpServerOAuthClients(() => new HttpResponse(null, { status: 503 })));
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queries}>
      <Harness view={server()} />
    </QueryClientProvider>,
  );
  expect(await screen.findByRole("alert")).toBeInTheDocument();
  expect(screen.queryByText("Chưa có ứng dụng OAuth nào.")).not.toBeInTheDocument();
});
