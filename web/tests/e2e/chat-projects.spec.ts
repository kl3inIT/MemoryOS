import { expect, test, type Page } from "@playwright/test";

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

async function createProject(page: Page, name: string) {
  return (
    await (
      await page.request.post("/api/chat/projects", {
        data: { name, description: "", instructions: "" },
      })
    ).json()
  ).id as string;
}

test("closes and opens the Dự án section and remembers it", async ({ page }) => {
  const id = await createProject(page, "Section toggle project");
  await page.goto("/");
  const sidebar = page.getByRole("complementary");
  const toggle = sidebar.getByRole("button", { name: "Danh sách dự án" });
  const project = sidebar.getByRole("button", {
    name: "Hội thoại trong Section toggle project",
  });
  await expect(project).toBeVisible();

  await toggle.click();
  await expect(project).toBeHidden();
  await page.reload();
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await expect(project).toBeHidden();

  await toggle.click();
  await expect(project).toBeVisible();
  await page.request.delete(`/api/chat/projects/${id}?revision=0`);
});

test("opens a project in the sidebar to its conversations", async ({ page }) => {
  const id = await createProject(page, "Folder project");
  for (const title of ["Folder conversation one", "Folder conversation two"])
    await page.request.post(`/api/chat/projects/${id}/sessions`, { data: { title } });
  await page.goto("/");
  const sidebar = page.getByRole("complementary");
  const folder = sidebar.locator(`[data-project-id="${id}"]`);
  const opener = folder.getByRole("button", { name: "Hội thoại trong Folder project" });
  await expect(opener).toHaveAttribute("aria-expanded", "false");
  // The project's own list is closed; "Hội thoại gần đây" may still list the same conversations.
  await expect(folder.getByRole("link", { name: /^Folder conversation/ })).toHaveCount(0);

  await opener.click();

  await expect(opener).toHaveAttribute("aria-expanded", "true");
  await expect(folder.getByRole("link", { name: /^Folder conversation one/ })).toBeVisible();
  await expect(folder.getByRole("link", { name: /^Folder conversation two/ })).toBeVisible();
  await folder.getByRole("link", { name: /^Folder conversation one/ }).click();
  await expect(page).toHaveURL(/\/chat\/[0-9a-f-]+$/);
  await opener.click();
  await expect(folder.getByRole("link", { name: /^Folder conversation/ })).toHaveCount(0);
  await page.request.delete(`/api/chat/projects/${id}?revision=0`);
});

test("opens the project of the conversation a page address names", async ({ page }) => {
  const id = await createProject(page, "Revealed project");
  const session = await (
    await page.request.post(`/api/chat/projects/${id}/sessions`, {
      data: { title: "Revealed conversation" },
    })
  ).json();
  await page.goto(`/chat/${session.id}`);

  const folder = page.getByRole("complementary").locator(`[data-project-id="${id}"]`);
  await expect(
    folder.getByRole("button", { name: "Hội thoại trong Revealed project" }),
  ).toHaveAttribute("aria-expanded", "true");
  await expect(folder.getByRole("link", { name: /^Revealed conversation/ })).toBeVisible();
  await page.request.delete(`/api/chat/projects/${id}?revision=0`);
});

test("reorders projects with the keyboard and keeps the order after a reload", async ({ page }) => {
  const ids = [
    await createProject(page, "Order test first"),
    await createProject(page, "Order test second"),
    await createProject(page, "Order test third"),
  ];
  await page.goto("/projects");
  const names = () =>
    page
      .getByRole("main")
      .getByRole("link", { name: /^Order test/ })
      // A row's link also holds its file count and update day.
      .evaluateAll((links) => links.map((link) => link.textContent?.replace(/0 tệp.*/, "")));
  // A new project comes first.
  await expect.poll(names).toEqual(["Order test third", "Order test second", "Order test first"]);

  const saved = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/chat/projects/order") && response.request().method() === "PUT",
  );
  await page
    .getByRole("main")
    .getByRole("button", { name: "Kéo để sắp xếp Order test third" })
    .focus();
  await page.keyboard.press("Space");
  await page.waitForTimeout(150);
  await page.keyboard.press("ArrowDown");
  await page.waitForTimeout(300);
  await page.keyboard.press("Space");

  await expect.poll(names).toEqual(["Order test second", "Order test third", "Order test first"]);
  expect((await saved).status()).toBe(204);
  await page.reload();
  await expect.poll(names).toEqual(["Order test second", "Order test third", "Order test first"]);
  // The sidebar lists the same order.
  const sidebarNames = await page
    .getByRole("complementary")
    .getByRole("link", { name: /^Order test/ })
    .evaluateAll((links) => links.map((link) => link.textContent));
  expect(sidebarNames).toEqual(["Order test second", "Order test third", "Order test first"]);

  for (const id of ids) await page.request.delete(`/api/chat/projects/${id}?revision=0`);
});

test("reorders projects in the sidebar by dragging the handle with the pointer", async ({
  page,
}) => {
  const ids = [
    await createProject(page, "Drag test lower"),
    await createProject(page, "Drag test upper"),
  ];
  await page.goto("/");
  const sidebar = page.getByRole("complementary");
  const names = () =>
    sidebar
      .getByRole("button", { name: /^Hội thoại trong Drag test/ })
      .evaluateAll((rows) => rows.map((row) => row.textContent));
  // A new project comes first.
  await expect.poll(names).toEqual(["Drag test upper", "Drag test lower"]);

  const saved = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/chat/projects/order") && response.request().method() === "PUT",
  );
  const handle = sidebar.getByRole("button", { name: "Kéo để sắp xếp Drag test lower" });
  const target = await sidebar
    .getByRole("button", { name: "Hội thoại trong Drag test upper" })
    .boundingBox();
  const from = await handle.boundingBox();
  if (!from || !target) throw new Error("The project rows have no layout.");
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(from.x + from.width / 2, target.y + 2, { steps: 12 });
  await page.mouse.up();

  await expect.poll(names).toEqual(["Drag test lower", "Drag test upper"]);
  expect((await saved).status()).toBe(204);
  await page.reload();
  await expect.poll(names).toEqual(["Drag test lower", "Drag test upper"]);

  for (const id of ids) await page.request.delete(`/api/chat/projects/${id}?revision=0`);
});
