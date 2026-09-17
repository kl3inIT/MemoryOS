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
