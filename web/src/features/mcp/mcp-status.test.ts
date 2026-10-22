import { expect, it } from "vitest";
import type { McpConnection, McpServerView } from "@/lib/hey-api/types.gen";
import { serverNextStep } from "./mcp-status";

const server = (overrides: Partial<McpServerView> = {}): McpServerView => ({
  id: "s1",
  slug: "drive",
  name: "Drive",
  description: null,
  url: "https://drivemcp.googleapis.com/mcp/v1",
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

const own = (connectionState: McpConnection["connectionState"]): McpConnection => ({
  id: "s1",
  slug: "drive",
  name: "Drive",
  description: null,
  url: "https://drivemcp.googleapis.com/mcp/v1",
  authType: "OAUTH",
  authPerformer: "PER_USER",
  status: "CREATED",
  connectionState,
  oauthClients: [],
  enabledToolCount: 0,
  credentialUpdatedAt: null,
  revision: 1,
});

it("asks for an OAuth application first", () => {
  expect(serverNextStep(server(), 0, own("NOT_CONNECTED"))).toBe("ADD_OAUTH_APP");
});

it("waits for the applications to load before naming a step that depends on them", () => {
  expect(serverNextStep(server(), undefined, own("CONNECTED"))).toBe("FETCH_TOOLS");
});

it("asks for the shared account on a shared server", () => {
  expect(serverNextStep(server({ authPerformer: "ADMIN" }), 1, undefined)).toBe("CONNECT_SHARED");
  expect(
    serverNextStep(server({ authType: "API_TOKEN", authPerformer: "ADMIN" }), undefined, undefined),
  ).toBe("CONNECT_SHARED");
});

it("asks the administrator to connect their own account before listing a per-person server's tools", () => {
  expect(serverNextStep(server(), 1, own("NOT_CONNECTED"))).toBe("CONNECT_OWN");
  expect(serverNextStep(server(), 1, own("REAUTH_REQUIRED"))).toBe("CONNECT_OWN");
});

it("does not ask for an own connection the administrator may not make", () => {
  expect(serverNextStep(server(), 1, undefined)).toBe("FETCH_TOOLS");
});

it("asks to fetch tools until the server is connected with some, then nothing", () => {
  expect(serverNextStep(server(), 1, own("CONNECTED"))).toBe("FETCH_TOOLS");
  expect(
    serverNextStep(server({ status: "DISCONNECTED", toolCount: 3 }), 1, own("CONNECTED")),
  ).toBe("FETCH_TOOLS");
  expect(serverNextStep(server({ status: "CONNECTED", toolCount: 3 }), 1, own("CONNECTED"))).toBe(
    null,
  );
  expect(
    serverNextStep(
      server({ authType: "NONE", status: "CONNECTED", toolCount: 1 }),
      undefined,
      undefined,
    ),
  ).toBe(null);
});
