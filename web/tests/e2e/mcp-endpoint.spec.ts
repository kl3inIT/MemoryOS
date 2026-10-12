import { expect, test, type Page } from "@playwright/test";
import type {
  CurrentIdentity,
  GetMcpEndpointConnectionResponse,
  GetMcpEndpointSettingsResponse,
  ListMcpClientGrantsResponse,
  McpEndpointCallResponse,
  McpEndpointInsightsResponse,
  McpTrustedAppListResponse,
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
};

const connection: GetMcpEndpointConnectionResponse = {
  available: true,
  url: "https://memoryos.tasco.com.vn/mcp",
  apps: ["CLAUDE", "CHATGPT", "CUSTOM"],
};

const grants: ListMcpClientGrantsResponse = [
  {
    clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata",
    client: "CLAUDE",
    name: "Claude",
    grantedAt: "2026-10-01T02:30:00Z",
  },
  {
    clientId: "https://chatgpt.com/oauth/client.json",
    client: "CHATGPT",
    name: "ChatGPT",
    grantedAt: "2026-09-24T08:05:00Z",
  },
];

const trustedApps: McpTrustedAppListResponse = {
  manageable: true,
  apps: [
    {
      id: "5d0f6f1e-1f4b-4c39-9d0e-0c1f5e7a0001",
      preset: "CLAUDE",
      name: "Claude",
      clientIdHosts: ["claude.ai", "claude.com"],
      documentHosts: ["claude.ai", "claude.com", "localhost", "127.0.0.1"],
      enabled: true,
      builtIn: true,
      revision: 1,
    },
    {
      id: "5d0f6f1e-1f4b-4c39-9d0e-0c1f5e7a0002",
      preset: "CHATGPT",
      name: "ChatGPT",
      clientIdHosts: ["chatgpt.com"],
      documentHosts: ["chatgpt.com", "persistent.oaistatic.com"],
      enabled: true,
      builtIn: true,
      revision: 2,
    },
    {
      id: "5d0f6f1e-1f4b-4c39-9d0e-0c1f5e7a0003",
      preset: "CUSTOM",
      name: "Trợ lý pháp chế Tasco",
      clientIdHosts: ["agents.tasco.com.vn"],
      documentHosts: ["agents.tasco.com.vn", "login.tasco.com.vn"],
      enabled: false,
      builtIn: false,
      revision: 4,
    },
  ],
};

const people = [
  ["Trần Thu Hà", "ha.tt@tasco.com.vn"],
  ["Nguyễn Minh Quân", "quan.nm@tasco.com.vn"],
  ["Lê Hoàng Yến", "yen.lh@tasco.com.vn"],
  ["Phạm Đức Anh", "anh.pd@tasco.com.vn"],
  ["Võ Thị Mai", "mai.vt@tasco.com.vn"],
] as const;
const apps = [
  ["CLAUDE", "Claude", "https://claude.ai/oauth/mcp-oauth-client-metadata"],
  ["CHATGPT", "ChatGPT", "https://chatgpt.com/oauth/client.json"],
  ["OTHER", "agents.tasco.com.vn", "https://agents.tasco.com.vn/oauth/client.json"],
] as const;
const tools = ["search", "fetch", "search_with_filters"] as const;
const outcomes: McpEndpointCallResponse["outcome"][] = [
  "SUCCESS",
  "SUCCESS",
  "SUCCESS",
  "SUCCESS",
  "RATE_LIMITED",
  "SUCCESS",
  "SUCCESS",
  "FAILED",
  "SUCCESS",
  "REFUSED",
  "SUCCESS",
  "SUCCESS",
];

/** A morning of calls, newest first, minutes apart, from several people through each app. */
function activity(now = Date.now()): McpEndpointCallResponse[] {
  return outcomes.map((outcome, index) => {
    const [actorName, actorEmail] = people[(index * 3) % people.length];
    const [client, clientName] = apps[index % 5 === 4 ? 2 : index % 2];
    return {
      id: `c0000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`,
      occurredAt: new Date(now - (index * 7 + 2) * 60_000).toISOString(),
      actorId: `10000000-0000-4000-8000-${String((index * 3) % people.length).padStart(12, "0")}`,
      actorName,
      actorEmail,
      client,
      clientName,
      tool: tools[index % 3],
      outcome,
    };
  });
}

function insights(days: number, now = new Date()): McpEndpointInsightsResponse {
  const end = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  const pattern = [184, 212, 96, 41, 228, 251, 197, 176, 0, 35, 203, 240, 219, 188];
  const daily = Array.from({ length: days }, (_, index) => ({
    day: new Date(end - (days - 1 - index) * 86_400_000).toISOString().slice(0, 10),
    calls: pattern[index % pattern.length],
    people: Math.round(pattern[index % pattern.length] / 9),
  })).filter((day) => day.calls > 0);
  const calls = daily.reduce((total, day) => total + day.calls, 0);
  return {
    days,
    calls,
    people: 37,
    failed: 4,
    rateLimited: 2,
    apps: [
      { key: apps[0][2], name: "Claude", calls: Math.round(calls * 0.64), people: 29 },
      { key: apps[1][2], name: "ChatGPT", calls: Math.round(calls * 0.31), people: 14 },
      { key: apps[2][2], name: "agents.tasco.com.vn", calls: Math.round(calls * 0.05), people: 3 },
    ],
    tools: [
      { key: "search", name: "search", calls: Math.round(calls * 0.55), people: 36 },
      { key: "fetch", name: "fetch", calls: Math.round(calls * 0.33), people: 31 },
      {
        key: "search_with_filters",
        name: "search_with_filters",
        calls: Math.round(calls * 0.12),
        people: 12,
      },
    ],
    daily,
  };
}

