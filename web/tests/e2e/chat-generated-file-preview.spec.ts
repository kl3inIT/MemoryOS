import { expect, test, type Page } from "@playwright/test";

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
});

// Set to a directory to save a screenshot of each preview for visual review.
const shots = process.env.MEMORYOS_PREVIEW_SHOTS;

async function openFiles(page: Page) {
  const session = await (
    await page.request.post("/api/chat/test-fixture", {
      data: { mode: "files", title: "Tệp tạo ra" },
    })
  ).json();
  await page.goto(`/chat/${session.id}`);
  // The first visit compiles the chat route (KaTeX included) in the dev server.
  await expect(page.getByText("12,48 tỷ ₫")).toBeVisible({ timeout: 30_000 });
}

async function preview(page: Page, filename: string) {
  await page.getByRole("button", { name: `Xem trước ${filename}` }).click();
}

async function shot(page: Page, name: string) {
  if (shots) await page.screenshot({ path: `${shots}/${name}.png` });
}

for (const [label, viewport, colorScheme] of [
  ["desktop", { width: 1440, height: 900 }, "light"],
  ["desktop-dark", { width: 1440, height: 900 }, "dark"],
  ["mobile", { width: 390, height: 844 }, "light"],
] as const) {
  test(`previews every generated file kind on ${label}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.emulateMedia({ colorScheme });
    await openFiles(page);
    await shot(page, `${label}-cards`);
    // The attached file sits above the question, and the answer's LaTeX renders as math.
    await page.getByText("Từ file dữ liệu đính kèm, hãy:").scrollIntoViewIfNeeded();
    await expect(page.locator(".katex").first()).toBeVisible();
    await page.evaluate(() =>
      document.querySelector("[data-aui-quote-selectable='false']")?.scrollIntoView(),
    );
    await shot(page, `${label}-question`);
    const close = () => page.getByRole("button", { name: "Đóng xem trước" }).click();

    await preview(page, "Doanh thu Q3 2026 theo khu vực.xlsx");
    await expect(page.getByRole("tab", { name: "Doanh thu Q3" })).toBeVisible();
    await expect(
      page.getByRole("cell", { name: "Khai trương chi nhánh mới, tăng ca cuối tuần" }).first(),
    ).toBeVisible();
    await expect(page.getByText("3 trang tính").first()).toBeVisible();
    await expect(
      page.getByRole("dialog", { name: "Doanh thu Q3 2026 theo khu vực.xlsx" }),
    ).toBeVisible();
    await shot(page, `${label}-xlsx`);
    await page.getByRole("tab", { name: "Chi phí vận hành và khấu hao tài sản cố định" }).click();
    await expect(page.getByText("Bản xem trước bị cắt bớt")).toBeVisible();
    await shot(page, `${label}-xlsx-sheet2`);
    await page.getByRole("tab", { name: "Ghi chú" }).click();
    await expect(page.getByText("Trang tính trống")).toBeVisible();
    await close();

    await preview(page, "doanh-thu-chi-tiet.csv");
    await expect(page.getByRole("cell", { name: "12 Lê Lợi, Quận 1, TP.HCM" })).toBeVisible();
    await expect(page.getByRole("cell", { name: 'Siêu thị "Xanh" Hà Nội' })).toBeVisible();
    await shot(page, `${label}-csv`);
    await close();

    await preview(page, "Báo cáo quý 3.docx");
    await expect(page.getByText("Báo cáo doanh thu quý 3/2026")).toBeVisible();
    await shot(page, `${label}-docx`);
    await close();

    await preview(page, "Tài chính Q3.pdf");
    await expect(page.locator(".react-pdf__Page").first()).toBeVisible();
    await page.waitForTimeout(800);
    await shot(page, `${label}-pdf`);
    await close();

    await preview(page, "bieu_do_doanh_thu.png");
    await expect(page.getByRole("img", { name: "bieu_do_doanh_thu.png" })).toBeVisible();
    await shot(page, `${label}-png`);
    await close();

    await preview(page, "phan_tich_doanh_thu.py");
    await expect(page.getByText(/Đọc dữ liệu doanh thu quý 3/)).toBeVisible();
    await page.waitForTimeout(800);
    await shot(page, `${label}-py`);
    await close();

    await preview(page, "Trình chiếu quý 3.pptx");
    await expect(page.getByText("Bản xem trước PDF của trình chiếu")).toBeVisible();
    await expect(page.locator(".react-pdf__Page").first()).toBeVisible();
    await page.waitForTimeout(800);
    await shot(page, `${label}-pptx`);
  });
}

test("draws captured charts interactively with the PNG as the static view", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await openFiles(page);
  const line = page
    .locator("[data-slot=generated-chart]")
    .filter({ hasText: "Doanh thu theo tháng" });
  await line.scrollIntoViewIfNeeded();
  await expect(line.locator(".recharts-line")).toHaveCount(3);
  await expect(line.getByText("Miền Trung")).toBeVisible();
  const pie = page
    .locator("[data-slot=generated-chart]")
    .filter({ hasText: "Tỷ trọng doanh thu quý 3" });
  await expect(pie.locator(".recharts-pie-sector")).toHaveCount(3);
  await page.waitForTimeout(600);
  await line.evaluate((element) => element.scrollIntoView({ block: "center" }));
  await line.screenshot(shots ? { path: `${shots}/chart-line.png` } : {});
  await pie.evaluate((element) => element.scrollIntoView({ block: "start" }));
  await page.waitForTimeout(300);
  await pie.screenshot(shots ? { path: `${shots}/chart-pie.png` } : {});
  await line.evaluate((element) => element.scrollIntoView({ block: "center" }));
  await line.hover({ position: { x: 300, y: 160 } });
  await page.waitForTimeout(300);
  await line.screenshot(shots ? { path: `${shots}/chart-line-hover.png` } : {});
  await line.getByRole("tab", { name: "Ảnh tĩnh" }).click();
  await expect(line.getByRole("img", { name: "Doanh thu theo tháng" })).toBeVisible();
  await shot(page, "chart-page");
});

// Runs against the production build under nginx.conf's Content-Security-Policy (MEMORYOS_E2E_PREVIEW=1); the dev
// server injects CSS through inline <style> elements that the policy refuses.
test("charts and file previews work under the deployment CSP", async ({ page }) => {
  test.skip(
    process.env.MEMORYOS_E2E_PREVIEW !== "1",
    "needs the production build (MEMORYOS_E2E_PREVIEW=1)",
  );
  await page.setViewportSize({ width: 1440, height: 900 });
  await openFiles(page);

  const line = page
    .locator("[data-slot=generated-chart]")
    .filter({ hasText: "Doanh thu theo tháng" });
  await line.scrollIntoViewIfNeeded();
  await expect(line.locator(".recharts-line")).toHaveCount(3);
  // Series colors survive: an inline <style> would be dropped by style-src 'self'.
  const stroke = await line
    .locator(".recharts-line-curve")
    .first()
    .evaluate((path) => getComputedStyle(path).stroke);
  expect(stroke).toBe("rgb(59, 130, 246)");
  await line.screenshot(shots ? { path: `${shots}/csp-chart.png` } : {});

  await preview(page, "bieu_do_doanh_thu.png");
  const image = page.getByRole("img", { name: "bieu_do_doanh_thu.png" });
  await expect(image).toBeVisible();
  await expect
    .poll(() => image.evaluate((img: HTMLImageElement) => img.naturalWidth))
    .toBeGreaterThan(0);
  await shot(page, "csp-png");
  await page.getByRole("button", { name: "Đóng xem trước" }).click();

  await preview(page, "Tài chính Q3.pdf");
  await expect(page.locator(".react-pdf__Page canvas").first()).toBeVisible({ timeout: 15_000 });
  await shot(page, "csp-pdf");
  await page.getByRole("button", { name: "Đóng xem trước" }).click();

  await preview(page, "Báo cáo quý 3.docx");
  await expect(page.getByText("Báo cáo doanh thu quý 3/2026")).toBeVisible();
  // Word page geometry comes from docx-preview's inline styles; blocked, a page would have no padding.
  const padding = await page
    .locator("[data-slot=docx-preview] section.docx")
    .first()
    .evaluate((section) => getComputedStyle(section).paddingLeft);
  expect(parseFloat(padding)).toBeGreaterThan(20);
  await shot(page, "csp-docx");
});
