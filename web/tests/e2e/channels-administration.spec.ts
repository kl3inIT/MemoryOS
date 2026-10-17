import { expect, test, type Page } from "@playwright/test";
import type {
  ChannelGroupResponse,
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

const HELPDESK = "0af099b0-b70f-455d-af56-a05a53bbc7c5";
const TAX = "70000000-0000-4000-8000-000000000011";
const HR = "70000000-0000-4000-8000-000000000012";
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

const minutesAgo = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

/** The group chats the finance bot has answered in; the Zalo-less Lark names come from the platform. */
const financeGroups = (): ChannelGroupResponse[] => [
  {
    id: "oc_a1b2c3d4e5f60718293a4b5c6d7e8f90",
    name: "Phòng Kế toán",
    firstMessageAt: "2026-10-10T02:00:00Z",
    lastMessageAt: minutesAgo(10),
    assistant: { id: TAX, name: "Kế toán thuế" },
  },
  {
    id: "oc_0f9e8d7c6b5a49382716a5b4c3d2e1f0",
    name: "Phòng Nhân sự",
    firstMessageAt: "2026-10-11T02:00:00Z",
    lastMessageAt: minutesAgo(26 * 60),
    assistant: { id: HR, name: "Chính sách nhân sự" },
  },
  {
    id: "oc_5f1e2d3c4b5a69788796a5b4c3d2e1f0",
    name: "Ban Giám đốc",
    firstMessageAt: "2026-10-12T02:00:00Z",
    lastMessageAt: minutesAgo(3 * 24 * 60),
  },
];

const persona = (id: string, name: string, builtin = false) => ({
  id,
  revision: 1,
  builtin,
  name,
  permissions: { edit: !builtin },
});

async function stub(page: Page, list: ChannelResponse[] = channels) {
  const switched: { id: string; enabled: boolean; ifMatch: string | undefined }[] = [];
  const groups = financeGroups();
  const chosen: { groupId: string; personaId: string | null }[] = [];
  await page.route("**/api/chat/personas?*", (route) =>
    route.fulfill({
      json: [
        persona("70000000-0000-4000-8000-000000000000", "MemoryOS", true),
        persona(TAX, "Kế toán thuế"),
        persona(HR, "Chính sách nhân sự"),
      ],
    }),
  );
  await page.route("**/api/channels/*/groups", (route) =>
    route.fulfill({ json: route.request().url().includes(FINANCE) ? groups : [] }),
  );
  await page.route("**/api/channels/*/groups/*/persona", async (route) => {
    const groupId = decodeURIComponent(route.request().url().split("/").at(-2) ?? "");
    const { personaId } = route.request().postDataJSON() as { personaId: string | null };
    chosen.push({ groupId, personaId });
    const group = groups.find((candidate) => candidate.id === groupId);
    if (group) group.assistant = personaId ? { id: personaId, name: "Kế toán thuế" } : undefined;
    await route.fulfill({ json: group });
  });
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
  return { switched, chosen };
}

test.describe.configure({ timeout: 120_000 });

test("the bots are listed with one line each, a switch saves on change and a bot opens its page", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const { switched } = await stub(page);
  await page.goto("/admin/channels");

  await expect(page.getByRole("heading", { name: "Kênh chat", level: 1 })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByText("Đang trả lời trên Lark")).toBeVisible();
  await expect(page.getByText("Thiếu 1 quyền trên Lark")).toBeVisible();
  await expect(page.getByText("Đang tắt")).toBeVisible();

  await page.getByRole("switch", { name: "Bật Bot Nhân sự" }).click();
  await expect
    .poll(() => switched)
    .toEqual([{ id: "6e2a9c4b-3d1f-4a8e-b7c6-d5e4f3a2b1c0", enabled: true, ifMatch: '"1"' }]);

  await page.getByRole("link", { name: /Hỏi đáp Tài chính/ }).click();
  await expect(page.getByRole("heading", { name: "Hỏi đáp Tài chính", level: 1 })).toBeVisible();
  await expect(page.getByText("Finance Q&A")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Quyền Lark còn thiếu" })).toBeVisible();
  await expect(page.getByText("contact:user.email:readonly")).toBeVisible();
});

test("each group chat takes an assistant of its own on the bot's page", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const { chosen } = await stub(page);
  await page.goto(`/admin/channels/${FINANCE}`);

  await expect(page.getByRole("heading", { name: "Theo nhóm chat" })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByRole("combobox", { name: "Trợ lý mặc định" })).toHaveText("MemoryOS");
  await expect(page.getByText("Nhóm Lark · hỏi 10 phút trước")).toBeVisible();
  const board = page.getByRole("combobox", { name: "Trợ lý của Ban Giám đốc" });
  await expect(board).toHaveText("Theo mặc định");

  await board.click();
  await page.getByRole("option", { name: "Kế toán thuế" }).click();
  await expect
    .poll(() => chosen)
    .toEqual([{ groupId: "oc_5f1e2d3c4b5a69788796a5b4c3d2e1f0", personaId: TAX }]);

  await page.getByRole("button", { name: "Thao tác với Phòng Nhân sự" }).click();
  await page.getByRole("menuitem", { name: "Quên nhóm" }).click();
  await expect(page.getByRole("alertdialog", { name: "Quên nhóm Phòng Nhân sự?" })).toBeVisible();
});

test("adding a bot opens the dialog beside the console steps and waits for the App Secret", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await stub(page, []);
  await page.goto("/admin/channels");

  await expect(page.getByText("Chưa có bot nào.")).toBeVisible({ timeout: 30_000 });
  await page.getByRole("button", { name: "Thêm bot" }).click();
  const dialog = page.getByRole("dialog", { name: "Thêm bot" });
  await expect(dialog.getByRole("radio", { name: "Lark", exact: true })).toBeChecked();
  await expect(dialog.getByText("im.message.receive_v1")).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Kiểm tra và lưu" })).toBeDisabled();
  await dialog.getByRole("textbox", { name: "App Secret" }).fill("secret");
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
      await expect(page.getByRole("heading", { name: "Kênh chat", level: 1 })).toBeAttached({
        timeout: 30_000,
      });
      await page.screenshot({ path: `${OUTPUT}/list-${theme}-${width}.png`, fullPage: true });

      await page.goto(`/admin/channels/${FINANCE}`);
      // A page opened by its address compiles its route on the dev server's first visit.
      await expect(page.getByRole("heading", { name: "Hỏi đáp Tài chính", level: 1 })).toBeAttached(
        {
          timeout: 30_000,
        },
      );
      await expect(page.getByRole("heading", { name: "Theo nhóm chat" })).toBeAttached();
      await page.screenshot({ path: `${OUTPUT}/detail-${theme}-${width}.png`, fullPage: true });
      await page.getByRole("button", { name: "Thao tác với Phòng Kế toán" }).click();
      await page.getByRole("menuitem", { name: "Đổi tên" }).click();
      await expect(page.getByRole("dialog", { name: "Đổi tên nhóm chat" })).toBeVisible();
      await page.screenshot({ path: `${OUTPUT}/rename-${theme}-${width}.png` });

      await page.goto("/admin/channels");
      await page.getByRole("button", { name: "Thêm bot" }).click({ timeout: 30_000 });
      await expect(page.getByRole("dialog")).toBeVisible({ timeout: 30_000 });
      await page.screenshot({ path: `${OUTPUT}/dialog-${theme}-${width}.png`, fullPage: true });
    });
  }
}
