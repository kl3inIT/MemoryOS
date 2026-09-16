import { expect, test } from "@playwright/test";
import type {
  CurrentIdentity,
  ListMcpConnectionsResponse,
  ListMcpServerOAuthClientsResponse,
  ListMcpServerToolsResponse,
  ListMcpServersResponse,
} from "../../src/lib/hey-api/types.gen";

const administrator: CurrentIdentity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  uiLanguage: "vi",
  tenant: { displayName: "Tasco", role: "MEMBER" },
  capabilities: ["MCP_MANAGE", "CHAT_READ", "CHAT_WRITE"],
  scopedCapabilities: [],
};

const DRIVE = "11111111-1111-4111-8111-111111111111";
const JIRA = "22222222-2222-4222-8222-222222222222";

const servers: ListMcpServersResponse = [
  {
    id: DRIVE,
    slug: "drive",
    name: "Google Drive",
    description: "Tìm và đọc tài liệu trên Drive của tổ chức.",
    url: "https://drivemcp.googleapis.com/mcp",
    authType: "OAUTH",
    authPerformer: "PER_USER",
    oauthProviderMode: "AUTO_DISCOVERY",
    oauthScopes: ["https://www.googleapis.com/auth/drive.readonly"],
    oauthAdditionalParameters: { access_type: "offline" },
    headerNames: [],
    sharedCredentialConfigured: false,
    tenantWide: true,
    groupIds: [],
    status: "CONNECTED",
    lastRefreshedAt: "2026-09-16T03:10:00Z",
    toolCount: 6,
    enabledToolCount: 4,
    revision: 3,
  },
  {
    id: JIRA,
    slug: "jira",
    name: "Jira Cloud",
    description: "Đọc và tạo issue cho nhóm vận hành.",
    url: "https://mcp.atlassian.com/v1/sse",
    authType: "API_TOKEN",
    authPerformer: "ADMIN",
    oauthProviderMode: "AUTO_DISCOVERY",
    oauthScopes: [],
    oauthAdditionalParameters: {},
    headerNames: ["Authorization"],
    sharedCredentialConfigured: true,
    tenantWide: false,
    groupIds: ["group-ops"],
    status: "AWAITING_AUTH",
    lastRefreshedAt: null,
    toolCount: 0,
    enabledToolCount: 0,
    revision: 1,
  },
];

const tools: ListMcpServerToolsResponse = [
  {
    id: "t1",
    name: "search_files",
    modelName: "mcp_drive_search_files",
    title: "Tìm tệp",
    description: "Tìm tệp theo tên hoặc nội dung trong Drive của người dùng.",
    readOnlyHint: true,
    destructiveHint: false,
    enabled: true,
    exposable: true,
    snapshotAt: "2026-09-16T03:10:00Z",
    revision: 1,
  },
  {
    id: "t2",
    name: "read_file",
    modelName: "mcp_drive_read_file",
    title: "Đọc tệp",
    description: "Trả về nội dung văn bản của một tệp Drive.",
    readOnlyHint: true,
    destructiveHint: false,
    enabled: true,
    exposable: true,
    snapshotAt: "2026-09-16T03:10:00Z",
    revision: 1,
  },
  {
    id: "t3",
    name: "delete_file",
    modelName: "mcp_drive_delete_file",
    title: "Xoá tệp",
    description: "Chuyển một tệp vào thùng rác.",
    readOnlyHint: false,
    destructiveHint: true,
    enabled: false,
    exposable: true,
    snapshotAt: "2026-09-16T03:10:00Z",
    revision: 1,
  },
  {
    id: "t4",
    name: "a_tool_name_that_cannot_fit_inside_the_model_tool_name_limit_at_all",
    modelName: "",
    title: null,
    description: "Công cụ có tên quá dài nên không gửi cho mô hình được.",
    readOnlyHint: true,
    destructiveHint: false,
    enabled: false,
    exposable: false,
    snapshotAt: "2026-09-16T03:10:00Z",
    revision: 1,
  },
];

