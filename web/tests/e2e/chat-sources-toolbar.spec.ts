import { expect, test } from "@playwright/test";
import { fixtureSource } from "../fixtures/chat-data";

for (const width of [1440, 390]) {
  test(`Onyx-style grouped sources keep the message panel at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ colorScheme: "dark" });
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
    // Exercise fallback deterministically without contacting a favicon service.
    await page.route("https://icons.duckduckgo.com/**", (route) => route.abort());
    const session = await (
      await page.request.post("/api/chat/test-fixture", {
        data: { title: "Nguồn tài liệu và Web" },
      })
    ).json();
    await page.goto(`/chat/${session.id}`);
    await page
      .getByRole("textbox", { name: "Câu hỏi", exact: true })
      .fill("Đối chiếu tài liệu với nguồn Web");
    await page.getByRole("button", { name: "Gửi câu hỏi" }).click();
    await expect(page.getByRole("button", { name: "Chỉnh sửa câu hỏi" })).toBeEnabled();
    const web = (citationId: number, url: string) => ({
      citationId,
      title: `Web ${citationId}`,
      documentId: null,
      generation: null,
      fileId: null,
      fileLocation: null,
      startOrdinal: 0,
      endOrdinal: 0,
      provenance: [],
      web: { url, excerpt: "Nội dung nguồn Web đã lưu.", retrievedAt: "2026-09-13T00:00:00Z" },
    });
    await page.route(`**/api/chat/sessions/${session.id}/messages?*`, async (route) => {
      const response = await route.fetch();
      const messages = await response.json();
      await route.fulfill({
        json: messages.map((message: { role: string }) =>
          message.role === "ASSISTANT"
            ? {
                ...message,
                content: "Thông tin từ tài liệu nội bộ [1] và các trang Web [2] [3].",
                sources: [
                  fixtureSource,
                  web(2, "https://react.dev/learn"),
                  web(3, "https://spring.io/projects"),
                ],
              }
            : message,
        ),
      });
    });
    await page.reload();
    const sources = page.getByRole("button", { name: "Nguồn 3", exact: true });
    await expect(sources.getByText("Nguồn", { exact: true })).toBeVisible();
    await expect(sources.locator('[data-slot="source-icon-stack"] > span')).toHaveCount(3);
    await expect(sources.locator('[data-slot="source-icon-fallback"]')).toHaveCount(2);
    const sourceBox = await sources.boundingBox();
    const copyBox = await page.getByRole("button", { name: "Sao chép câu trả lời" }).boundingBox();
    expect(Math.abs(sourceBox!.y - copyBox!.y)).toBeLessThan(2);
    await page.screenshot({ path: `../output/playwright/sources-toolbar-${width}.png` });
    await sources.click();
    const panel =
      width === 390 ? page.getByRole("dialog") : page.getByRole("complementary", { name: "Nguồn" });
    await expect(panel).toBeVisible();
    await expect(panel.locator('[data-slot="source-row"]')).toHaveCount(3);
    await page.screenshot({ path: `../output/playwright/source-reference-list-${width}.png` });
    await panel.getByRole("button", { name: "Đọc nguồn 2: Web 2" }).click();
    await expect(panel).toContainText("Nội dung nguồn Web đã lưu.");
    await page.screenshot({ path: `../output/playwright/source-web-reader-${width}.png` });
    await expect(panel.getByRole("link", { name: /Mở trang gốc/ })).toHaveAttribute(
      "href",
      "https://react.dev/learn",
    );
    if (width === 390) await page.keyboard.press("Escape");
    else await sources.click();
    await expect(panel).toBeHidden();
    await expect(sources).toBeFocused();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  });
}
