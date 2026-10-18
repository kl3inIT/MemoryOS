import { expect, test, type Page } from "@playwright/test";

// The sidebar lists only the first three projects, so the projects a parallel test creates would push this file's out
// of it; the tests share one fixture server and run in order.
test.describe.configure({ mode: "default" });

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
  // The project's own list is closed; "Gần đây" may still list the same conversations.
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

test("removing a conversation from a sidebar project keeps it among its assistant's topics", async ({
  page,
}) => {
  const id = await createProject(page, "Remove history project");
  const session = await (
    await page.request.post(`/api/chat/projects/${id}/sessions`, {
      data: { title: "Retained project conversation" },
    })
  ).json();
  await page.goto(`/chat/${session.id}`);
  const sidebar = page.getByRole("complementary");
  const folder = sidebar.locator(`[data-project-id="${id}"]`);
  const recent = sidebar.getByRole("group", { name: "Chủ đề của MemoryOS", exact: true });
  await expect(folder.locator(`a[href="/chat/${session.id}"]`)).toBeVisible();
  await folder.getByRole("button", { name: `Thao tác hội thoại ${session.title}` }).click();
  // The topics list loads from its own query; holding its reads makes it show the conversation after the Project
  // row has gone, the order a busy machine produced, so the focus has to wait for the row to be there.
  let holdReads = true;
  await page.route("**/api/chat/**", async (route) => {
    if (holdReads && route.request().method() === "GET")
      await new Promise((done) => setTimeout(done, 1500));
    await route.continue();
  });
  await page.getByRole("menuitem", { name: "Gỡ khỏi dự án", exact: true }).click();
  await expect(folder.locator(`a[href="/chat/${session.id}"]`)).toHaveCount(0);
  await expect(recent.locator(`a[href="/chat/${session.id}"]`)).toBeVisible({ timeout: 10_000 });
  holdReads = false;
  // Out of the Project, the sidebar is the conversation's assistant's; focus continues at its topic, not the page.
  await expect(recent.locator(`a[href="/chat/${session.id}"]`)).toBeFocused();
  await page
    .getByRole("banner")
    .getByRole("button", { name: `Thao tác hội thoại ${session.title}` })
    .click();
  await expect(page.getByRole("menuitem", { name: "Gỡ khỏi dự án", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(`/chat/${session.id}`);
  await page.reload();
  await expect(recent.locator(`a[href="/chat/${session.id}"]`)).toBeVisible();
  await page.getByRole("button", { name: "Tìm kiếm", exact: true }).click();
  await expect(
    page
      .getByRole("dialog", { name: "Tìm kiếm", exact: true })
      .getByRole("option", { name: session.title }),
  ).toBeVisible();
  await page.request.delete(`/api/chat/projects/${id}?revision=0`);
});

test("starts a conversation from the project menu without creating a session until the first send", async ({
  page,
}) => {
  const id = await createProject(page, "New conversation project");
  const existing = await (
    await page.request.post(`/api/chat/projects/${id}/sessions`, {
      data: { title: "Existing project conversation" },
    })
  ).json();
  const listProjectSessions = async () =>
    (await (await page.request.get(`/api/chat/projects/${id}/sessions`)).json()) as {
      id: string;
      title: string;
    }[];
  await page.goto(`/chat/${existing.id}`);
  const sidebar = page.getByRole("complementary");
  const folder = sidebar.locator(`[data-project-id="${id}"]`);
  await expect(folder.locator(`a[href="/chat/${existing.id}"]`)).toBeVisible();

  await folder.getByRole("button", { name: "Thao tác với New conversation project" }).click();
  await page.getByRole("menuitem", { name: "Hội thoại mới", exact: true }).click();

  // The open conversation gives way to an unsent one in the Project; nothing is created yet.
  await expect(page).toHaveURL(`/projects/${id}`);
  const input = page.getByRole("textbox", { name: "Câu hỏi", exact: true });
  await expect(input).toBeVisible();
  await expect(input).toHaveValue("");
  expect((await listProjectSessions()).map((item) => item.id)).toEqual([existing.id]);

  await input.fill("Project menu first question");
  await input.press("Enter");
  await expect(page).toHaveURL(/\/chat\/[0-9a-f-]+$/);
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Dừng trả lời" })).toHaveCount(0);
  const sessionId = page.url().split("/").at(-1)!;
  expect(sessionId).not.toBe(existing.id);
  const created = (await listProjectSessions()).find((item) => item.id === sessionId);
  expect(created).toBeDefined();

  await folder.getByRole("button", { name: `Thao tác hội thoại ${created!.title}` }).click();
  await page.getByRole("menuitem", { name: "Gỡ khỏi dự án", exact: true }).click();
  await expect(folder.locator(`a[href="/chat/${sessionId}"]`)).toHaveCount(0);
  const recent = sidebar.getByRole("group", { name: "Chủ đề của MemoryOS", exact: true });
  await expect(recent.locator(`a[href="/chat/${sessionId}"]`)).toBeVisible();
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  await page.reload();
  await expect(recent.locator(`a[href="/chat/${sessionId}"]`)).toBeVisible();
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  await page.request.delete(`/api/chat/projects/${id}?revision=0`);
});

test("retains a newly sent project conversation and its transcript after removal", async ({
  page,
}) => {
  const id = await createProject(page, "First send history project");
  await page.goto(`/projects/${id}`);
  const input = page.getByRole("textbox", { name: "Câu hỏi", exact: true });
  await input.fill("Project history retention question");
  await input.press("Enter");
  await expect(page).toHaveURL(/\/chat\/[0-9a-f-]+$/);
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Dừng trả lời" })).toHaveCount(0);
  const sessionId = page.url().split("/").at(-1)!;
  const sidebar = page.getByRole("complementary");
  const folder = sidebar.locator(`[data-project-id="${id}"]`);
  await folder.getByRole("button", { name: /^Thao tác hội thoại/ }).click();
  await page.getByRole("menuitem", { name: "Gỡ khỏi dự án", exact: true }).click();
  await expect(folder.getByRole("link")).toHaveCount(0);
  const recent = sidebar.getByRole("group", { name: "Chủ đề của MemoryOS", exact: true });
  await expect(recent.locator(`a[href="/chat/${sessionId}"]`)).toBeVisible();
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  await page.reload();
  await expect(recent.locator(`a[href="/chat/${sessionId}"]`)).toBeVisible();
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
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
    .getByRole("button", { name: /^Hội thoại trong Order test/ })
    .evaluateAll((links) => links.map((link) => link.textContent));
  expect(sidebarNames).toEqual(["Order test second", "Order test third", "Order test first"]);

  for (const id of ids) await page.request.delete(`/api/chat/projects/${id}?revision=0`);
});

test("reorders projects in the sidebar by dragging the row with the pointer", async ({ page }) => {
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
  const handle = sidebar.getByRole("button", { name: "Hội thoại trong Drag test lower" });
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
  // Letting go of the dragged row must not open it.
  await expect(handle).toHaveAttribute("aria-expanded", "false");
  await page.reload();
  await expect.poll(names).toEqual(["Drag test lower", "Drag test upper"]);

  for (const id of ids) await page.request.delete(`/api/chat/projects/${id}?revision=0`);
});

test("reorders projects on the Projects page by dragging a row and does not open it on release", async ({
  page,
}) => {
  const ids = [
    await createProject(page, "Page drag lower"),
    await createProject(page, "Page drag upper"),
  ];
  await page.goto("/projects");
  const rows = page.getByRole("main").getByRole("link", { name: /^Page drag/ });
  const names = () =>
    rows.evaluateAll((links) => links.map((link) => link.textContent?.replace(/0 tệp.*/, "")));
  // A new project comes first.
  await expect.poll(names).toEqual(["Page drag upper", "Page drag lower"]);

  const saved = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/chat/projects/order") && response.request().method() === "PUT",
  );
  const from = await rows.nth(0).boundingBox();
  const target = await rows.nth(1).boundingBox();
  if (!from || !target) throw new Error("The project rows have no layout.");
  await page.mouse.move(from.x + 60, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(from.x + 60, target.y + target.height - 2, { steps: 12 });
  await page.mouse.up();

  await expect.poll(names).toEqual(["Page drag lower", "Page drag upper"]);
  expect((await saved).status()).toBe(204);
  await page.waitForTimeout(300);
  await expect(page).toHaveURL(/\/projects$/);

  for (const id of ids) await page.request.delete(`/api/chat/projects/${id}?revision=0`);
});

test("narrows the Projects page by a search kept in the address, without dragging, and says when nothing matches", async ({
  page,
}) => {
  const ids = [
    await createProject(page, "Lọc Tài chính"),
    await createProject(page, "Lọc Kế hoạch quý"),
  ];
  await page.goto("/projects");
  const main = page.getByRole("main");
  const box = main.getByRole("textbox", { name: "Tìm dự án" });
  const rows = main.getByRole("link", { name: /^Lọc/ });
  const names = () =>
    rows.evaluateAll((links) => links.map((link) => link.textContent?.replace(/0 tệp.*/, "")));
  await expect.poll(names).toEqual(["Lọc Kế hoạch quý", "Lọc Tài chính"]);

  // Typed without its marks, the search still finds the name.
  await box.fill("loc ke hoach");
  await expect.poll(names).toEqual(["Lọc Kế hoạch quý"]);
  await expect(page).toHaveURL(/[?&]q=loc(\+|%20)ke(\+|%20)hoach/);
  await page.reload();
  await expect(box).toHaveValue("loc ke hoach");
  await expect.poll(names).toEqual(["Lọc Kế hoạch quý"]);

  // A narrowed list has no handles and does not move under the pointer.
  await box.fill("loc");
  await expect.poll(names).toEqual(["Lọc Kế hoạch quý", "Lọc Tài chính"]);
  await expect(main.getByText("Xóa từ khóa để sắp xếp lại dự án.")).toBeVisible();
  await expect(main.getByRole("button", { name: /^Kéo để sắp xếp/ })).toHaveCount(0);
  let reordered = false;
  page.on("request", (request) => {
    if (request.url().endsWith("/api/chat/projects/order") && request.method() === "PUT")
      reordered = true;
  });
  const from = await rows.nth(0).boundingBox();
  const target = await rows.nth(1).boundingBox();
  if (!from || !target) throw new Error("The project rows have no layout.");
  await page.mouse.move(from.x + 60, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(from.x + 60, target.y + target.height - 2, { steps: 12 });
  await page.mouse.up();
  await page.waitForTimeout(300);
  expect(reordered).toBe(false);
  await expect.poll(names).toEqual(["Lọc Kế hoạch quý", "Lọc Tài chính"]);

  await box.fill("khong co du an nay");
  await expect(main.getByRole("heading", { name: "Không có dự án nào khớp" })).toBeVisible();
  await main.getByRole("button", { name: "Xóa bộ lọc" }).click();
  await expect(box).toHaveValue("");
  await expect(page).toHaveURL(/\/projects$/);
  await expect(main.getByRole("button", { name: "Kéo để sắp xếp Lọc Tài chính" })).toHaveCount(1);

  for (const id of ids) await page.request.delete(`/api/chat/projects/${id}?revision=0`);
});

test("selecting text in the project editor does not drag the project rows behind it", async ({
  page,
}) => {
  const ids = [
    await createProject(page, "Editor drag lower"),
    await createProject(page, "Editor drag upper"),
  ];
  await page.goto("/projects");
  const rows = page.getByRole("main").getByRole("link", { name: /^Editor drag/ });
  const names = () =>
    rows.evaluateAll((links) => links.map((link) => link.textContent?.replace(/0 tệp.*/, "")));
  await expect.poll(names).toEqual(["Editor drag upper", "Editor drag lower"]);

  await page
    .getByRole("main")
    .getByRole("button", { name: "Thao tác với Editor drag upper" })
    .click();
  await page.getByRole("menuitem", { name: "Sửa" }).click();
  const dialog = page.getByRole("dialog");
  const name = dialog.getByRole("textbox", { name: /Tên/ });
  const field = await name.boundingBox();
  if (!field) throw new Error("The editor has no layout.");

  let reordered = false;
  page.on("request", (request) => {
    if (request.url().endsWith("/api/chat/projects/order") && request.method() === "PUT")
      reordered = true;
  });
  // Select the name by dragging from its end down past the dialog, as a person does when the hand overshoots.
  await page.mouse.move(field.x + field.width - 20, field.y + field.height / 2);
  await page.mouse.down();
  await page.mouse.move(field.x + 10, (page.viewportSize()?.height ?? 720) - 5, { steps: 12 });
  await page.mouse.up();

  await expect(dialog).toBeVisible();
  await expect(name).toHaveValue("Editor drag upper");
  await page.waitForTimeout(300);
  expect(reordered).toBe(false);
  await dialog.getByRole("button", { name: "Đóng" }).click();
  await expect.poll(names).toEqual(["Editor drag upper", "Editor drag lower"]);

  for (const id of ids) await page.request.delete(`/api/chat/projects/${id}?revision=0`);
});
