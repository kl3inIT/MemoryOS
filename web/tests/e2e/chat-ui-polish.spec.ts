import { expect, test } from "@playwright/test";
import { fixtureModels } from "../fixtures/chat-data";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        actorId: "e62a621f-41d2-4853-aa76-b600dafd8e34",
        authorizationVersion: 1,
        uiLanguage: "vi",
        tenant: { displayName: "Test tenant", role: "MEMBER" },
        capabilities: ["MODELS_MANAGE"],
        scopedCapabilities: [],
      },
    }),
  );
  await page.route(/\/api\/chat\/web(?:\?|$)/, (route) =>
    route.fulfill({
      json: {
        searchAvailable: true,
        inheritedModelId: fixtureModels[0]!.id,
        automaticModelIds: [fixtureModels[0]!.id],
        requiredModelIds: [fixtureModels[0]!.id],
      },
    }),
  );
  await page.emulateMedia({ colorScheme: "dark" });
});

for (const width of [1440, 390]) {
  test(`Actions and current conversation search at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const session = await (
      await page.request.post("/api/chat/test-fixture", { data: { title: "HUT Q1 2026" } })
    ).json();
    await page.goto(`/chat/${session.id}`);
    const actions = page.getByRole("button", { name: "Hành động", exact: true });
    await actions.click();
    await expect(
      page.getByRole("combobox", { name: "" }).filter({ hasNotText: /GPT/ }),
    ).toHaveCount(1);
    await expect(page.getByRole("option", { name: "Tìm kiếm Web", exact: true })).toBeVisible();
    await expect(page.getByRole("option")).toHaveCount(1);
    await page.screenshot({ path: `../output/playwright/actions-${width}.png` });
    await page.getByRole("button", { name: "Tùy chọn Web" }).click();
    await page.getByRole("option", { name: "Tự động dùng Web" }).click();
    await expect(actions).toContainText("Web");
    await page
      .getByRole("textbox", { name: "Câu hỏi", exact: true })
      .fill("HUT Q1 2026 và HUT Q2 2026");
    await page.getByRole("button", { name: "Gửi câu hỏi" }).click();
    await expect(page.getByRole("button", { name: "Chỉnh sửa câu hỏi" })).toBeEnabled();
    const find = page.getByRole("button", { name: "Tìm trong hội thoại", exact: true });
    await find.click();
    const input = page.getByRole("textbox", { name: "Tìm trong hội thoại này…" });
    await input.fill("HUT");
    const finder = page.locator('[data-slot="conversation-search"]');
    await expect(finder.getByRole("status")).toHaveText("1/2");
    await expect(page.locator("[data-chat-search-match]")).toHaveCount(1);
    await input.press("Enter");
    await expect(finder.getByRole("status")).toHaveText("2/2");
    await page.screenshot({ path: `../output/playwright/conversation-find-${width}.png` });
    await input.fill("no-such-match");
    await expect(finder.getByRole("button", { name: "Kết quả tiếp theo" })).toBeDisabled();
    await input.press("Escape");
    await expect(find).toBeFocused();
    await expect(page.locator("[data-chat-search-match]")).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  });

  test(`Search/crawler sections and shared Exa credential at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    let configured = false;
    const saves: { credentialAction: string }[] = [];
    await page.route("**/api/chat/web/connections", (route) =>
      route.fulfill({
        json: configured
          ? [
              {
                provider: "EXA",
                credentialConfigured: true,
                revision: saves.length,
                searchActive: false,
                contentActive: false,
              },
            ]
          : [],
      }),
    );
    await page.route("**/api/chat/web/connections/EXA", async (route) => {
      saves.push(route.request().postDataJSON());
      configured = true;
      await route.fulfill({ json: {} });
    });
    await page.goto("/settings/web");
    const search = page.getByRole("region", { name: "Công cụ tìm kiếm", exact: true });
    const reader = page.getByRole("region", { name: "Trình đọc trang Web", exact: true });
    await expect(search.getByRole("button", { name: "Kết nối", exact: true })).toHaveCount(6);
    await expect(reader.getByRole("button", { name: "Kết nối", exact: true })).toHaveCount(3);
    await expect(reader).toContainText("Trình đọc MemoryOS");
    await page.screenshot({ path: `../output/playwright/web-settings-search-${width}.png` });
    const exaSearch = search.getByRole("region", { name: "Exa", exact: true });
    await exaSearch.getByRole("button", { name: "Kết nối", exact: true }).click();
    await exaSearch.getByLabel("Khóa API").fill("fixture-not-a-secret");
    await exaSearch.getByRole("button", { name: "Lưu", exact: true }).click();
    const exaReader = reader.getByRole("region", { name: "Exa", exact: true });
    await exaReader.getByRole("button", { name: "Cấu hình", exact: true }).click();
    await expect(exaReader.getByLabel("Khóa API")).toHaveValue("");
    await expect(exaReader.getByLabel("Khóa API")).toHaveAttribute(
      "placeholder",
      "Đã lưu khóa; để trống để giữ nguyên",
    );
    await exaReader.getByRole("button", { name: "Lưu", exact: true }).click();
    expect(saves.map((save) => save.credentialAction)).toEqual(["REPLACE", "KEEP"]);
    await reader.scrollIntoViewIfNeeded();
    await page.screenshot({ path: `../output/playwright/web-settings-reader-${width}.png` });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  });
}
