import { expect, test, type Page } from "@playwright/test";
import type {
  ChannelLinkResponse,
  ChannelResponse,
  CurrentIdentity,
} from "../../src/lib/hey-api/types.gen";

const administrator: CurrentIdentity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  uiLanguage: "vi",
  tenant: { displayName: "Tasco", role: "MEMBER" },
  capabilities: ["CHANNELS_MANAGE", "CHAT_READ", "CHAT_WRITE"],
  scopedCapabilities: [],
};

const ZALO = "3c9e1f20-7a4b-4d2e-9f81-6b5a4c3d2e1f";
const HR_ZALO = "8d7c6b5a-4e3f-4a2b-9c1d-0e9f8a7b6c5d";

const channels: ChannelResponse[] = [
  {
    id: "0af099b0-b70f-455d-af56-a05a53bbc7c5",
    provider: "LARK",
    name: "Trợ lý Tasco",
    region: "LARK",
    appId: "cli_aa4102bd7238de15",
    noticeLanguage: "vi",
    enabled: true,
    serviceAccountId: "4c6f0e2a-1b2c-4d5e-8f90-a1b2c3d4e5f6",
    botName: "Tasco Assistant",
    missingScopes: [],
    status: "CONNECTED",
    connectedAt: "2026-10-14T03:58:07Z",
    lastMessageAt: "2026-10-14T06:28:39Z",
    revision: 4,
    createdAt: "2026-10-14T03:40:00Z",
    updatedAt: "2026-10-14T03:41:00Z",
  },
  {
    id: ZALO,
    provider: "ZALO_BOT",
    name: "Trợ lý Tasco trên Zalo",
    transport: "WEBHOOK",
    appId: "3141592653589793238",
    noticeLanguage: "vi",
    enabled: true,
    serviceAccountId: "2b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091",
    botName: "Bot Tasco",
    missingScopes: [],
    status: "CONNECTED",
    connectedAt: "2026-10-15T08:02:11Z",
    lastMessageAt: "2026-10-15T09:41:27Z",
    revision: 3,
    createdAt: "2026-10-15T07:55:00Z",
    updatedAt: "2026-10-15T08:02:00Z",
  },
  {
    id: HR_ZALO,
    provider: "ZALO_BOT",
    name: "Hỏi đáp Nhân sự",
    transport: "WEBHOOK",
    appId: "2718281828459045235",
    noticeLanguage: "vi",
    enabled: true,
    serviceAccountId: "5e6f7081-92a3-4b4c-8d5e-6f708192a3b4",
    botName: "Bot Nhan Su",
    missingScopes: [],
    status: "FAILED",
    failureCode: "CHANNEL_WEBHOOK_UNREACHABLE",
    failedAt: "2026-10-15T09:12:40Z",
    revision: 2,
    createdAt: "2026-10-15T09:10:00Z",
    updatedAt: "2026-10-15T09:12:40Z",
  },
];

async function stubAdministration(page: Page) {
  const calls = {
    transport: [] as { id: string; body: unknown; ifMatch: string | undefined }[],
    tested: [] as unknown[],
    created: [] as unknown[],
  };
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: administrator }));
  await page.route("**/api/channels", (route) => route.fulfill({ json: channels }));
  await page.route("**/api/channels/*/transport", async (route) => {
    const id = route.request().url().split("/").at(-2) ?? "";
    const body = route.request().postDataJSON() as { transport: "WEBHOOK" | "POLLING" };
    calls.transport.push({ id, body, ifMatch: route.request().headers()["if-match"] });
    const channel = channels.find((candidate) => candidate.id === id);
    await route.fulfill({ json: { ...channel, transport: body.transport } });
  });
  await page.route("**/api/channels/zalo/test", async (route) => {
    calls.tested.push(route.request().postDataJSON());
    await route.fulfill({ json: { botName: "Bot Kho Van", missingScopes: [] } });
  });
  await page.route("**/api/channels/zalo", async (route) => {
    calls.created.push(route.request().postDataJSON());
    await route.fulfill({ status: 201, json: { ...channels[1], id: "new", name: "Kho vận" } });
  });
  for (const path of ["**/api/chat/sessions?*", "**/api/chat/projects?*", "**/api/chat/models"]) {
    await page.route(path, (route) => route.fulfill({ json: [] }));
  }
  return calls;
}

