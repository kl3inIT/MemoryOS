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

const PAIRING = "5f1d2c3b-4a59-4e68-8b7a-9c0d1e2f3a4b";
const LOGGED_OUT = "6a2e3d4c-5b6a-4f79-9c8b-0d1e2f3a4b5c";
const ANSWERING = "7b3f4e5d-6c7b-4a8a-8d9c-1e2f3a4b5c6d";

const whatsapp = (overrides: Partial<ChannelResponse>): ChannelResponse => ({
  id: ANSWERING,
  provider: "WHATSAPP",
  name: "Trợ lý Tasco trên WhatsApp",
  appId: "84912345678",
  noticeLanguage: "vi",
  enabled: true,
  publicAccess: false,
  serviceAccountId: "2b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091",
  missingScopes: [],
  status: "CONNECTED",
  revision: 3,
  createdAt: "2026-10-19T07:55:00Z",
  updatedAt: "2026-10-19T08:02:00Z",
  ...overrides,
});

const channels = (): ChannelResponse[] => [
  whatsapp({
    botName: "Tasco",
    connectedAt: "2026-10-19T08:02:11Z",
    lastMessageAt: "2026-10-19T09:41:27Z",
  }),
  whatsapp({
    id: PAIRING,
    name: "Hỏi đáp Nhân sự",
    appId: "84987654321",
    status: "PAIRING",
    revision: 1,
  }),
  whatsapp({
    id: LOGGED_OUT,
    name: "Kho vận",
    appId: "84901112233",
    status: "FAILED",
    failureCode: "CHANNEL_WHATSAPP_LOGGED_OUT",
    failedAt: "2026-10-19T09:12:40Z",
    revision: 5,
  }),
];

async function stubAdministration(page: Page) {
  const calls = {
    created: [] as unknown[],
    relinked: [] as { id: string; ifMatch: string | undefined }[],
  };
  const listed = channels();
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: administrator }));
  await page.route("**/api/channels", (route) => route.fulfill({ json: listed }));
  await page.route("**/api/channels/whatsapp", async (route) => {
    calls.created.push(route.request().postDataJSON());
    await route.fulfill({ status: 201, json: { ...listed[0], id: "new", name: "Kho vận 2" } });
  });
  await page.route("**/api/channels/*/pairing", async (route) => {
    const id = route.request().url().split("/").at(-2) ?? "";
    if (route.request().method() === "POST") {
      calls.relinked.push({ id, ifMatch: route.request().headers()["if-match"] });
      const index = listed.findIndex((channel) => channel.id === id);
      listed[index] = { ...listed[index], status: "PAIRING", failureCode: undefined, revision: 6 };
      await route.fulfill({ json: listed[index] });
      return;
    }
    if (id !== PAIRING) {
      await route.fulfill({ status: 204 });
      return;
    }
    await route.fulfill({
      json: {
        code: "2@Wd8n3Kq7c0VbX1rYz5LmA9pQ,7tHs4FgJ2kE6uN8oR1iC3vB5xZ0aD,MemoryOS",
        expiresAt: new Date(Date.now() + 20_000).toISOString(),
      },
    });
  });
  await page.route("**/api/channels/*/groups", (route) => route.fulfill({ json: [] }));
  for (const path of [
    "**/api/chat/sessions?*",
    "**/api/chat/projects?*",
    "**/api/chat/models",
    "**/api/chat/personas*",
  ]) {
    await page.route(path, (route) => route.fulfill({ json: [] }));
  }
  return calls;
}

/** Everything Settings › Chat reads, so the page shows its sections as a member sees them. */
async function stubChatSettings(page: Page, links: ChannelLinkResponse[]) {
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
  await page.route("**/api/channel-links", (route) => route.fulfill({ json: links }));
}

test.describe.configure({ timeout: 120_000 });

test("a WhatsApp bot is added by its number, with no secret", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const calls = await stubAdministration(page);
  await page.goto("/admin/channels");

  await expect(page.getByText("Đang trả lời trên WhatsApp")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("Chờ quét mã QR")).toBeVisible();
  await expect(page.getByText("WhatsApp đã đăng xuất thiết bị")).toBeVisible();

  await page.getByRole("button", { name: "Thêm bot" }).click();
  const dialog = page.getByRole("dialog", { name: "Thêm bot" });
  await dialog.getByRole("radio", { name: "WhatsApp" }).click();
  await expect(dialog.getByText("Chuẩn bị số WhatsApp")).toBeVisible();
  await dialog.getByRole("textbox", { name: "Tên bot" }).fill("Kho vận 2");
  await dialog.getByRole("textbox", { name: "Số điện thoại của bot" }).fill("0912");
  await dialog.getByRole("button", { name: "Thêm bot" }).click();
  await expect(dialog.getByText(/theo dạng quốc tế/)).toBeVisible();
  expect(calls.created).toEqual([]);

  await dialog.getByRole("textbox", { name: "Số điện thoại của bot" }).fill("+84 912 000 111");
  await dialog.getByRole("button", { name: "Thêm bot" }).click();
  await expect
    .poll(() => calls.created)
    .toEqual([{ name: "Kho vận 2", phone: "+84 912 000 111", noticeLanguage: "vi" }]);
  await expect(page.getByRole("dialog")).toBeHidden();
});

