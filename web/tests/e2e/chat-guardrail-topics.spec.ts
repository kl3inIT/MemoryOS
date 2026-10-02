import { expect, test, type Page } from "@playwright/test";

type Topic = {
  id?: string;
  name: string;
  description: string;
  examples: string[];
  message: string;
  enabled: boolean;
};

async function stub(page: Page) {
  const state = {
    revision: 4,
    topics: [
      {
        id: "0f5b6f2a-7c1d-4e8a-9b3c-000000000001",
        name: "Chính trị",
        description:
          "Câu hỏi xin ý kiến, đánh giá hoặc dự đoán về đảng phái, bầu cử, nhà nước và chính sách của nhà nước, tranh cãi chính trị, hoặc tranh chấp lãnh thổ và chủ quyền.",
        examples: ["Đảng nào tốt hơn?"],
        message: "Trợ lý không trả lời câu hỏi về chính trị.",
        enabled: true,
      },
      {
        id: "0f5b6f2a-7c1d-4e8a-9b3c-000000000002",
        name: "Lãnh tụ và lãnh đạo",
        description:
          "Câu hỏi về đời tư, gia đình, tính cách hoặc đánh giá các lãnh tụ, nguyên thủ quốc gia, lãnh đạo nhà nước và nhân vật chính trị trong lịch sử, kể cả khi nhắc đến gián tiếp.",
        examples: ["Vợ bác Hồ là ai?"],
        message: "Trợ lý không trả lời câu hỏi về lãnh tụ và lãnh đạo.",
        enabled: true,
      },
      {
        id: "0f5b6f2a-7c1d-4e8a-9b3c-000000000003",
        name: "Tôn giáo",
        description:
          "Câu hỏi so sánh, phán xét hoặc cổ vũ các tôn giáo, tín ngưỡng hay nghi lễ tôn giáo.",
        examples: [],
        message: "Trợ lý không trả lời câu hỏi về tôn giáo.",
        enabled: false,
      },
    ] as Topic[],
    blockedPhrases: ["Dự án Phoenix"],
    blockedPhraseMessage: "Trợ lý không trả lời câu hỏi này.",
  };
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
        authorizationVersion: 1,
        uiLanguage: "vi",
        tenant: { displayName: "Tasco", role: "MEMBER" },
        capabilities: ["MODELS_MANAGE"],
        scopedCapabilities: [],
      },
    }),
  );
  await page.route("**/api/chat/settings", (route) =>
    route.fulfill({
      json: {
        deepResearchEnabled: true,
        chatHistoryVisibility: "NORMAL",
        groundedAnswers: true,
        groundedAllowWeb: false,
        revision: 15,
      },
    }),
  );
  await page.route("**/api/chat/settings/guardrails", async (route) => {
    if (route.request().method() === "PUT") {
      const body = route.request().postDataJSON() as typeof state & { revision: number };
      state.revision = body.revision + 1;
      state.topics = body.topics.map((topic, index) => ({
        ...topic,
        id: topic.id ?? `11111111-0000-4000-8000-00000000000${index}`,
      }));
      state.blockedPhrases = body.blockedPhrases;
    }
    await route.fulfill({ json: state });
  });
  return state;
}

test("a model manager adds, edits and deletes a sensitive topic", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const state = await stub(page);
  await page.goto("/admin/chat");
  const topics = page.getByRole("region", { name: "Chủ đề nhạy cảm" });
  await expect(topics.getByText("Lãnh tụ và lãnh đạo")).toBeVisible({ timeout: 60_000 });
  await page.screenshot({ path: "test-results/chat-settings-1440.png", fullPage: true });

  await topics.getByRole("button", { name: "Thêm chủ đề" }).click();
  const dialog = page.getByRole("dialog", { name: "Thêm chủ đề" });
  await dialog.getByRole("textbox", { name: "Tên chủ đề" }).fill("Lương thưởng");
  await dialog
    .getByRole("textbox", { name: "Mô tả" })
    .fill("Câu hỏi về lương, thưởng hoặc thu nhập của từng người trong công ty.");
  await dialog
    .getByRole("textbox", { name: "Câu hỏi ví dụ" })
    .fill("Lương giám đốc bao nhiêu?\nThưởng Tết của anh A là bao nhiêu?");
  await page.screenshot({ path: "test-results/chat-settings-topic-dialog.png" });
  await dialog.getByRole("button", { name: "Lưu" }).click();
  await expect(topics.getByRole("switch", { name: "Lương thưởng" })).toBeChecked();
  expect(state.topics.map((topic) => topic.name)).toEqual([
    "Chính trị",
    "Lãnh tụ và lãnh đạo",
    "Tôn giáo",
    "Lương thưởng",
  ]);

  await topics.getByRole("switch", { name: "Tôn giáo" }).click();
  await expect(topics.getByRole("switch", { name: "Tôn giáo" })).toBeChecked();
  expect(state.topics[2]!.enabled).toBe(true);

  await topics.getByRole("button", { name: "Thao tác với Chính trị" }).click();
  await page.getByRole("menuitem", { name: "Xóa chủ đề" }).click();
  await page
    .getByRole("alertdialog", { name: "Xóa chủ đề?" })
    .getByRole("button", { name: "Xóa chủ đề" })
    .click();
  await expect(topics.getByText("Chính trị", { exact: true })).toHaveCount(0);
  expect(state.topics).toHaveLength(3);
});

for (const [label, width, scheme] of [
  ["dark", 1440, "dark"],
  ["390", 390, "light"],
] as const) {
  test(`the Chat settings page reads at ${label}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.emulateMedia({ colorScheme: scheme });
    await stub(page);
    await page.goto("/admin/chat");
    await expect(page.getByText("Lãnh tụ và lãnh đạo")).toBeVisible({ timeout: 60_000 });
    await page.screenshot({ path: `test-results/chat-settings-${label}.png`, fullPage: true });
    await page.getByRole("region", { name: "Cụm từ bị chặn" }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: `test-results/chat-settings-${label}-phrases.png` });
  });
}
