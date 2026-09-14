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
  test(`composer plus menu and current conversation search at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const session = await (
      await page.request.post("/api/chat/test-fixture", { data: { title: "HUT Q1 2026" } })
    ).json();
    await page.goto(`/chat/${session.id}`);
    // One `+` entry for files and tools; the model picker sits beside Send.
    const add = page.getByRole("button", { name: "Thêm vào câu hỏi", exact: true });
    const send = page.getByRole("button", { name: "Gửi câu hỏi" });
    const picker = page.getByRole("combobox", { name: "Chọn mô hình" });
    expect((await add.boundingBox())!.x).toBeLessThan((await picker.boundingBox())!.x);
    expect((await picker.boundingBox())!.x).toBeLessThan((await send.boundingBox())!.x);
    await add.click();
    await expect(page.getByRole("button", { name: "Tải tệp lên", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Chọn tệp đã có", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Tìm kiếm Web", exact: true })).toBeVisible();
    await page.screenshot({ path: `../output/playwright/composer-menu-${width}.png` });
    await page.getByRole("button", { name: "Tùy chọn Web" }).click();
    await page.getByRole("radio", { name: "Tự động dùng Web" }).click();
    await expect(page.getByRole("button", { name: "Tắt Web" })).toBeVisible();
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
    await page.goto("/admin/web-search");
    if (width === 1440) {
      const administration = page.getByRole("navigation", { name: "Điều hướng quản trị" });
      await expect(administration.getByRole("link", { name: "Tìm kiếm Web" })).toBeVisible();
    }
    const search = page.getByRole("region", { name: "Công cụ tìm kiếm", exact: true });
    const reader = page.getByRole("region", { name: "Trình đọc trang Web", exact: true });
    await expect(search.getByRole("button", { name: "Kết nối", exact: true })).toHaveCount(7);
    await expect(reader.getByRole("button", { name: "Kết nối", exact: true })).toHaveCount(3);
    await expect(reader).toContainText("Trình đọc MemoryOS");
    await page.screenshot({ path: `../output/playwright/web-settings-search-${width}.png` });
    const dialog = page.getByRole("dialog");
    const exaSearch = search.getByRole("region", { name: "Exa", exact: true });
    await exaSearch.getByRole("button", { name: "Kết nối", exact: true }).click();
    await dialog.getByLabel("Khóa API").fill("fixture-not-a-secret");
    await dialog.getByRole("button", { name: "Lưu", exact: true }).click();
    await expect(dialog).toBeHidden();
    const exaReader = reader.getByRole("region", { name: "Exa", exact: true });
    await exaReader.getByRole("button", { name: "Cấu hình", exact: true }).click();
    await expect(dialog.getByLabel("Khóa API")).toHaveValue("");
    await expect(dialog.getByLabel("Khóa API")).toHaveAttribute(
      "placeholder",
      "Đã lưu khóa; để trống để giữ nguyên",
    );
    await dialog.getByRole("button", { name: "Lưu", exact: true }).click();
    await expect(dialog).toBeHidden();
    expect(saves.map((save) => save.credentialAction)).toEqual(["REPLACE", "KEEP"]);
    await reader.scrollIntoViewIfNeeded();
    await page.screenshot({ path: `../output/playwright/web-settings-reader-${width}.png` });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  });
}
