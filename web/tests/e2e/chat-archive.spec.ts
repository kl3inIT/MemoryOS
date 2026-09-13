import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        actorId: "e62a621f-41d2-4853-aa76-b600dafd8e34",
        authorizationVersion: 1,
        uiLanguage: "vi",
        tenant: { displayName: "Test tenant", role: "MEMBER" },
        capabilities: ["SYSTEM_BASIC", "SEARCH_READ", "CHAT_READ", "CHAT_WRITE"],
        scopedCapabilities: [],
      },
    }),
  );
});

test("archives a conversation from the sidebar and reopening it returns it to the regular list", async ({
  page,
}) => {
  const created = await page.request.post("/api/chat/test-fixture", {
    data: { title: "Archive candidate" },
  });
  const session = await created.json();
  await page.goto("/");
  const sidebar = page.getByRole("complementary");
  const recent = sidebar.getByLabel("Hội thoại gần đây", { exact: true });
  await expect(recent.getByRole("link", { name: "Archive candidate", exact: true })).toBeVisible();

  await sidebar.getByRole("button", { name: "Thao tác hội thoại Archive candidate" }).click();
  await page.getByRole("menuitem", { name: "Lưu trữ", exact: true }).click();

  await expect(recent.getByRole("link", { name: "Archive candidate", exact: true })).toHaveCount(0);
  await expect
    .poll(async () =>
      (await (await page.request.get("/api/chat/sessions?status=ARCHIVED")).json()).map(
        (item: { id: string }) => item.id,
      ),
    )
    .toContain(session.id);
  const archivedToggle = sidebar.getByRole("button", { name: "Hội thoại đã lưu trữ" });
  await expect(archivedToggle).toHaveAttribute("aria-expanded", "false");
  await archivedToggle.click();
  const archived = sidebar.getByLabel("Hội thoại đã lưu trữ", { exact: true }).last();
  await archived.getByRole("link", { name: "Archive candidate", exact: true }).click();

  await expect(page).toHaveURL(new RegExp(`/chat/${session.id}$`));
  await expect(page.getByRole("banner")).toContainText("Archive candidate");
  await expect(recent.getByRole("link", { name: "Archive candidate", exact: true })).toBeVisible();
  await expect
    .poll(
      async () =>
        (await (await page.request.get(`/api/chat/sessions/${session.id}`)).json()).archived,
    )
    .toBe(false);
});