const oauthClients: ListMcpServerOAuthClientsResponse = [
  {
    id: "c1",
    label: "Tasco Miền Bắc",
    source: "ADMIN",
    issuer: "https://accounts.google.com",
    clientId: "north.apps.googleusercontent.com",
    clientSecretConfigured: true,
    tokenEndpointAuthMethod: "CLIENT_SECRET_POST",
    authorizationEndpoint: "https://accounts.google.com/o/oauth2/v2/auth",
    tokenEndpoint: "https://oauth2.googleapis.com/token",
    revocationEndpoint: "https://oauth2.googleapis.com/revoke",
    issParameterRequired: false,
    revision: 1,
  },
  {
    id: "c2",
    label: "Tasco Miền Nam",
    source: "REGISTERED",
    issuer: "https://accounts.google.com",
    clientId: "south.apps.googleusercontent.com",
    clientSecretConfigured: true,
    tokenEndpointAuthMethod: "CLIENT_SECRET_BASIC",
    authorizationEndpoint: "https://accounts.google.com/o/oauth2/v2/auth",
    tokenEndpoint: "https://oauth2.googleapis.com/token",
    revocationEndpoint: null,
    issParameterRequired: true,
    revision: 1,
  },
];

const connections: ListMcpConnectionsResponse = [
  {
    id: DRIVE,
    slug: "drive",
    name: "Google Drive",
    description: null,
    url: "https://drivemcp.googleapis.com/mcp",
    authType: "OAUTH",
    authPerformer: "PER_USER",
    status: "CONNECTED",
    connectionState: "CONNECTED",
    oauthClients: [{ id: "c1", label: "Tasco Miền Bắc" }],
    enabledToolCount: 4,
    credentialUpdatedAt: "2026-09-16T03:00:00Z",
    revision: 3,
  },
  {
    id: JIRA,
    slug: "jira",
    name: "Jira Cloud",
    description: null,
    url: "https://mcp.atlassian.com/v1/sse",
    authType: "API_TOKEN",
    authPerformer: "PER_USER",
    status: "CONNECTED",
    connectionState: "NOT_CONNECTED",
    oauthClients: [],
    enabledToolCount: 5,
    credentialUpdatedAt: null,
    revision: 1,
  },
  {
    id: "33333333-3333-4333-8333-333333333333",
    slug: "figma",
    name: "Figma",
    description: null,
    url: "https://mcp.figma.com/mcp",
    authType: "OAUTH",
    authPerformer: "PER_USER",
    status: "CONNECTED",
    connectionState: "REAUTH_REQUIRED",
    oauthClients: [
      { id: "f1", label: "Tasco Miền Bắc" },
      { id: "f2", label: "Tasco Miền Nam" },
    ],
    enabledToolCount: 3,
    credentialUpdatedAt: "2026-09-10T03:00:00Z",
    revision: 1,
  },
];

async function stub(page: import("@playwright/test").Page) {
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: administrator }));
  await page.route("**/api/mcp/servers", (route) => route.fulfill({ json: servers }));
  await page.route(`**/api/mcp/servers/${DRIVE}/tools`, (route) => route.fulfill({ json: tools }));
  await page.route(`**/api/mcp/servers/${DRIVE}/oauth/clients`, (route) =>
    route.fulfill({ json: oauthClients }),
  );
  await page.route("**/api/mcp/connections", (route) => route.fulfill({ json: connections }));
  await page.route("**/api/mcp/group-options*", (route) =>
    route.fulfill({ json: { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 } }),
  );
  for (const path of [
    "**/api/chat/sessions?*",
    "**/api/chat/projects?*",
    "**/api/chat/personas?*",
    "**/api/chat/models",
  ]) {
    await page.route(path, (route) => route.fulfill({ json: [] }));
  }
}

const OUTPUT = "D:/MemoryOS/output";

// These tests boot the app and capture full pages; the default budget is short under parallel load.
test.describe.configure({ timeout: 120_000 });