test("a WhatsApp bot being linked shows its QR code, and one logged out is linked again", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const calls = await stubAdministration(page);
  await page.goto(`/admin/channels/${PAIRING}`);

  await expect(page.getByRole("heading", { name: "Hỏi đáp Nhân sự", level: 1 })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByRole("img", { name: "Mã QR liên kết WhatsApp" })).toBeVisible();
  await expect(page.getByText("Mở WhatsApp trên điện thoại của số +84987654321.")).toBeVisible();
  await expect(page.getByText("+84987654321", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Thao tác với Hỏi đáp Nhân sự" }).click();
  await expect(page.getByRole("menuitem", { name: /Thay/ })).toHaveCount(0);
  await expect(page.getByRole("menuitem", { name: "Liên kết tài khoản của bạn" })).toBeVisible();
  await page.keyboard.press("Escape");

  await page.goto(`/admin/channels/${LOGGED_OUT}`);
  const section = page.getByRole("region", { name: "Liên kết WhatsApp" });
  await expect(section).toBeVisible({ timeout: 30_000 });
  await section.getByRole("button", { name: "Liên kết lại" }).click();
  await expect.poll(() => calls.relinked).toEqual([{ id: LOGGED_OUT, ifMatch: '"5"' }]);
  await expect(section.getByRole("button", { name: "Liên kết lại" })).toHaveCount(0);
});

test("a member's WhatsApp link is listed beside Zalo's", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await stubChatSettings(page, [
    { connectionId: ANSWERING, botName: "Trợ lý Tasco trên WhatsApp", provider: "WHATSAPP" },
  ]);
  await page.goto("/settings/chat");
  const section = page.getByRole("region", { name: "Liên kết tài khoản chat" });
  await expect(section).toBeVisible({ timeout: 30_000 });
  await expect(section.getByText("Trợ lý Tasco trên WhatsApp")).toBeVisible();
  await expect(section.getByRole("button", { name: "Lấy mã" })).toBeVisible();
});

const OUTPUT = "test-results/mem283-web";

/** The delivered pages in both themes, at desktop and phone width. */
for (const theme of ["light", "dark"] as const) {
  for (const width of [1280, 390] as const) {
    test(`the WhatsApp pages are captured in ${theme} at ${width}px`, async ({ page }) => {
      await page.addInitScript((value) => localStorage.setItem("memoryos-theme", value), theme);
      await page.emulateMedia({ colorScheme: theme });
      await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
      await stubAdministration(page);

      await page.goto("/admin/channels");
      await expect(page.getByText("Đang trả lời trên WhatsApp")).toBeVisible({ timeout: 30_000 });
      await page.screenshot({ path: `${OUTPUT}/list-${theme}-${width}.png`, fullPage: true });

      await page.getByRole("button", { name: "Thêm bot" }).click();
      const dialog = page.getByRole("dialog", { name: "Thêm bot" });
      await dialog.getByRole("radio", { name: "WhatsApp" }).click();
      await expect(dialog.getByText("Chuẩn bị số WhatsApp")).toBeVisible();
      await page.screenshot({ path: `${OUTPUT}/add-${theme}-${width}.png`, fullPage: true });
      await page.keyboard.press("Escape");

      await page.goto(`/admin/channels/${PAIRING}`);
      await expect(page.getByRole("img", { name: "Mã QR liên kết WhatsApp" })).toBeVisible({
        timeout: 30_000,
      });
      await page.screenshot({ path: `${OUTPUT}/pairing-${theme}-${width}.png`, fullPage: true });

      await page.goto(`/admin/channels/${LOGGED_OUT}`);
      await expect(page.getByRole("button", { name: "Liên kết lại" })).toBeVisible({
        timeout: 30_000,
      });
      await page.screenshot({ path: `${OUTPUT}/relink-${theme}-${width}.png`, fullPage: true });
    });
  }
}
