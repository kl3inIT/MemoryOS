import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        actorId: "e62a621f-41d2-4853-aa76-b600dafd8e34",
        authorizationVersion: 1,
        tenant: { displayName: "Test tenant", role: "MEMBER" },
        capabilities: [],
        scopedCapabilities: [],
      },
    }),
  );
});

for (const title of ["Chat", "Search"]) {
  test(`keeps the saved ${title} title separate from the mode switcher`, async ({ page }) => {
    const session = await (
      await page.request.post("/api/chat/test-fixture", { data: { title } })
    ).json();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`/chat/${session.id}`);
    const header = page.getByRole("banner");
    await expect(header.getByRole("button", { name: `Thao tác hội thoại ${title}` })).toBeVisible();
    await expect(header.getByText(title, { exact: true })).toBeVisible();
    await expect(header.getByRole("button", { name: /switch mode/ })).toHaveCount(0);
    await page.goto("/");
    await expect(header.getByRole("button", { name: "Chat, switch mode" })).toBeVisible();
    await page.goto("/search");
    await expect(header.getByRole("button", { name: "Search, switch mode" })).toBeVisible();
  });
}

test("keeps the project dialog and draft when the creation response is malformed", async ({
  page,
}) => {
  await page.route("**/api/chat/projects", (route) =>
    route.request().method() === "POST"
      ? route.fulfill({ status: 201, json: { id: "40000000-0000-4000-8000-000000000003" } })
      : route.continue(),
  );
  await page.goto("/projects");
  await page.getByRole("main").getByRole("button", { name: "Tạo dự án", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Tạo dự án" });
  await dialog.getByLabel("Tên dự án").fill("Project draft to preserve");
  await dialog.getByRole("button", { name: "Tạo dự án", exact: true }).click();
  await expect(dialog.getByRole("alert")).toContainText("Chưa xác nhận được kết quả");
  await expect(dialog.getByLabel("Tên dự án")).toHaveValue("Project draft to preserve");
  await expect(page).toHaveURL(/\/projects$/);
  await dialog.getByRole("button", { name: "Đóng", exact: true }).click();
  await expect(dialog).toHaveCount(0);
});

test("does not mark an assistant unavailable while its settings are loading", async ({ page }) => {
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Loading settings" } })
  ).json();
  let releasePersonas = () => {};
  const pending = new Promise<void>((resolve) => {
    releasePersonas = resolve;
  });
  let available = true;
  await page.route("**/api/chat/personas?*", async (route) => {
    await pending;
    await route.fulfill({
      json: available
        ? [
            {
              id: session.personaId,
              name: "Available assistant",
              builtin: true,
              editable: false,
              revision: 0,
              description: "",
              instructions: "",
              starterPrompts: [],
              sourceIds: [],
              searchEnabled: true,
            },
          ]
        : [],
    });
  });
  const openSettings = async () => {
    await page
      .getByRole("banner")
      .getByRole("button", { name: "Thao tác hội thoại Loading settings" })
      .click();
    await page.getByRole("menuitem", { name: "Cấu hình hội thoại" }).click();
  };
  await page.goto(`/chat/${session.id}`);
  await openSettings();
  const dialog = page.getByRole("dialog", { name: "Cấu hình hội thoại" });
  try {
    await expect(dialog.getByRole("button", { name: "Lưu", exact: true })).toBeDisabled();
    await expect(dialog.getByRole("option", { name: "Trợ lý không còn khả dụng" })).toHaveCount(0);
  } finally {
    releasePersonas();
  }
  await expect(dialog.getByRole("option", { name: "Available assistant" })).toHaveCount(1);
  await expect(dialog.getByRole("combobox", { name: "Trợ lý", exact: true })).toHaveValue(
    session.personaId,
  );
  await expect(dialog.getByRole("button", { name: "Lưu", exact: true })).toBeEnabled();
  await dialog.getByRole("button", { name: "Đóng", exact: true }).click();
  available = false;
  await page.reload();
  await openSettings();
  await expect(dialog.getByRole("option", { name: "Trợ lý không còn khả dụng" })).toHaveCount(1);
});

test("opens sessions created through the project endpoint and preserves list pagination", async ({
  page,
}) => {
  const project = await (
    await page.request.post("/api/chat/projects", {
      data: { name: "Project session contract", description: "", instructions: "" },
    })
  ).json();
  const created = [];
  for (const title of ["First project session", "Second project session"]) {
    const response = await page.request.post(`/api/chat/projects/${project.id}/sessions`, {
      data: { title },
    });
    expect(response.status()).toBe(201);
    const session = await response.json();
    expect(session).toMatchObject({ title, projectId: project.id });
    created.push(session);
  }
  const firstPage = await (
    await page.request.get(`/api/chat/projects/${project.id}/sessions?limit=1&offset=0`)
  ).json();
  const secondPage = await (
    await page.request.get(`/api/chat/projects/${project.id}/sessions?limit=1&offset=1`)
  ).json();
  expect(firstPage).toHaveLength(1);
  expect(secondPage).toHaveLength(1);
  expect(new Set([firstPage[0].id, secondPage[0].id])).toEqual(
    new Set(created.map((session) => session.id)),
  );
  await page.goto(`/projects/${project.id}`);
  await page
    .getByRole("main")
    .getByRole("link", { name: /^First project session/ })
    .click();
  await expect(page.getByRole("banner")).toContainText("First project session");
  await expect(page.getByRole("textbox", { name: "Câu hỏi", exact: true })).toBeVisible();
  await page.request.delete(`/api/chat/projects/${project.id}?revision=0`);
});

test("edits, regenerates, selects saved branches, rates, shares, revokes and deletes", async ({
  page,
}) => {
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Workspace versions" } })
  ).json();
  const ownUrl = `/chat/${session.id}`;
  await page.goto(ownUrl);
  await page.getByRole("textbox", { name: "Câu hỏi", exact: true }).fill("Original question");
  await page.getByRole("button", { name: "Gửi câu hỏi" }).click();
  await expect(page.getByRole("button", { name: "Chỉnh sửa câu hỏi" })).toBeEnabled();
  await page.getByRole("button", { name: "Chỉnh sửa câu hỏi" }).click();
  await page.getByRole("textbox", { name: "Nội dung câu hỏi" }).fill("Edited question");
  await page.getByRole("button", { name: "Lưu và gửi" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("main").getByText("Edited question", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Tạo lại câu trả lời" })).toBeEnabled();
  await page.getByRole("button", { name: "Tạo lại câu trả lời" }).click();
  const answers = page.getByRole("group", { name: "Phiên bản câu trả lời" });
  await expect(answers.getByText("2 / 2")).toBeVisible();
  const branches = await (
    await page.request.get(`/api/chat/sessions/${session.id}/branches`)
  ).json();
  const firstQuestion = branches.find(
    (branch: { parentMessageId: string | null }) =>
      branch.parentMessageId === session.rootMessageId,
  );
  const staleBranch = await page.request.put(`/api/chat/sessions/${session.id}/branch`, {
    data: { messageId: firstQuestion.id, expectedChildId: session.rootMessageId },
  });
  expect(staleBranch.status()).toBe(409);
  await expect(page.getByRole("button", { name: "Không hữu ích" })).toBeEnabled();
  await page.getByRole("button", { name: "Không hữu ích" }).click();
  await page.getByRole("textbox", { name: "Góp ý", exact: true }).fill("Please add details");
  await page.getByRole("dialog").getByRole("button", { name: "Gửi đánh giá" }).click();
  await expect(page.getByRole("button", { name: "Không hữu ích" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await answers.getByRole("button", { name: "Phiên bản trước" }).click();
  await expect(answers.getByText("1 / 2")).toBeVisible();
  await expect(page.getByRole("button", { name: "Không hữu ích" })).toHaveAttribute(
    "aria-pressed",
    "false",
  );
  const questions = page.getByRole("group", { name: "Phiên bản câu hỏi" });
  await questions.getByRole("button", { name: "Phiên bản trước" }).click();
  await expect(
    page.getByRole("main").getByText("Original question", { exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("main").getByText("Original question", { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("main").getByText("Edited question", { exact: true })).toHaveCount(0);
  const stats = await (await page.request.get(`/api/chat/sessions/${session.id}/stats`)).json();
  expect(stats.sends).toBe(3);
  await page
    .getByRole("complementary")
    .getByRole("button", { name: "Thao tác hội thoại Workspace versions" })
    .click();
  await page.getByRole("menuitem", { name: "Đổi tên", exact: true }).click();
  await page.getByRole("textbox", { name: "Tên hội thoại" }).fill("Renamed workspace");
  await page.getByRole("button", { name: "Lưu tên hội thoại" }).click();
  await expect(page.getByRole("banner")).toContainText("Renamed workspace");
  await expect(page.getByRole("link", { name: "Renamed workspace" })).toBeVisible();
  await page.getByRole("button", { name: "Chia sẻ", exact: true }).click();
  await page.getByRole("radio", { name: "Chia sẻ trong tổ chức", exact: true }).check();
  await page.getByRole("dialog").getByRole("button", { name: "Tạo liên kết" }).click();
  await expect(page.getByRole("textbox", { name: "Liên kết chỉ đọc" })).toHaveValue(
    new URL(`/shared/${session.id}`, page.url()).href,
  );
  await page.goto(`/shared/${session.id}`);
  await expect(page.getByRole("heading", { name: "Renamed workspace" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Câu hỏi", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Chỉnh sửa câu hỏi" })).toHaveCount(0);
  await page.goto(ownUrl);
  await page.getByRole("button", { name: "Chia sẻ", exact: true }).click();
  await expect(
    page.getByRole("radio", { name: "Chia sẻ trong tổ chức", exact: true }),
  ).toBeChecked();
  await page.getByRole("radio", { name: "Riêng tư", exact: true }).check();
  await page.getByRole("dialog").getByRole("button", { name: "Thu hồi liên kết" }).click();
  await expect(page.getByRole("dialog")).toContainText("Hội thoại đang riêng tư.");
  await page.goto(`/shared/${session.id}`);
  await expect(page.getByRole("alert")).toContainText("Hội thoại không khả dụng");
  await page.goto(ownUrl);
  await page
    .getByRole("banner")
    .getByRole("button", { name: "Thao tác hội thoại Renamed workspace" })
    .click();
  await page.getByRole("menuitem", { name: "Xóa", exact: true }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Xóa hội thoại" }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("link", { name: "Renamed workspace" })).toHaveCount(0);
});

test("creates and revises private assistants with source, starter and limit settings", async ({
  page,
}) => {
  const id = "40000000-0000-4000-8000-000000000001";
  const sourceId = "40000000-0000-4000-8000-000000000002";
  let saved: Record<string, unknown> | undefined;
  await page.route("**/api/chat/personas**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith("/sources"))
      return route.fulfill({ json: [{ id: sourceId, name: "Employee handbook", type: "FILE" }] });
    if (path.endsWith("/models")) return route.fulfill({ json: [] });
    if (request.method() === "DELETE") {
      saved = undefined;
      return route.fulfill({ status: 204 });
    }
    if (request.method() === "POST" || request.method() === "PUT") {
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      saved = {
        ...request.postDataJSON(),
        id,
        builtin: false,
        editable: true,
        revision: saved ? 1 : 0,
      };
      return route.fulfill({ status: request.method() === "POST" ? 201 : 200, json: saved });
    }
    return route.fulfill({ json: saved ? [saved] : [] });
  });
  await page.goto("/assistants");
  await page.getByRole("button", { name: "Tạo trợ lý" }).click();
  await page.getByLabel("Tên trợ lý", { exact: true }).fill("HR assistant");
  await page.getByLabel("Hướng dẫn", { exact: true }).fill("Answer using the employee handbook.");
  await page
    .getByLabel("Câu hỏi gợi ý")
    .fill(Array.from({ length: 9 }, (_, index) => `Question ${index}`).join("\n"));
  await expect(page.getByRole("alert")).toHaveText("Dùng tối đa 8 câu hỏi gợi ý.");
  await expect(
    page.getByRole("dialog").getByRole("button", { name: "Lưu", exact: true }),
  ).toHaveCount(0);
  expect(saved).toBeUndefined();
  await page
    .getByLabel("Câu hỏi gợi ý")
    .fill("How do I request leave?\nWhat is the expense policy?");
  await page.getByRole("checkbox", { name: "Employee handbook", exact: true }).check();
  await page.getByText("Giới hạn nâng cao", { exact: true }).click();
  await page.getByLabel("Câu trả lời (token)").fill("1000");
  await page.getByRole("dialog").getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByRole("heading", { name: "HR assistant" })).toBeVisible();
  expect(saved).toMatchObject({
    sourceIds: [sourceId],
    outputTokenLimit: 1000,
    starterPrompts: ["How do I request leave?", "What is the expense policy?"],
  });
  await page.getByRole("button", { name: "Chỉnh sửa", exact: true }).click();
  await expect(
    page.getByRole("checkbox", { name: "Employee handbook", exact: true }),
  ).toBeChecked();
  await page.getByLabel("Tên trợ lý", { exact: true }).fill("Updated assistant");
  await page.getByRole("dialog").getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Updated assistant" })).toBeVisible();
  await page.getByRole("button", { name: "Xóa", exact: true }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Xóa trợ lý" }).click();
  await expect(page.getByRole("heading", { name: "Updated assistant" })).toHaveCount(0);
});

test("rechecks shared access without polling the full transcript and hides revoked content", async ({
  page,
}) => {
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Access polling" } })
  ).json();
  await page.request.put(`/api/chat/sessions/${session.id}/sharing`, {
    data: { enabled: true, revision: 0 },
  });
  let accessReads = 0;
  let historyReads = 0;
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (path === `/api/chat/shared/${session.id}`) accessReads++;
    if (path === `/api/chat/shared/${session.id}/messages`) historyReads++;
  });
  await page.clock.install();
  await page.goto(`/shared/${session.id}`);
  await expect(page.getByRole("heading", { name: "Access polling" })).toBeVisible();
  const initialAccess = accessReads;
  const initialHistory = historyReads;
  expect(initialHistory).toBeGreaterThan(0);
  const accessPath = `/api/chat/shared/${session.id}`;
  const reload = page.getByRole("button", { name: "Tải lại hội thoại" });
  const pollResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === accessPath,
  );
  await page.clock.fastForward(31000);
  await (await pollResponse).finished();
  await expect(reload).toBeEnabled();
  await expect.poll(() => accessReads).toBeGreaterThan(initialAccess);
  expect(historyReads).toBe(initialHistory);
  const reloadAccess = page.waitForResponse(
    (response) => new URL(response.url()).pathname === accessPath,
  );
  const reloadHistory = page.waitForResponse(
    (response) => new URL(response.url()).pathname === `${accessPath}/messages`,
  );
  await reload.click();
  await Promise.all([
    reloadAccess.then((response) => response.finished()),
    reloadHistory.then((response) => response.finished()),
  ]);
  await expect(reload).toBeEnabled();
  await expect.poll(() => historyReads).toBeGreaterThan(initialHistory);
  await page.request.put(`/api/chat/sessions/${session.id}/sharing`, {
    data: { enabled: false, revision: 1 },
  });
  const denied = page.waitForResponse(
    (response) => new URL(response.url()).pathname === accessPath && response.status() === 404,
  );
  await page.clock.fastForward(31000);
  await (await denied).finished();
  await expect(page.getByRole("alert")).toContainText("Hội thoại không khả dụng");
  await expect(page.getByRole("heading", { name: "Access polling" })).toHaveCount(0);
});

test("reopening sharing waits for the new revision before allowing save", async ({ page }) => {
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Sharing revisions" } })
  ).json();
  await page.goto(`/chat/${session.id}`);
  await page.getByRole("button", { name: "Chia sẻ", exact: true }).click();
  await expect(page.getByRole("radio", { name: "Riêng tư", exact: true })).toBeChecked();
  await page.getByRole("dialog").getByRole("button", { name: "Đóng", exact: true }).click();
  await page.request.put(`/api/chat/sessions/${session.id}/sharing`, {
    data: { enabled: true, revision: 0 },
  });
  const refresh = Promise.withResolvers<void>();
  let waiting = false;
  await page.route(`**/api/chat/sessions/${session.id}/sharing`, async (route) => {
    if (route.request().method() === "GET") {
      waiting = true;
      await refresh.promise;
    }
    await route.continue();
  });
  try {
    await page.getByRole("button", { name: "Chia sẻ", exact: true }).click();
    await expect.poll(() => waiting).toBe(true);
    await expect(
      page
        .getByRole("dialog")
        .getByRole("button", { name: /Tạo liên kết|Sao chép liên kết|Thu hồi liên kết/ }),
    ).toHaveCount(0);
  } finally {
    refresh.resolve();
  }
  await expect(
    page.getByRole("radio", { name: "Chia sẻ trong tổ chức", exact: true }),
  ).toBeChecked();
  await page.getByRole("radio", { name: "Riêng tư", exact: true }).check();
  await page.getByRole("dialog").getByRole("button", { name: "Thu hồi liên kết" }).click();
  await expect(page.getByRole("dialog")).toContainText("Hội thoại đang riêng tư.");
  await expect(page.getByRole("dialog")).toBeVisible();
  const sharing = await (await page.request.get(`/api/chat/sessions/${session.id}/sharing`)).json();
  expect(sharing).toMatchObject({ enabled: false, revision: 2 });
});

test("malformed shared sources show the unavailable state without crashing the page", async ({
  page,
}) => {
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Invalid sources" } })
  ).json();
  await page.request.put(`/api/chat/sessions/${session.id}/sharing`, {
    data: { enabled: true, revision: 0 },
  });
  await page.route(`**/api/chat/shared/${session.id}/messages*`, (route) =>
    route.fulfill({
      json: new URL(route.request().url()).searchParams.has("after")
        ? []
        : [
            {
              id: "60000000-0000-4000-8000-000000000001",
              role: "ASSISTANT",
              status: "COMPLETED",
              content: "Unvalidated answer",
              sources: [{ invalid: true }],
            },
          ],
    }),
  );
  const crashes: string[] = [];
  page.on("pageerror", (error) => crashes.push(error.message));
  await page.goto(`/shared/${session.id}`);
  await expect(page.getByRole("alert")).toContainText("Hội thoại không khả dụng");
  await expect(page.getByText("Unvalidated answer")).toHaveCount(0);
  expect(crashes).toEqual([]);
});

test("creates a project draft, sends once without remounting, edits instructions and preserves chats on deletion", async ({
  page,
}) => {
  const created: Array<{ projectId?: string }> = [];
  const historyReads: string[] = [];
  const streams: string[] = [];
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (request.method() === "POST" && path === "/api/chat/sessions")
      created.push(request.postDataJSON());
    if (
      request.method() === "GET" &&
      /\/api\/chat\/sessions\/[0-9a-f-]+(?:\/messages)?$/.test(path)
    )
      historyReads.push(path);
    if (path.endsWith("/events")) streams.push(path);
  });
  await page.goto("/projects");
  await page.getByRole("main").getByRole("button", { name: "Tạo dự án", exact: true }).click();
  await page.getByLabel("Tên dự án").fill("Policy review");
  await expect(page.getByLabel("Hướng dẫn dự án")).toHaveCount(0);
  await page.getByRole("dialog").getByRole("button", { name: "Tạo dự án", exact: true }).click();
  await expect(page).toHaveURL(/\/projects\/[0-9a-f-]+$/);
  const projectUrl = page.url();
  const projectId = projectUrl.split("/").at(-1);
  await expect(page.getByRole("heading", { name: "Policy review" })).toBeVisible();
  const input = page.getByRole("textbox", { name: "Câu hỏi", exact: true });
  await input.fill("Draft only");
  expect(created).toHaveLength(0);
  await input.clear();
  await page.getByRole("button", { name: /Hướng dẫn dự án/ }).click();
  await page.getByLabel("Hướng dẫn dự án", { exact: true }).fill("Include effective dates.");
  await page.getByRole("dialog").getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByRole("button", { name: /Hướng dẫn dự án/ })).toContainText(
    "Include effective dates.",
  );
  await page.screenshot({ path: "../.tmp/mem11-ui-project-empty-desktop.png", fullPage: true });
  const composer = await input.elementHandle();
  await input.fill("Project policy question");
  await input.press("Enter");
  await expect(page).toHaveURL(/\/chat\/[0-9a-f-]+$/);
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Dừng trả lời" })).toHaveCount(0);
  expect(created).toEqual([expect.objectContaining({ projectId })]);
  expect(historyReads).toEqual([]);
  expect(streams).toHaveLength(1);
  expect(await composer!.evaluate((element) => element.isConnected)).toBe(true);
  const sessionUrl = page.url();
  const sessionId = sessionUrl.split("/").at(-1);
  await page.goto(projectUrl);
  await expect(
    page.getByRole("main").getByRole("link", { name: /Project policy question/ }),
  ).toBeVisible();
  await page.screenshot({ path: "../.tmp/mem11-ui-project-desktop.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("heading", { name: "Policy review" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.screenshot({ path: "../.tmp/mem11-ui-project-mobile.png", fullPage: true });
  await page.getByRole("button", { name: "Thao tác dự án" }).click();
  await page.getByRole("menuitem", { name: "Xóa dự án", exact: true }).click();
  await page
    .getByRole("alertdialog")
    .getByRole("button", { name: "Xóa dự án", exact: true })
    .click();
  await expect(page).toHaveURL(/\/$/);
  await page.goto(sessionUrl);
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  const savedSession = await (await page.request.get(`/api/chat/sessions/${sessionId}`)).json();
  expect(savedSession.projectId).toBeNull();
  expect((await page.request.get(`/api/chat/projects/${projectId}`)).status()).toBe(404);
});

test("moves and removes a conversation with keyboard menus and desktop drag and drop", async ({
  page,
}) => {
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Movable conversation" } })
  ).json();
  const project = await (
    await page.request.post("/api/chat/projects", {
      data: { name: "Move destination", description: "", instructions: "Use the project context." },
    })
  ).json();
  await page.goto(`/chat/${session.id}`);
  const headerMenu = page
    .getByRole("banner")
    .getByRole("button", { name: "Thao tác hội thoại Movable conversation" });
  await headerMenu.focus();
  await headerMenu.press("Enter");
  const move = page.getByRole("menuitem", { name: "Chuyển vào dự án" });
  await move.focus();
  await move.press("Enter");
  const destination = page.getByRole("radio", { name: "Move destination" });
  await destination.focus();
  await destination.press("Space");
  await expect(destination).toBeChecked();
  await page.getByRole("dialog").getByRole("button", { name: "Chuyển", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(headerMenu).toBeFocused();
  expect(
    (await (await page.request.get(`/api/chat/sessions/${session.id}`)).json()).projectId,
  ).toBe(project.id);
  await page.getByRole("button", { name: "Mở rộng dự án Move destination" }).click();
  const folder = page.locator(`[data-project-id="${project.id}"]`);
  await expect(
    folder.getByRole("link", { name: "Movable conversation", exact: true }),
  ).toBeVisible();
  await headerMenu.click();
  await page.getByRole("menuitem", { name: "Chuyển vào dự án" }).click();
  await page.getByRole("radio", { name: "Ngoài dự án" }).check();
  await page.getByRole("dialog").getByRole("button", { name: "Chuyển", exact: true }).click();
  await expect(folder.getByRole("link", { name: "Movable conversation", exact: true })).toHaveCount(
    0,
  );
  const row = page
    .getByRole("complementary")
    .locator('[data-slot="thread-list-row"]')
    .filter({ has: page.getByRole("link", { name: "Movable conversation", exact: true }) });
  await row.dragTo(folder.getByRole("link", { name: "Move destination", exact: true }));
  await expect(
    folder.getByRole("link", { name: "Movable conversation", exact: true }),
  ).toBeVisible();
  expect(
    (await (await page.request.get(`/api/chat/sessions/${session.id}`)).json()).projectId,
  ).toBe(project.id);
  const stats = await (await page.request.get(`/api/chat/sessions/${session.id}/stats`)).json();
  expect(stats.sends).toBe(0);
  await page.request.delete(`/api/chat/projects/${project.id}?revision=0`);
});

test("preserves rename and inline edit drafts after errors and uses the same request ID on retry", async ({
  page,
}) => {
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Draft recovery" } })
  ).json();
  await page.goto(`/chat/${session.id}`);
  const sidebar = page.getByRole("complementary");
  await sidebar.getByRole("button", { name: "Thao tác hội thoại Draft recovery" }).click();
  await page.getByRole("menuitem", { name: "Đổi tên", exact: true }).click();
  const title = page.getByRole("textbox", { name: "Tên hội thoại" });
  await expect(title).toBeFocused();
  await title.fill("Canceled title");
  await title.press("Escape");
  await expect(sidebar.getByRole("link", { name: "Draft recovery", exact: true })).toBeFocused();
  await sidebar.getByRole("button", { name: "Thao tác hội thoại Draft recovery" }).click();
  await page.getByRole("menuitem", { name: "Đổi tên", exact: true }).click();
  await title.fill("Recovered title");
  let failRename = true;
  await page.route(`**/api/chat/sessions/${session.id}/title`, (route) =>
    failRename ? route.fulfill({ status: 503, json: {} }) : route.continue(),
  );
  await title.press("Enter");
  await expect(sidebar.getByRole("alert")).toBeVisible();
  await expect(title).toHaveValue("Recovered title");
  failRename = false;
  await title.press("Enter");
  await expect(page.getByRole("banner")).toContainText("Recovered title");
  await page.getByRole("textbox", { name: "Câu hỏi", exact: true }).fill("Original draft");
  await page.getByRole("button", { name: "Gửi câu hỏi" }).click();
  await expect(page.getByRole("button", { name: "Chỉnh sửa câu hỏi" })).toBeEnabled();
  await expect(page.getByRole("banner")).toContainText("Recovered title");
  await page.getByRole("button", { name: "Chỉnh sửa câu hỏi" }).click();
  const editor = page.getByRole("textbox", { name: "Nội dung câu hỏi" });
  await expect(editor).toBeFocused();
  await editor.fill("Retry this edited draft");
  let failEdit = true;
  const requests: string[] = [];
  await page.route(`**/api/chat/sessions/${session.id}/messages/*/edit`, (route) => {
    requests.push(route.request().postDataJSON().clientRequestId);
    return failEdit ? route.fulfill({ status: 503, json: {} }) : route.continue();
  });
  await page.getByRole("button", { name: "Lưu và gửi" }).click();
  await expect(page.locator('[data-slot="edit-message"]').getByRole("alert")).toBeVisible();
  await expect(editor).toHaveValue("Retry this edited draft");
  await expect(page.getByRole("button", { name: "Hủy", exact: true })).toBeEnabled();
  await page.screenshot({ path: "../.tmp/mem11-ui-inline-error.png", fullPage: true });
  failEdit = false;
  await page.getByRole("button", { name: "Kiểm tra hội thoại" }).click();
  await expect(page.getByRole("button", { name: "Lưu và gửi" })).toBeEnabled();
  await page.getByRole("button", { name: "Lưu và gửi" }).click();
  await expect(
    page.getByRole("main").getByText("Retry this edited draft", { exact: true }),
  ).toBeVisible();
  expect(requests).toHaveLength(2);
  expect(requests[0]).toBe(requests[1]);
  expect(
    (await (await page.request.get(`/api/chat/sessions/${session.id}/stats`)).json()).sends,
  ).toBe(2);
});

test("shares in one dialog with manual copying fallback and restores keyboard focus", async ({
  page,
}) => {
  const session = await (
    await page.request.post("/api/chat/test-fixture", {
      data: { title: "Sharing clipboard fallback" },
    })
  ).json();
  await page.addInitScript(() =>
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: () => Promise.reject(new DOMException("Denied", "NotAllowedError")) },
    }),
  );
  await page.goto(`/chat/${session.id}`);
  await page.setViewportSize({ width: 390, height: 844 });
  const share = page.getByRole("button", { name: "Chia sẻ", exact: true });
  await share.click();
  await page.getByRole("radio", { name: "Riêng tư", exact: true }).focus();
  await page.keyboard.press("ArrowDown");
  await expect(
    page.getByRole("radio", { name: "Chia sẻ trong tổ chức", exact: true }),
  ).toBeChecked();
  await page.getByRole("button", { name: "Tạo liên kết", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("Chưa sao chép được.");
  await expect(page.getByRole("textbox", { name: "Liên kết chỉ đọc" })).toHaveValue(
    new URL(`/shared/${session.id}`, page.url()).href,
  );
  await page.screenshot({ path: "../.tmp/mem11-ui-sharing-mobile.png", fullPage: true });
  await page.getByRole("button", { name: "Đóng", exact: true }).click();
  await expect(share).toBeFocused();
});

test("keeps feedback drafts on failure, reloads the saved reaction and removes it", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Bạn muốn tìm hiểu điều gì?" })).toBeVisible();
  await page.screenshot({ path: "../.tmp/mem11-ui-new-chat-desktop.png", fullPage: true });
  await page.getByRole("button", { name: "Tenant member", exact: true }).click();
  await page.getByRole("button", { name: "Use dark theme" }).click();
  await page.keyboard.press("Escape");
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Review the policy" } })
  ).json();
  await page.goto(`/chat/${session.id}`);
  await page
    .getByRole("textbox", { name: "Câu hỏi", exact: true })
    .fill("Summarize the policy for new staff.");
  await page.getByRole("button", { name: "Gửi câu hỏi" }).click();
  const negative = page.getByRole("button", { name: "Không hữu ích", exact: true });
  await expect(negative).toBeEnabled();
  await page.screenshot({ path: "../.tmp/mem11-ui-conversation-dark.png", fullPage: true });
  const menu = page
    .getByRole("banner")
    .getByRole("button", { name: "Thao tác hội thoại Review the policy" });
  await menu.click();
  await page.screenshot({ path: "../.tmp/mem11-ui-conversation-menu.png", fullPage: true });
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Chỉnh sửa câu hỏi" }).click();
  await page.screenshot({ path: "../.tmp/mem11-ui-inline-editor.png", fullPage: true });
  await page.getByRole("button", { name: "Hủy", exact: true }).click();
  await negative.click();
  await page.getByRole("button", { name: "Thiếu thông tin", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Góp ý", exact: true })
    .fill("Explain the effective date.");
  let fail = true;
  await page.route(`**/api/chat/sessions/${session.id}/messages/*/feedback`, (route) =>
    fail ? route.fulfill({ status: 503, json: {} }) : route.continue(),
  );
  await page.getByRole("button", { name: "Gửi đánh giá" }).click();
  await expect(page.getByRole("dialog").getByRole("alert")).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Góp ý", exact: true })).toHaveValue(
    "Explain the effective date.",
  );
  fail = false;
  await page.getByRole("button", { name: "Gửi đánh giá" }).click();
  await expect(negative).toHaveAttribute("aria-pressed", "true");
  await page.reload();
  await expect(negative).toHaveAttribute("aria-pressed", "true");
  await negative.click();
  await expect(negative).toHaveAttribute("aria-pressed", "false");
  expect(
    await (await page.request.get(`/api/chat/sessions/${session.id}/feedback`)).json(),
  ).toEqual([]);
});