async function stub(page: Page, endpoint: { on: boolean } = { on: true }) {
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: member }));
  await page.route("**/api/mcp/endpoint", (route) => route.fulfill({ json: settings }));
  await page.route("**/api/mcp/endpoint/connection", (route) =>
    route.fulfill({ json: endpoint.on ? connection : { available: false, url: null, apps: [] } }),
  );
  await page.route("**/api/mcp/endpoint/trusted-apps*", (route) =>
    route.fulfill({ json: trustedApps }),
  );
  await page.route("**/api/mcp/endpoint/activity*", (route) =>
    route.fulfill({ json: { calls: activity(), next: "older" } }),
  );
  await page.route("**/api/mcp/endpoint/insights*", (route) =>
    route.fulfill({
      json: insights(Number(new URL(route.request().url()).searchParams.get("days") ?? 7)),
    }),
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

const OUTPUT = "D:/MemoryOS/output/mem209-mcp";

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
      // Below `md` the shell bar names the page and this heading is off screen, so the wait is for its presence.
      await expect(page.getByRole("heading", { name: "MemoryOS MCP", level: 1 })).toBeAttached({
        timeout: 30_000,
      });
      await expect(page.getByLabel("Địa chỉ MCP", { exact: true })).toHaveValue(settings.url ?? "");
      await expect(page.getByText("Trợ lý pháp chế Tasco")).toBeVisible();
      await page.screenshot({ path: `${OUTPUT}/admin-${theme}-${width}.png`, fullPage: true });

      await page.getByRole("button", { name: "Thêm ứng dụng" }).click();
      await page.getByLabel("Tên", { exact: true }).fill("Copilot Studio");
      await page.getByLabel("Tên miền của client ID").fill("copilot.tasco.com.vn, bad_host");
      await expect(page.getByText("“bad_host” không phải tên miền.")).toBeVisible();
      await page.screenshot({ path: `${OUTPUT}/admin-add-${theme}-${width}.png` });
      await page.keyboard.press("Escape");

      await page.getByRole("switch", { name: "ChatGPT" }).click();
      await expect(page.getByRole("alertdialog")).toBeVisible();
      await page.screenshot({ path: `${OUTPUT}/admin-stop-${theme}-${width}.png` });
      await page.keyboard.press("Escape");

      // The app shell scrolls inside its own pane, so a full-page capture is only as tall as the window.
      await page.setViewportSize({ width, height: 1800 });
      await page.getByRole("tab", { name: "Hoạt động" }).click();
      await expect(page.getByRole("table", { name: "Hoạt động MemoryOS MCP" })).toBeVisible();
      await expect(page.getByText("Vượt giới hạn").last()).toBeVisible();
      await page.screenshot({ path: `${OUTPUT}/activity-${theme}-${width}.png`, fullPage: true });

      await page.getByRole("tab", { name: "Thống kê" }).click();
      await expect(page.getByText("Lượt gọi theo ngày")).toBeVisible();
      await expect(page.locator(".recharts-bar-rectangle").first()).toBeVisible();
      // The bars grow in; a capture taken at once shows them half drawn.
      await page.waitForTimeout(1_500);
      await page.screenshot({ path: `${OUTPUT}/insights-${theme}-${width}.png`, fullPage: true });

      await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
      await page.goto("/settings/mcp");
      await expect(page.getByRole("heading", { name: "Ứng dụng đã cấp quyền" })).toBeVisible();
      await expect(page.getByText("Claude", { exact: true }).last()).toBeVisible();
      await page.screenshot({
        path: `${OUTPUT}/settings-claude-${theme}-${width}.png`,
        fullPage: true,
      });

      await page.getByRole("tab", { name: "Ứng dụng khác" }).click();
      await expect(page.getByText("Trong ứng dụng, thêm một máy chủ MCP từ xa.")).toBeVisible();
      // The selected tab's pill fades in; a capture taken at once shows it half drawn.
      await page.waitForTimeout(400);
      await page.screenshot({
        path: `${OUTPUT}/settings-other-${theme}-${width}.png`,
        fullPage: true,
      });
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
  await expect(page.getByLabel("Địa chỉ MCP", { exact: true })).toHaveValue(connection.url ?? "");
  await page.getByRole("button", { name: "Thu hồi" }).first().click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Thu hồi" }).click();
  await expect.poll(() => revoked).toEqual(["https://claude.ai/oauth/mcp-oauth-client-metadata"]);

  await stub(page, { on: false });
  await page.goto("/settings/mcp");
  await expect(page.getByText("Quản trị viên chưa bật MemoryOS MCP.")).toBeVisible();
  await expect(page.getByLabel("Địa chỉ MCP", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Thu hồi" })).toHaveCount(2);
});

test("An administrator filters the activity by app and the address keeps it", async ({ page }) => {
  await stub(page);
  const requested: URL[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/mcp/endpoint/activity"))
      requested.push(new URL(request.url()));
  });

  await page.goto("/admin/mcp-endpoint?tab=activity");
  await expect(page.getByRole("table", { name: "Hoạt động MemoryOS MCP" })).toBeVisible({
    timeout: 30_000,
  });
  await page.getByRole("combobox", { name: "Ứng dụng" }).click();
  await page.getByRole("option", { name: "ChatGPT" }).click();
  await expect.poll(() => requested.at(-1)?.searchParams.get("client")).toBe("CHATGPT");
  await expect(page).toHaveURL(/tab=activity/);
  await expect(page).toHaveURL(/client=CHATGPT/);
});