/** Everything Settings › Chat reads, so the page shows its sections as a member sees them. */
async function stubChatSettings(page: Page, links: ChannelLinkResponse[]) {
  const calls = { codes: [] as unknown[], unlinked: [] as string[] };
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: administrator }));
  await page.route("**/api/chat/**", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/chat/preferences", (route) =>
    route.fulfill({
      json: {
        workRole: "",
        personalPreferences: "",
        defaultModelId: null,
        temperatureDefault: null,
        reasoningEffortDefault: "OFF",
        autoScroll: true,
        displayName: null,
        email: null,
      },
    }),
  );
  await page.route("**/api/chat/memory", (route) =>
    route.fulfill({ json: { content: "", enabled: true, revision: 0, maxLength: 4000 } }),
  );
  await page.route("**/api/chat/retention", (route) => route.fulfill({ json: { days: null } }));
  await page.route("**/api/chat/prompt-shortcuts/preferences", (route) =>
    route.fulfill({ json: { enabled: true } }),
  );
  await page.route("**/api/chat/voice", (route) =>
    route.fulfill({ json: { sttAvailable: false, ttsAvailable: false } }),
  );
  await page.route("**/api/channel-links", async (route) => {
    if (route.request().method() === "POST") {
      calls.codes.push(route.request().postDataJSON());
      await route.fulfill({
        status: 201,
        json: { code: "K7MQ4XRP", expiresAt: new Date(Date.now() + 10 * 60_000).toISOString() },
      });
      return;
    }
    await route.fulfill({ json: links });
  });
  await page.route("**/api/channel-links/*", async (route) => {
    const id = route.request().url().split("/").at(-1) ?? "";
    calls.unlinked.push(id);
    const index = links.findIndex((link) => link.connectionId === id);
    if (index >= 0) links[index] = { ...links[index], linkedAt: undefined };
    await route.fulfill({ status: 204 });
  });
  return calls;
}

const memberLinks = (): ChannelLinkResponse[] => [
  { connectionId: ZALO, botName: "Trợ lý Tasco trên Zalo", provider: "ZALO_BOT" },
  {
    connectionId: HR_ZALO,
    botName: "Hỏi đáp Nhân sự",
    provider: "ZALO_BOT",
    linkedAt: "2026-10-15T09:20:00Z",
  },
];

test.describe.configure({ timeout: 120_000 });

test("a Zalo bot is added with its token and the way it receives", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const calls = await stubAdministration(page);
  await page.goto("/admin/channels");

  await expect(page.getByText("Đang trả lời trên Zalo")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("Zalo không gọi được tới webhook")).toBeVisible();

  await page.getByRole("button", { name: "Thêm bot" }).click();
  const dialog = page.getByRole("dialog", { name: "Thêm bot" });
  await dialog.getByRole("radio", { name: "Zalo" }).click();
  await expect(dialog.getByText("Chuẩn bị bot trên Zalo")).toBeVisible();
  await dialog.getByRole("textbox", { name: "Tên bot" }).fill("Kho vận");
  await expect(dialog.getByRole("button", { name: "Kiểm tra và lưu" })).toBeDisabled();
  await dialog.getByLabel("Token", { exact: true }).fill("1234567890:abc-xyz");
  await dialog.getByRole("radio", { name: "Polling" }).click();
  await dialog.getByRole("button", { name: "Kiểm tra và lưu" }).click();

  await expect
    .poll(() => calls.created)
    .toEqual([
      { name: "Kho vận", token: "1234567890:abc-xyz", noticeLanguage: "vi", transport: "POLLING" },
    ]);
  await expect(page.getByRole("dialog")).toBeHidden();
});

