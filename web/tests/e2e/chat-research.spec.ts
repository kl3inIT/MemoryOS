import { expect, test } from "@playwright/test";

const identity = {
  actorId: "e62a621f-41d2-4853-aa76-b600dafd8e34",
  authorizationVersion: 1,
  uiLanguage: "vi",
  tenant: { displayName: "Test tenant", role: "MEMBER" },
  capabilities: ["SYSTEM_BASIC", "SEARCH_READ", "CHAT_READ", "CHAT_WRITE"],
  scopedCapabilities: [],
};

const persona = {
  id: "9f0d1f1a-4a0a-4d64-9f0a-9f0d1f1a4a0a",
  builtin: true,
  permissions: { edit: false, delete: false },
  revision: 0,
  name: "Trợ lý",
  description: "Trợ lý mặc định",
  instructions: "",
  starterPrompts: [],
  sourceIds: [],
  fileIds: [],
  tools: ["search", "web_search", "image_generation"],
};

/** A saved Deep research answer restored from history: plan, cycles, agent tabs and intermediate reports. */
test("restores the research timeline of a saved answer", async ({ page }) => {
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: identity }));
  await page.route("**/api/chat/personas*", (route) => route.fulfill({ json: [persona] }));
  await page.route("**/api/chat/settings*", (route) =>
    route.fulfill({ json: { deepResearchEnabled: true, revision: 0 } }),
  );
  const created = await page.request.post("/api/chat/test-fixture", {
    data: { title: "Nghiên cứu chính sách nghỉ phép", mode: "research" },
  });
  const session = await created.json();

  await page.goto(`/chat/${session.id}`);
  // The answer reads first; research is collapsed behind its header until the reader opens it.
  await expect(page.getByRole("heading", { name: "Nghỉ phép hằng năm" })).toBeVisible();
  const header = page.getByRole("button", { name: "Đã nghiên cứu" });
  await expect(header).toContainText("4 bước · 2 nguồn");
  await expect(page.getByText("Kế hoạch nghiên cứu")).toBeHidden();

  await header.click();
  await expect(page.getByText("Xác định số ngày phép hằng năm").first()).toBeVisible();
  // A long plan stays clamped behind a reveal so the answer is not pushed far down the thread.
  const reveal = page.getByRole("button", { name: "Xem thêm" }).first();
  await expect(reveal).toBeVisible();
  await reveal.click();
  await expect(page.getByRole("button", { name: "Thu gọn" }).first()).toBeVisible();
  await expect(page.getByText("Chu kỳ 1")).toBeVisible();
  await expect(page.getByText("Chu kỳ 2")).toBeVisible();

  // The first cycle ran two agents in parallel, so it has one tab each; the second ran one agent alone.
  const tabs = page.getByRole("tab");
  await expect(tabs).toHaveText(["Tác tử 1", "Tác tử 2"]);
  await expect(page.getByText("Đã chạy 1 phút 32 giây")).toBeVisible();
  await expect(page.getByText("nghỉ phép hằng năm").first()).toBeVisible();

  await tabs.nth(1).click();
  await expect(page.getByText("Tìm quy trình duyệt đơn nghỉ phép")).toBeVisible();
  await expect(page.getByText("Đã chạy 1 phút 4 giây")).toBeVisible();

  // An intermediate report opens on demand and keeps its own citation numbers.
  await page.getByText("Báo cáo trung gian").first().click();
  await expect(page.getByText("Đơn nghỉ phép gửi trước")).toBeVisible();
});

test("shows the Deep research toggle outside Projects and keeps it off by default", async ({
  page,
}) => {
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: identity }));
  await page.route("**/api/chat/personas*", (route) => route.fulfill({ json: [persona] }));
  await page.route("**/api/chat/settings*", (route) =>
    route.fulfill({ json: { deepResearchEnabled: true, revision: 0 } }),
  );
  await page.goto("/");
  const toggle = page.getByRole("button", { name: "Deep research" });
  await expect(toggle).toHaveAttribute("aria-pressed", "false");
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-pressed", "true");
});
