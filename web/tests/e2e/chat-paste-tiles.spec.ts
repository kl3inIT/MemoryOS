import { expect, test } from "@playwright/test";

const identity = {
  actorId: "e62a621f-41d2-4853-aa76-b600dafd8e34",
  authorizationVersion: 1,
  uiLanguage: "vi",
  tenant: { displayName: "Test tenant", role: "MEMBER" },
  capabilities: ["SYSTEM_BASIC", "SEARCH_READ", "CHAT_READ", "CHAT_WRITE", "LLM_GATEWAY_USE"],
  scopedCapabilities: [],
};

const log = [
  "10:01 INFO payroll export started",
  "10:02 WARN disk at 91%",
  "10:03 ERROR export timed out",
  "10:04 INFO retry scheduled",
  "10:05 INFO export finished",
];

test("a long paste waits as a tile and its full text is sent with the question (MEM-148)", async ({
  page,
}) => {
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: identity }));
  await page.route("**/api/chat/preferences", (route) =>
    route.fulfill({
      json: {
        workRole: "",
        personalPreferences: "",
        defaultModelId: null,
        temperatureDefault: null,
        reasoningEffortDefault: "OFF",
        autoScroll: true,
        pasteAsTile: true,
        displayName: null,
        email: null,
      },
    }),
  );
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Nhật ký xuất lương" } })
  ).json();
  await page.goto(`/chat/${session.id}`);
  const field = page.getByRole("textbox", { name: "Câu hỏi", exact: true });
  await field.fill("Lỗi nằm ở đâu?");
  await field.evaluate((element, text) => {
    const data = new DataTransfer();
    data.setData("text/plain", text);
    element.dispatchEvent(
      new ClipboardEvent("paste", { clipboardData: data, bubbles: true, cancelable: true }),
    );
  }, log.join("\n"));

  // The field keeps only the question; the paste waits beside it as a tile.
  await expect(field).toHaveValue("Lỗi nằm ở đâu?");
  const tile = page.getByRole("button", { name: "Xem đoạn dán, 5 dòng" });
  await expect(tile).toBeVisible();
  await tile.click();
  await expect(page.getByRole("dialog").getByRole("textbox")).toHaveValue(log.join("\n"));
  await page.getByRole("dialog").getByRole("button", { name: "Đóng" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);

  await page.getByRole("button", { name: "Gửi câu hỏi" }).click();
  await expect(tile).toHaveCount(0);
  await expect(field).toHaveValue("");
  for (const line of ["Lỗi nằm ở đâu?", ...log])
    await expect(page.getByText(line, { exact: false }).first()).toBeVisible();
});
