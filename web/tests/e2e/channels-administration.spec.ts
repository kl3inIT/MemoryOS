import { expect, test, type Page } from "@playwright/test";
import type { ChannelResponse, CurrentIdentity } from "../../src/lib/hey-api/types.gen";

const administrator: CurrentIdentity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  uiLanguage: "vi",
  tenant: { displayName: "Tasco", role: "MEMBER" },
  capabilities: ["CHANNELS_MANAGE", "CHAT_READ", "CHAT_WRITE"],
  scopedCapabilities: [],
};

const HELPDESK = "0af099b0-b70f-455d-af56-a05a53bbc7c5";
const FINANCE = "5d1f6c2e-8a1b-4c3d-9e4f-0a1b2c3d4e5f";

const channels: ChannelResponse[] = [
  {
    id: HELPDESK,
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
    id: FINANCE,
    provider: "LARK",
    name: "Hỏi đáp Tài chính",
    region: "LARK",
    appId: "cli_b7c3e9f1a2d4c6e8",
    noticeLanguage: "vi",
    enabled: true,
    serviceAccountId: "9e8d7c6b-5a49-4382-9a1b-c0d1e2f3a4b5",
    botName: "Finance Q&A",
    missingScopes: ["contact:user.email:readonly"],
    status: "CONNECTED",
    connectedAt: "2026-10-13T09:12:00Z",
    lastMessageAt: "2026-10-14T02:05:00Z",
    revision: 2,
    createdAt: "2026-10-13T09:00:00Z",
    updatedAt: "2026-10-13T09:10:00Z",
  },
  {
    id: "6e2a9c4b-3d1f-4a8e-b7c6-d5e4f3a2b1c0",
    provider: "LARK",
    name: "Bot Nhân sự",
    region: "FEISHU",
    appId: "cli_c9d8e7f6a5b4c3d2",
    noticeLanguage: "en",
    enabled: false,
    serviceAccountId: "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
    botName: "HR Helper",
    missingScopes: [],
    status: "OFF",
    revision: 1,
    createdAt: "2026-10-12T08:00:00Z",
    updatedAt: "2026-10-12T08:00:00Z",
  },
];

async function stub(page: Page, list: ChannelResponse[] = channels) {
  const switched: { id: string; enabled: boolean; ifMatch: string | undefined }[] = [];
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: administrator }));
  await page.route("**/api/channels", (route) => route.fulfill({ json: list }));
  await page.route("**/api/channels/*/enabled", async (route) => {
    const id = route.request().url().split("/").at(-2) ?? "";
    const body = route.request().postDataJSON() as { enabled: boolean };
    switched.push({ id, enabled: body.enabled, ifMatch: route.request().headers()["if-match"] });
    const channel = list.find((candidate) => candidate.id === id);
    await route.fulfill({ json: { ...channel, enabled: body.enabled } });
  });
  for (const path of ["**/api/chat/sessions?*", "**/api/chat/projects?*", "**/api/chat/models"]) {
    await page.route(path, (route) => route.fulfill({ json: [] }));
  }
  return switched;
}

test.describe.configure({ timeout: 120_000 });

test("the bots are listed with one line each, a switch saves on change and a bot opens its page", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const switched = await stub(page);
  await page.goto("/admin/channels");

  await expect(page.getByRole("heading", { name: "Bot chat", level: 1 })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("Đang trả lời trên Lark")).toBeVisible();
  await expect(page.getByText("Thiếu 1 quyền trên Lark")).toBeVisible();
  await expect(page.getByText("Đang tắt")).toBeVisible();

  await page.getByRole("switch", { name: "Bật Bot Nhân sự" }).click();
  await expect.poll(() => switched).toEqual([
    { id: "6e2a9c4b-3d1f-4a8e-b7c6-d5e4f3a2b1c0", enabled: true, ifMatch: '"1"' },
  ]);

  await page.getByRole("link", { name: /Hỏi đáp Tài chính/ }).click();
  await expect(page.getByRole("heading", { name: "Hỏi đáp Tài chính", level: 1 })).toBeVisible();
  await expect(page.getByText("Finance Q&A")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Quyền Lark còn thiếu" })).toBeVisible();
  await expect(page.getByText("contact:user.email:readonly")).toBeVisible();
});

test("adding a bot opens the dialog beside the console steps and waits for the App Secret", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await stub(page, []);
  await page.goto("/admin/channels");

  await expect(page.getByText("Chưa có bot nào.")).toBeVisible({ timeout: 30_000 });
  await page.getByRole("button", { name: "Thêm bot Lark" }).click();
  const dialog = page.getByRole("dialog", { name: "Thêm bot Lark" });
  await expect(dialog.getByText("im.message.receive_v1")).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Kiểm tra và lưu" })).toBeDisabled();
  await dialog.getByLabel("App Secret").fill("secret");
  await expect(dialog.getByRole("button", { name: "Kiểm tra và lưu" })).toBeEnabled();
});

const OUTPUT = "test-results/mem246-channels";

/** The delivered pages in both themes, at desktop and phone width. */
for (const theme of ["light", "dark"] as const) {
  for (const width of [1280, 390] as const) {
    test(`the bots' pages are captured in ${theme} at ${width}px`, async ({ page }) => {
      await page.addInitScript((value) => localStorage.setItem("memoryos-theme", value), theme);
      await page.emulateMedia({ colorScheme: theme });
      await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
      await stub(page);

      await page.goto("/admin/channels");
      await expect(page.getByRole("heading", { name: "Bot chat", level: 1 })).toBeAttached({ timeout: 30_000 });
      await page.screenshot({ path: `${OUTPUT}/list-${theme}-${width}.png`, fullPage: true });

      await page.goto(`/admin/channels/${FINANCE}`);
      await expect(page.getByRole("heading", { name: "Hỏi đáp Tài chính", level: 1 })).toBeAttached();
      await page.screenshot({ path: `${OUTPUT}/detail-${theme}-${width}.png`, fullPage: true });

      await page.goto("/admin/channels");
      await page.getByRole("button", { name: "Thêm bot Lark" }).click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await page.screenshot({ path: `${OUTPUT}/dialog-${theme}-${width}.png`, fullPage: true });
    });
  }
}