/** Captures the delivered pages the way MEM-108 recorded its own: both themes, desktop and phone. */
for (const theme of ["light", "dark"] as const) {
  for (const width of [1280, 390] as const) {
    test(`MCP screens are captured in ${theme} at ${width}px`, async ({ page }) => {
      await page.addInitScript((value) => localStorage.setItem("memoryos-theme", value), theme);
      await page.emulateMedia({ colorScheme: theme });
      await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
      await stub(page);

      await page.goto("/admin/mcp");
      // Four capture tests share one dev server, so the first paint is given more room than the default.
      await expect(page.getByRole("heading", { name: "Máy chủ MCP", level: 1 })).toBeVisible({
        timeout: 30_000,
      });
      await page.screenshot({
        path: `${OUTPUT}/mem112-mcp-admin-${theme}-${width}.png`,
        fullPage: true,
      });

      await page.getByRole("button", { name: "Xem công cụ" }).first().click();
      await expect(page.getByRole("heading", { name: "Ứng dụng OAuth" })).toBeVisible();
      await page.screenshot({
        path: `${OUTPUT}/mem112-mcp-tools-${theme}-${width}.png`,
        fullPage: true,
      });

      await page.goto("/admin/mcp");
      await page.getByRole("button", { name: "Thêm máy chủ" }).click();
      await expect(page.getByText("Địa chỉ callback")).toBeVisible();
      await page.screenshot({
        path: `${OUTPUT}/mem112-mcp-add-${theme}-${width}.png`,
        fullPage: true,
      });
      await page.keyboard.press("Escape");

      await page.goto("/");
      await page.getByRole("button", { name: "Thêm vào câu hỏi" }).click();
      await page.getByRole("button", { name: "Công cụ MCP" }).click();
      await expect(page.getByRole("switch", { name: "Google Drive" })).toBeVisible();
      await page.screenshot({ path: `${OUTPUT}/mem112-mcp-composer-${theme}-${width}.png` });
    });
  }
}

test("MCP administration lists servers, tools and OAuth applications", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await stub(page);
  await page.goto("/admin/mcp");

  await expect(page.getByRole("heading", { name: "Máy chủ MCP", level: 1 })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByText("Google Drive")).toBeVisible();
  await expect(page.getByText("Đã kết nối")).toBeVisible();
  await expect(page.getByText("Cần kết nối")).toBeVisible();
  await page.screenshot({ path: "test-results/mcp-admin-list.png", fullPage: true });

  await page.getByRole("button", { name: "Xem công cụ" }).first().click();
  await expect(page.getByText("Tasco Miền Bắc")).toBeVisible();
  await expect(page.getByText("Có thể thay đổi dữ liệu")).toBeVisible();
  await expect(page.getByText("Tên quá dài", { exact: true })).toBeVisible();
  // A tool whose model-facing name cannot fit is visible but not switchable.
  await expect(
    page.getByRole("switch", {
      name: "a_tool_name_that_cannot_fit_inside_the_model_tool_name_limit_at_all",
    }),
  ).toBeDisabled();
  await page.screenshot({ path: "test-results/mcp-admin-tools.png", fullPage: true });
});

test("Adding an MCP server requires the trust acknowledgement", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await stub(page);
  await page.goto("/admin/mcp");

  await page.getByRole("button", { name: "Thêm máy chủ" }).click();
  await page.getByLabel("Tên").fill("Google Drive");
  await page.getByLabel("Mã ngắn").fill("drive");
  await page.getByLabel("Địa chỉ máy chủ").fill("https://drivemcp.googleapis.com/mcp");
  const add = page.getByRole("button", { name: "Thêm", exact: true });
  await expect(add).toBeDisabled();
  await page.screenshot({ path: "test-results/mcp-admin-add.png" });

  await page.getByRole("checkbox").last().check();
  await expect(add).toBeEnabled();
});

test("The composer lists MCP servers with their own connection state", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await stub(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Thêm vào câu hỏi" }).click();
  await page.getByRole("button", { name: "Công cụ MCP" }).click();
  await expect(page.getByRole("switch", { name: "Google Drive" })).toBeVisible();
  // Jira needs the User's own key and Figma expired, so both offer Connect instead of a switch.
  await expect(page.getByRole("switch", { name: "Jira Cloud" })).toHaveCount(0);
  await expect(page.getByText("Cần kết nối lại")).toBeVisible();
  await page.screenshot({ path: "test-results/mcp-composer.png" });
});
