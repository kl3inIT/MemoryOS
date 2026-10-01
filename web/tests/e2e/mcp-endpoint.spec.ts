import { expect, test, type Page } from "@playwright/test";
import type {
  CurrentIdentity,
  GetMcpEndpointConnectionResponse,
  GetMcpEndpointSettingsResponse,
  ListMcpClientGrantsResponse,
} from "../../src/lib/hey-api/types.gen";

const member: CurrentIdentity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  uiLanguage: "vi",
  tenant: { displayName: "Tasco", role: "MEMBER" },
  capabilities: ["MCP_MANAGE", "SEARCH_READ", "CHAT_READ", "CHAT_WRITE"],
  scopedCapabilities: [],
};

const settings: GetMcpEndpointSettingsResponse = {
  configured: true,
  enabled: true,
  revision: 3,
  url: "https://memoryos.tasco.com.vn/mcp",
  chatGpt: {
    clientId: "memoryos-chatgpt",
    // Masked on the page; a real-looking value would trip the repository's secret scan.
    clientSecret: "chatgpt-test-secret",
  },
};

const connection: GetMcpEndpointConnectionResponse = {
  available: true,
  url: "https://memoryos.tasco.com.vn/mcp",
};

const grants: ListMcpClientGrantsResponse = [
  {
    clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata",
    client: "CLAUDE",
    name: "Claude",
    grantedAt: "2026-10-01T02:30:00Z",
  },
  {
    clientId: "memoryos-chatgpt",
    client: "CHATGPT",
    name: "ChatGPT",
    grantedAt: "2026-09-24T08:05:00Z",
  },
];

async function stub(page: Page, endpoint: { on: boolean } = { on: true }) {
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: member }));
  await page.route("**/api/mcp/endpoint", (route) => route.fulfill({ json: settings }));
  await page.route("**/api/mcp/endpoint/connection", (route) =>
    route.fulfill({ json: endpoint.on ? connection : { available: false, url: null } }),
  );
  await page.route("**/api/mcp/grants*", (route) =>
    route.request().method() === "DELETE"
      ? route.fulfill({ status: 204 })
      : route.fulfill({ json: grants }),
  );
  for (const path of ["**/api/chat/sessions?*", "**/api/chat/projects?*", "**/api/chat/models"]) {
    await page.route(path, (route) => route.fulfill({ json: [] }));
  }
}

const OUTPUT = "D:/MemoryOS/output/mem114-mcp";

// These tests boot the app and capture full pages; the default budget is short under parallel load.
test.describe.configure({ timeout: 120_000 });

/** Captures the delivered MemoryOS MCP pages in both themes, desktop and phone, with a Tenant's realistic values. */
for (const theme of ["light", "dark"] as const) {
  for (const width of [1280, 390] as const) {
    test(`MemoryOS MCP pages are captured in ${theme} at ${width}px`, async ({ page }) => {
      await page.addInitScript((value) => localStorage.setItem("memoryos-theme", value), theme);
      await page.emulateMedia({ colorScheme: theme });
      await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
      await stub(page);

      await page.goto("/admin/mcp-endpoint");
      await expect(page.getByRole("heading", { name: "MemoryOS MCP", level: 1 })).toBeVisible({
        timeout: 30_000,
      });
      await expect(page.getByLabel("Địa chỉ MCP")).toHaveValue(settings.url ?? "");
      await page.screenshot({ path: `${OUTPUT}/admin-${theme}-${width}.png`, fullPage: true });

      await page.goto("/settings/mcp");
      await expect(page.getByRole("heading", { name: "Ứng dụng đã cấp quyền" })).toBeVisible();
      await expect(page.getByText("Claude", { exact: true }).last()).toBeVisible();
      await page.screenshot({
        path: `${OUTPUT}/settings-claude-${theme}-${width}.png`,
        fullPage: true,
      });

      await page.getByRole("tab", { name: "ChatGPT" }).click();
      await expect(page.getByText("Trong ChatGPT, mở Settings › Apps")).toBeVisible();
      await page.screenshot({
        path: `${OUTPUT}/settings-chatgpt-${theme}-${width}.png`,
        fullPage: true,
      });

      await page.getByRole("button", { name: "Thu hồi" }).first().click();
      await expect(page.getByRole("alertdialog")).toBeVisible();
      await page.screenshot({ path: `${OUTPUT}/settings-revoke-${theme}-${width}.png` });
    });
  }
}

test("A member revokes Claude by its URL client ID and sees one status line while the endpoint is off", async ({
  page,
}) => {
  await stub(page);
  const revoked: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "DELETE" && request.url().includes("/api/mcp/grants")) {
      revoked.push(new URL(request.url()).searchParams.get("clientId") ?? "");
    }
  });

  await page.goto("/settings/mcp");
  await expect(page.getByLabel("Địa chỉ MCP")).toHaveValue(connection.url ?? "");
  await page.getByRole("button", { name: "Thu hồi" }).first().click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Thu hồi" }).click();
  await expect.poll(() => revoked).toEqual(["https://claude.ai/oauth/mcp-oauth-client-metadata"]);

  await stub(page, { on: false });
  await page.goto("/settings/mcp");
  await expect(page.getByText("Quản trị viên chưa bật MemoryOS MCP.")).toBeVisible();
  await expect(page.getByLabel("Địa chỉ MCP")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Thu hồi" })).toHaveCount(2);
});
