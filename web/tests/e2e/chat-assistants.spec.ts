import { expect, test, type Page } from "@playwright/test";

// The sidebar's assistants and their topics (MEM-273) against the fixture backend, whose one assistant is MemoryOS.
const builtin = "00000000-0000-4000-8000-00000000b017";

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

async function sessionIds(page: Page) {
  const sessions = (await (
    await page.request.get(`/api/chat/sessions?personaId=${builtin}&limit=100`)
  ).json()) as { id: string }[];
  return sessions.map((session) => session.id);
}

test("opens an assistant at its latest topic in its own sidebar and returns to the home", async ({
  page,
}) => {
  await page.request.post("/api/chat/test-fixture", { data: { title: "Quy trình mua sắm 2026" } });
  await page.goto("/");
  const sidebar = page.getByRole("complementary");
  const projects = sidebar.getByRole("button", { name: "Danh sách dự án" });
  const assistants = sidebar.locator("section[aria-labelledby=sidebar-assistants]");
  const recent = sidebar.locator("section[aria-labelledby=sidebar-recent]");
  await expect(recent).toBeVisible();
  // Projects, then the assistants with MemoryOS first, then the recent topics.
  const top = async (locator: typeof projects) => (await locator.boundingBox())!.y;
  expect(await top(projects)).toBeLessThan(await top(assistants));
  expect(await top(assistants)).toBeLessThan(await top(recent));
  const memoryos = assistants.locator('a[href^="/chat/"], a[href^="/assistants/"]').first();
  await expect(memoryos).toContainText("MemoryOS");

  // The fixture is shared by the specs, so the latest topic is whichever the assistants list names.
  const latest = (await (await page.request.get("/api/chat/assistants")).json())[0].latestTopic;
  await memoryos.click();
  await expect(page).toHaveURL(`/chat/${latest.sessionId}`);
  const back = sidebar.getByRole("button", { name: "Về danh sách trợ lý" });
  await expect(back).toBeVisible();
  const topics = sidebar.getByRole("group", { name: "Chủ đề của MemoryOS", exact: true });
  await expect(topics.locator(`a[href="/chat/${latest.sessionId}"]`)).toHaveAttribute(
    "aria-current",
    "page",
  );

  await back.click();
  await expect(page).toHaveURL(`/chat/${latest.sessionId}`);
  await expect(assistants).toBeVisible();
});

test("writes a new topic down only with its first question", async ({ page }) => {
  // Other specs share the fixture, so the page's own requests tell whether it wrote a session.
  const created: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "POST" && new URL(request.url()).pathname === "/api/chat/sessions")
      created.push(request.url());
  });
  await page.goto(`/assistants/${builtin}/new`);
  const input = page.getByRole("textbox", { name: "Câu hỏi", exact: true });
  await expect(input).toBeVisible();
  await expect(
    page.getByRole("complementary").getByRole("link", { name: "Chủ đề mới" }),
  ).toHaveAttribute("aria-current", "page");
  expect(created).toHaveLength(0);

  await input.fill("Định mức công tác phí đi Đà Nẵng?");
  await input.press("Enter");
  await expect(page).toHaveURL(/\/chat\/[0-9a-f-]+$/);
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  expect(created).toHaveLength(1);
  expect(await sessionIds(page)).toContain(page.url().split("/").at(-1));
});

test("keeps a gone assistant's pin and opens its topic without a composer", async ({ page }) => {
  const topic = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Điều khoản phạt" } })
  ).json();
  const gone = "00000000-0000-4000-8000-0000000000aa";
  await page.route("**/api/chat/assistants", async (route) => {
    const rows = await (await route.fetch()).json();
    await route.fulfill({
      json: [
        ...rows,
        {
          id: gone,
          name: "Pháp chế hợp đồng",
          description: "",
          avatarSeed: "law",
          hasAvatar: false,
          builtin: false,
          ownerName: null,
          usable: false,
          latestTopic: {
            sessionId: topic.id,
            title: topic.title,
            lastMessageAt: topic.updatedAt,
            preview: "",
            replyRunning: false,
          },
        },
      ],
    });
  });
  // The topic belongs to the gone assistant wherever it is read.
  const owned = (session: { id: string; personaId: string }) =>
    session.id === topic.id ? { ...session, personaId: gone } : session;
  await page.route(`**/api/chat/sessions/${topic.id}`, async (route) => {
    await route.fulfill({ json: owned(await (await route.fetch()).json()) });
  });
  await page.route("**/api/chat/sessions?*", async (route) => {
    await route.fulfill({ json: (await (await route.fetch()).json()).map(owned) });
  });
  await page.goto("/");
  const row = page
    .locator("section[aria-labelledby=sidebar-assistants]")
    .getByRole("link", { name: /Pháp chế hợp đồng/ });
  await expect(row).toContainText("Không còn dùng được");
  await row.click();
  await expect(page).toHaveURL(`/chat/${topic.id}`);
  await expect(page.getByRole("complementary").getByText("Không còn dùng được")).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Câu hỏi", exact: true })).toHaveCount(0);
});

test("filters the history by assistant", async ({ page }) => {
  const topic = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Lợi nhuận gộp quý 3" } })
  ).json();
  await page.goto(`/history?assistant=${builtin}`);
  await expect(
    page.getByRole("main").getByRole("link", { name: new RegExp(topic.title) }),
  ).toBeVisible();
  await page.getByRole("combobox", { name: "Lọc theo trợ lý" }).selectOption("");
  await expect(page).toHaveURL("/history");
  await page.goto("/history?assistant=00000000-0000-4000-8000-0000000000ab");
  await expect(page.getByText("Không có chủ đề nào khớp")).toBeVisible();
});