test("a Zalo bot's page shows its facts and its way of receiving saves on change", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const calls = await stubAdministration(page);
  await page.goto(`/admin/channels/${ZALO}`);

  await expect(page.getByRole("heading", { name: "Trợ lý Tasco trên Zalo", level: 1 })).toBeVisible(
    { timeout: 30_000 },
  );
  await expect(page.getByText("Bot Tasco")).toBeVisible();
  await expect(page.getByText("3141592653589793238")).toBeVisible();
  await expect(page.getByText("Khu vực")).toHaveCount(0);

  await page.getByRole("combobox", { name: "Cách nhận tin" }).click();
  await page.getByRole("option", { name: "Polling" }).click();
  await expect
    .poll(() => calls.transport)
    .toEqual([{ id: ZALO, body: { transport: "POLLING" }, ifMatch: '"3"' }]);

  await page.getByRole("button", { name: "Thao tác với Trợ lý Tasco trên Zalo" }).click();
  await expect(page.getByRole("menuitem", { name: "Thay token" })).toBeVisible();
});

test("a member gets a code for a Zalo bot, sees it link, and unlinks another", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const links = memberLinks();
  const calls = await stubChatSettings(page, links);
  await page.goto("/settings/chat");

  const section = page.getByRole("region", { name: "Liên kết Zalo" });
  await expect(section).toBeVisible({ timeout: 30_000 });
  await expect(section.getByText("Chưa liên kết")).toBeVisible();

  await section.getByRole("button", { name: "Lấy mã" }).click();
  const dialog = page.getByRole("dialog", { name: "Mã liên kết" });
  await expect(dialog.getByRole("status", { name: "Mã liên kết" })).toHaveText("K7MQ4XRP");
  await expect(dialog.getByText(/Hết hạn sau \d+:\d{2}/)).toBeVisible();
  expect(calls.codes).toEqual([{ connectionId: ZALO }]);

  // The member sends the code in Zalo; the bot takes it and the dialog closes on the next read.
  links[0] = { ...links[0], linkedAt: "2026-10-15T10:02:00Z" };
  await expect(dialog).toBeHidden({ timeout: 15_000 });
  await expect(section.getByText("Chưa liên kết")).toHaveCount(0);

  await section.getByRole("button", { name: "Bỏ liên kết" }).last().click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Bỏ liên kết" }).click();
  await expect.poll(() => calls.unlinked).toEqual([HR_ZALO]);
});

const OUTPUT = "test-results/mem263-web";

/** The delivered pages in both themes, at desktop and phone width. */
for (const theme of ["light", "dark"] as const) {
  for (const width of [1280, 390] as const) {
    test(`the Zalo pages are captured in ${theme} at ${width}px`, async ({ page }) => {
      await page.addInitScript((value) => localStorage.setItem("memoryos-theme", value), theme);
      await page.emulateMedia({ colorScheme: theme });
      await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
      await stubAdministration(page);

      await page.goto("/admin/channels");
      await expect(page.getByText("Đang trả lời trên Zalo")).toBeVisible({ timeout: 30_000 });
      await page.screenshot({ path: `${OUTPUT}/list-${theme}-${width}.png`, fullPage: true });

      await page.getByRole("button", { name: "Thêm bot" }).click();
      const dialog = page.getByRole("dialog", { name: "Thêm bot" });
      await dialog.getByRole("radio", { name: "Zalo" }).click();
      await expect(dialog.getByText("Chuẩn bị bot trên Zalo")).toBeVisible();
      await page.screenshot({ path: `${OUTPUT}/add-zalo-${theme}-${width}.png`, fullPage: true });
      await page.keyboard.press("Escape");

      await page.goto(`/admin/channels/${HR_ZALO}`);
      await expect(page.getByRole("heading", { name: "Hỏi đáp Nhân sự", level: 1 })).toBeVisible({
        timeout: 30_000,
      });
      await page.screenshot({ path: `${OUTPUT}/detail-${theme}-${width}.png`, fullPage: true });

      await page.unrouteAll({ behavior: "ignoreErrors" });
      await stubChatSettings(page, memberLinks());
      await page.goto("/settings/chat");
      const section = page.getByRole("region", { name: "Liên kết Zalo" });
      await expect(section).toBeVisible({ timeout: 30_000 });
      await section.scrollIntoViewIfNeeded();
      await section.screenshot({ path: `${OUTPUT}/links-${theme}-${width}.png` });

      await section.getByRole("button", { name: "Lấy mã" }).click();
      await expect(page.getByRole("dialog", { name: "Mã liên kết" })).toBeVisible();
      await page.screenshot({ path: `${OUTPUT}/code-${theme}-${width}.png` });
    });
  }
}
