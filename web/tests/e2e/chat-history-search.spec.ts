import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        actorId: "e62a621f-41d2-4853-aa76-b600dafd8e34",
        authorizationVersion: 1,
        uiLanguage: "vi",
        tenant: { displayName: "Test tenant", role: "MEMBER" },
        capabilities: [],
        scopedCapabilities: [],
      },
    }),
  );
  await page.emulateMedia({ colorScheme: "dark" });
});

for (const width of [1440, 390]) {
  test(`finds server message-only matches outside loaded history at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    const oldSession = await (
      await page.request.post("/api/chat/test-fixture", {
        data: { title: "Báo cáo đã lưu năm trước" },
      })
    ).json();
    const lastSession = await (
      await page.request.post("/api/chat/test-fixture", {
        data: { title: "Hội thoại trang tiếp theo" },
      })
    ).json();
    await page.route(
      (url) => url.pathname === "/api/chat/sessions",
      (route) => route.fulfill({ json: [] }),
    );
    const offsets: number[] = [];
    await page.route("**/api/chat/sessions/search?*", async (route) => {
      const url = new URL(route.request().url());
      const query = url.searchParams.get("query");
      const offset = Number(url.searchParams.get("offset"));
      if (query !== "zebratail") return route.fulfill({ json: { items: [], hasMore: false } });
      offsets.push(offset);
      // The server marks matched tokens with U+E000/U+E001.
      const snippet = `Báo cáo ${String.fromCharCode(0xe000)}zebratail${String.fromCharCode(0xe001)} đã lưu`;
      await route.fulfill({
        json: {
          items: Array.from({ length: Number(url.searchParams.get("limit")) }, (_, i) => ({
            session:
              i === 0
                ? oldSession
                : i === 1
                  ? lastSession
                  : { ...oldSession, id: crypto.randomUUID(), title: `Kết quả máy chủ ${i}` },
            snippet: i === 0 ? snippet : null,
          })),
          hasMore: true,
        },
      });
    });
    await page.goto("/");
    if (width === 390) await page.getByRole("button", { name: "Mở điều hướng" }).click();
    await expect(page.getByRole("link", { name: oldSession.title, exact: true })).toHaveCount(0);
    // The trigger is the first row of the sidebar.
    await page.getByRole("button", { name: "Tìm kiếm", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Tìm kiếm", exact: true });
    await expect(dialog.getByRole("option", { name: "Chủ đề mới" })).toBeVisible();
    await dialog.getByRole("combobox").fill("zebratail");
    await expect(dialog.getByRole("option", { name: oldSession.title })).toBeVisible();
    await expect(dialog.getByRole("option", { name: "Chủ đề mới" })).toHaveCount(0);
    await expect(dialog.getByRole("option", { name: oldSession.title }).locator("mark")).toHaveText(
      "zebratail",
    );
    // Server found body text; cmdk must not discard it because the title lacks the query. A query shows five
    // topics, and the history page holds the rest.
    await expect(dialog.getByRole("option", { name: /Kết quả máy chủ/ })).toHaveCount(3);
    await page.screenshot({ path: `../output/playwright/history-search-${width}.png` });
    await dialog.getByRole("option", { name: "Xem tất cả chủ đề khớp" }).click();
    await expect(page).toHaveURL("/history?q=zebratail");
    await expect(dialog).toBeHidden();
    await page
      .getByRole("main")
      .getByRole("link", { name: new RegExp(lastSession.title) })
      .click();
    await expect(page).toHaveURL(`/chat/${lastSession.id}`);
    expect(offsets).toEqual([0, 0]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  });
}

test("search debounce hides old matches, handles errors and restores focus", async ({ page }) => {
  const oldSession = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Old result" } })
  ).json();
  const newSession = { ...oldSession, id: crypto.randomUUID(), title: "New result" };
  let releaseOld!: () => void;
  const oldGate = new Promise<void>((resolve) => {
    releaseOld = resolve;
  });
  let failure = true;
  await page.route("**/api/chat/sessions/search?*", async (route) => {
    const query = new URL(route.request().url()).searchParams.get("query");
    if (query === "old") {
      await oldGate;
      await route
        .fulfill({ json: { items: [{ session: oldSession, snippet: null }], hasMore: false } })
        .catch(() => {});
      return;
    }
    if (query === "failure" && failure) {
      failure = false;
      return route.fulfill({ status: 503, json: {} });
    }
    await route.fulfill({
      json: { items: query ? [{ session: newSession, snippet: null }] : [], hasMore: false },
    });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Thu gọn thanh bên" }).click();
  const trigger = page.getByRole("button", { name: "Tìm kiếm", exact: true });
  await trigger.click();
  const dialog = page.getByRole("dialog", { name: "Tìm kiếm", exact: true });
  const input = dialog.getByRole("combobox");
  const requested = page.waitForRequest(
    (request) =>
      request.url().includes("/sessions/search?") &&
      new URL(request.url()).searchParams.get("query") === "old",
  );
  await input.fill("old");
  await requested;
  await input.fill("new");
  await expect(dialog.getByRole("option", { name: "New result" })).toBeVisible();
  releaseOld();
  await expect(dialog.getByRole("option", { name: "Old result" })).toHaveCount(0);
  await input.fill("failure");
  await expect(dialog.getByRole("alert")).toBeVisible();
  await dialog.getByRole("button", { name: "Thử lại" }).click();
  await expect(dialog.getByRole("option", { name: "New result" })).toBeVisible();
  await input.press("Escape");
  await expect(trigger).toBeFocused();
});
