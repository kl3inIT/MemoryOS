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
  await expect(page.getByText("12,48 tỷ ₫")).toBeVisible();
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
    const close = () =>
      page
        .getByRole("button", { name: /Đóng/ })
        .first()
        .click();

    await preview(page, "Doanh thu Q3 2026 theo khu vực.xlsx");
    await expect(page.getByRole("tab", { name: "Doanh thu Q3" })).toBeVisible();
    await expect(
      page.getByRole("cell", { name: "Khai trương chi nhánh mới, tăng ca cuối tuần" }).first(),
    ).toBeVisible();
    await expect(page.getByText("40 dòng · 12 cột")).toBeVisible();
    await shot(page, `${label}-xlsx`);
    await page.getByRole("tab", { name: "Chi phí vận hành và khấu hao tài sản cố định" }).click();
    await expect(page.getByText(/bản xem trước bị cắt bớt/)).toBeVisible();
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
    await expect(
      page.getByText("Chưa xem trước được loại tệp này. Hãy tải tệp xuống để mở."),
    ).toBeVisible();
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
