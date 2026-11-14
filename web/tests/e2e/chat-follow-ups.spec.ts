import { expect, test, type Page } from "@playwright/test";
import type { ChatSettingsResponse } from "../../src/lib/hey-api/types.gen";

const settings: ChatSettingsResponse = {
  deepResearchEnabled: true,
  chatHistoryVisibility: "NORMAL",
  groundedAnswers: false,
  groundedAllowWeb: false,
  followUpSuggestions: true,
  revision: 1,
};
const suggestions = [
  "Nghỉ phép quá số ngày còn lại thì tính thế nào?",
  "Mẫu đơn xin nghỉ phép ở đâu?",
  "Nếu quản lý trực tiếp đang nghỉ và trưởng phòng cũng vắng mặt trong tuần đó thì đơn nghỉ phép của mình sẽ được chuyển cho ai duyệt và mất bao lâu?",
];

/** MEM-266: the API's follow-up questions, after a pause so the placeholders show; returns the asked answers' ids. */
async function followUps(page: Page) {
  const asked: string[] = [];
  await page.route("**/api/chat/settings", (route) => route.fulfill({ json: settings }));
  await page.route("**/api/chat/sessions/*/messages/*/follow-ups", async (route) => {
    asked.push(new URL(route.request().url()).pathname.split("/").at(-2) ?? "");
    await new Promise((resolve) => setTimeout(resolve, 800));
    await route.fulfill({ json: { suggestions } });
  });
  return asked;
}

async function ask(page: Page, mode?: string) {
  const session = await (
    await page.request.post("/api/chat/test-fixture", {
      data: { title: "Nghỉ phép", ...(mode && { mode }) },
    })
  ).json();
  await page.goto(`/chat/${session.id}`);
  await page
    .getByRole("textbox", { name: "Câu hỏi", exact: true })
    .fill("Quy trình xin nghỉ phép của công ty mình thế nào?");
  await page.getByRole("button", { name: "Gửi câu hỏi" }).click();
}

test("the latest answer offers three follow-up questions, and choosing one asks it", async ({
  page,
}) => {
  const asked = await followUps(page);
  await ask(page);

  await expect(page.getByRole("status", { name: "Đang gợi ý câu hỏi tiếp theo" })).toBeVisible();
  const chip = page.getByRole("button", { name: suggestions[1] });
  await expect(chip).toBeVisible();
  await expect(page.getByRole("button", { name: suggestions[2] })).toBeVisible();
  await page.screenshot({ path: "../output/playwright/chat-follow-ups.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "../output/playwright/chat-follow-ups-mobile.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 800 });

  await chip.click();
  // The question is asked at once; the old questions leave with the new run and come back under its answer only.
  await expect(page.getByRole("button", { name: suggestions[1] })).toHaveCount(0);
  await expect(page.getByText(suggestions[1], { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: suggestions[0] })).toHaveCount(1);
  expect(asked).toHaveLength(2);
  expect(asked[0]).not.toBe(asked[1]);

  // Suggestions are not kept: a reload shows none and asks for none.
  await page.reload();
  await expect(page.getByRole("button", { name: "Sao chép câu trả lời" }).last()).toBeVisible();
  await expect(page.getByRole("button", { name: suggestions[0] })).toHaveCount(0);
  expect(asked).toHaveLength(2);
});

test("a failed answer asks for no follow-up questions", async ({ page }) => {
  const asked = await followUps(page);
  await ask(page, "failed");

  await expect(page.getByText("Câu trả lời bị gián đoạn")).toBeVisible();
  await expect(page.getByRole("status", { name: "Đang gợi ý câu hỏi tiếp theo" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: suggestions[0] })).toHaveCount(0);
  expect(asked).toEqual([]);
});
