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

test("edits, regenerates, selects saved branches, rates, shares, revokes and deletes", async ({
  page,
}) => {
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Workspace versions" } })
  ).json();
  const ownUrl = `/chat/${session.id}`;
  await page.goto(ownUrl);
  await page.getByRole("textbox", { name: "Message", exact: true }).fill("Original question");
  await page.getByRole("button", { name: "Send message" }).click();
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
  await expect(page.getByRole("button", { name: "Đánh giá câu trả lời" })).toBeEnabled();
  await page.getByRole("button", { name: "Đánh giá câu trả lời" }).click();
  await page.getByLabel("Mức độ hữu ích").selectOption("negative");
  await page.getByRole("textbox", { name: "Góp ý", exact: true }).fill("Please add details");
  await page.getByRole("dialog").getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByRole("button", { name: "Đánh giá câu trả lời" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await answers.getByRole("button", { name: "Phiên bản trước" }).click();
  await expect(answers.getByText("1 / 2")).toBeVisible();
  await expect(page.getByRole("button", { name: "Đánh giá câu trả lời" })).toHaveAttribute(
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
  await page.getByRole("button", { name: "Đổi tên", exact: true }).click();
  await page.getByRole("textbox", { name: "Tên hội thoại" }).fill("Renamed workspace");
  await page.getByRole("dialog").getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByRole("link", { name: "Renamed workspace" })).toBeVisible();
  await page.getByRole("button", { name: "Chia sẻ", exact: true }).click();
  await page.getByRole("combobox", { name: "Thay đổi quyền chia sẻ" }).selectOption("shared");
  await page.getByRole("dialog").getByRole("button", { name: "Lưu", exact: true }).click();
  await page.goto(`/shared/${session.id}`);
  await expect(page.getByRole("heading", { name: "Renamed workspace" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Message", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Chỉnh sửa câu hỏi" })).toHaveCount(0);
  await page.goto(ownUrl);
  await page.getByRole("button", { name: "Chia sẻ", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "Thay đổi quyền chia sẻ" })).toHaveValue(
    "shared",
  );
  await page.getByRole("combobox", { name: "Thay đổi quyền chia sẻ" }).selectOption("private");
  await page.getByRole("dialog").getByRole("button", { name: "Lưu", exact: true }).click();
  await page.goto(`/shared/${session.id}`);
  await expect(page.getByRole("alert")).toContainText("Hội thoại không khả dụng");
  await page.goto(ownUrl);
  await page.getByRole("button", { name: "Xóa", exact: true }).click();
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
  await page.clock.fastForward(31000);
  await expect.poll(() => accessReads).toBeGreaterThan(initialAccess);
  expect(historyReads).toBe(initialHistory);
  await page.getByRole("button", { name: "Tải lại hội thoại" }).click();
  await expect.poll(() => historyReads).toBeGreaterThan(initialHistory);
  await page.request.put(`/api/chat/sessions/${session.id}/sharing`, {
    data: { enabled: false, revision: 1 },
  });
  await page.clock.fastForward(31000);
  await expect(page.getByRole("alert")).toContainText("Hội thoại không khả dụng");
  await expect(page.getByRole("heading", { name: "Access polling" })).toHaveCount(0);
});

test("reopening sharing waits for the new revision before allowing save", async ({ page }) => {
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Sharing revisions" } })
  ).json();
  await page.goto(`/chat/${session.id}`);
  await page.getByRole("button", { name: "Chia sẻ", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "Thay đổi quyền chia sẻ" })).toHaveValue(
    "private",
  );
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
      page.getByRole("dialog").getByRole("button", { name: "Lưu", exact: true }),
    ).toHaveCount(0);
  } finally {
    refresh.resolve();
  }
  await expect(page.getByRole("combobox", { name: "Thay đổi quyền chia sẻ" })).toHaveValue(
    "shared",
  );
  await page.getByRole("combobox", { name: "Thay đổi quyền chia sẻ" }).selectOption("private");
  await page.getByRole("dialog").getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
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

test("creates and edits project instructions and retains its conversation when deleted", async ({
  page,
}) => {
  const id = "50000000-0000-4000-8000-000000000001";
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Project conversation" } })
  ).json();
  let saved: Record<string, unknown> | undefined;
  await page.route("**/api/chat/projects**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname.endsWith("/sessions")) {
      if (request.method() === "POST")
        return route.fulfill({ status: 201, json: { ...session, projectId: id } });
      return route.fulfill({ json: [session] });
    }
    if (request.method() === "DELETE") {
      saved = undefined;
      return route.fulfill({ status: 204 });
    }
    if (request.method() === "POST" || request.method() === "PUT") {
      saved = {
        ...request.postDataJSON(),
        id,
        revision: saved ? 1 : 0,
        updatedAt: new Date().toISOString(),
      };
      return route.fulfill({ status: request.method() === "POST" ? 201 : 200, json: saved });
    }
    return route.fulfill({ json: url.pathname.endsWith(id) ? saved : saved ? [saved] : [] });
  });
  await page.goto("/projects");
  await page.getByRole("button", { name: "Tạo dự án" }).click();
  await page.getByLabel("Tên dự án").fill("Policy review");
  await page.getByLabel("Hướng dẫn dự án").fill("Summarize each policy for new staff.");
  await page.getByRole("dialog").getByRole("button", { name: "Lưu", exact: true }).click();
  await page.getByRole("link", { name: /Policy review/ }).click();
  await page.getByText("Hướng dẫn dự án", { exact: true }).click();
  await expect(page.getByText("Summarize each policy for new staff.")).toBeVisible();
  await page.getByRole("button", { name: "Chỉnh sửa dự án" }).click();
  await page.getByLabel("Hướng dẫn dự án").fill("Include effective dates.");
  await page.getByRole("dialog").getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByText("Include effective dates.")).toBeVisible();
  await expect(
    page.getByRole("main").getByRole("link", { name: "Project conversation" }),
  ).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("heading", { name: "Policy review" })).toBeVisible();
  await page.screenshot({ path: "../.tmp/mem11-project-mobile.png", fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.getByRole("button", { name: "Xóa", exact: true }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Xóa dự án" }).click();
  await expect(page).toHaveURL(/\/projects\/?$/);
  await page.goto(`/chat/${session.id}`);
  await expect(page.getByRole("textbox", { name: "Message", exact: true })).toBeVisible();
});
