// Temporary screenshot harness for self-review; not committed.
import { test, type Page } from "@playwright/test";

const out = "D:/MemoryOS/output/mem149-shots";
const customId = "50000000-0000-0000-0000-000000000001";
const identity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1", authorizationVersion: 1, uiLanguage: "vi",
  tenant: { displayName: "Tasco", role: "OWNER" },
  capabilities: ["SYSTEM_ADMIN", "SYSTEM_BASIC", "CHAT_READ", "CHAT_WRITE", "USERS_MANAGE", "GROUPS_READ", "GROUPS_MANAGE", "SOURCES_READ", "MODELS_MANAGE"],
  scopedCapabilities: [],
};
const group = {
  id: customId, name: "Kế toán", systemKey: null, memberCount: 12, managerCount: 2,
  capabilities: ["SEARCH_READ", "CHAT_READ"],
  permissions: { manage: true, manageMembers: true, delete: true, editPermissions: true, manageSources: true },
};
const capability = (key: string, name: string, description: string) => ({ key, name, description, category: "CHAT" });

async function mocks(page: Page) {
  await page.route("**/api/identity/me", (r) => r.fulfill({ json: identity }));
  await page.route("**/api/chat/sessions?*", (r) => r.fulfill({ json: [] }));
  await page.route(`**/api/groups/${customId}`, (r) => r.fulfill({ json: group }));
  await page.route("**/api/groups?*", (r) => r.fulfill({ json: { items: [group], page: 0, size: 20, totalItems: 1, totalPages: 1 } }));
  await page.route("**/api/groups/capabilities*", (r) => r.fulfill({ json: { items: [
    capability("SEARCH_READ", "Tìm tài liệu", "Tìm trong các nguồn được phép"),
    capability("CHAT_READ", "Trò chuyện", "Hỏi đáp với trợ lý"),
  ] } }));
  await page.route(`**/api/groups/${customId}/members*`, (r) => r.fulfill({ json: { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 } }));
  await page.route(`**/api/groups/${customId}/sources*`, (r) => r.fulfill({ json: { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 } }));
  await page.route("**/api/sources*", (r) => r.fulfill({ json: [] }));
}

for (const [width, scheme] of [[1280, "light"], [1280, "dark"], [390, "light"]] as const) {
  test(`groups ${width} ${scheme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await page.setViewportSize({ width, height: width < 768 ? 1500 : 1100 });
    await mocks(page);
    for (const [name, path, text] of [["groups", "/admin/groups", "Kế toán"], ["group-detail", `/admin/groups/${customId}`, "Tên nhóm"]] as const) {
      await page.goto(path);
      await page.getByText(text).first().waitFor({ timeout: 30000 });
      await page.waitForTimeout(500);
      await page.screenshot({ path: `${out}/${name}-${width}-${scheme}.png`, fullPage: true });
    }
  });
}
