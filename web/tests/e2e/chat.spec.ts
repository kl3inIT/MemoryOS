import { expect, test } from "@playwright/test";

const identity = {
  actorId: "e62a621f-41d2-4853-aa76-b600dafd8e34",
  authorizationVersion: 1,
  tenant: { displayName: "Test tenant", role: "MEMBER" },
  capabilities: [],
  scopedCapabilities: [],
};
test.beforeEach(async ({ page }) => {
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: identity }));
});

test("new chat, native keyboard/IME, server IDs, multiple turns, markdown and reload", async ({
  page,
  context,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "What can I help you with?" })).toBeVisible();
  const input = page.getByRole("textbox", { name: "Message", exact: true });
  await input.fill("First line");
  await input.press("Shift+Enter");
  await input.press("a");
  await expect(input).toHaveValue("First line\na");
  await input.dispatchEvent("compositionstart");
  await input.dispatchEvent("keydown", { key: "Enter", code: "Enter", isComposing: true });
  await expect(page).toHaveURL(/\/$/);
  await input.dispatchEvent("compositionend");
  await input.press("Enter");
  await expect(page).toHaveURL(/\/chat\/[0-9a-f-]+$/);
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Stop reply" })).toHaveCount(0);
  await expect(page.locator("pre")).toContainText('System.out.println("Hello");');
  await page.getByRole("button", { name: "Copy code" }).click();
  await expect
    .poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toContain('System.out.println("Hello");');
  await input.fill("Next question");
  await input.press("Enter");
  await expect(page.getByText("Hello 👋", { exact: true })).toHaveCount(2);
  await expect(page.getByRole("button", { name: "Stop reply" })).toHaveCount(0);
  const sessionId = page.url().split("/").at(-1)!;
  const stats = await page.request.get(`/api/chat/sessions/${sessionId}/stats`);
  expect((await stats.json()).sends).toBe(2);
  await page.reload();
  await expect(page.getByText("Hello 👋", { exact: true })).toHaveCount(2);
  expect(errors).toEqual([]);
});

for (const mode of ["slow", "disconnect", "gap", "failed"]) {
  test(`recovers ${mode} with one inference and server-authoritative status`, async ({ page }) => {
    const response = await page.request.post("/api/chat/test-fixture", {
      data: { title: `${mode} conversation`, mode },
    });
    const session = await response.json();
    await page.goto(`/chat/${session.id}`);
    await page.getByRole("textbox", { name: "Message", exact: true }).fill("Explain streaming");
    await page.getByRole("button", { name: "Send message" }).click();
    if (mode === "slow") {
      await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
      await page.reload();
      await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
      await page.getByRole("button", { name: "Stop reply" }).click();
      await expect(page.getByRole("button", { name: "Requesting stop" })).toBeDisabled();
      await expect(page.getByText("Stopped", { exact: true })).toBeVisible();
      await page.reload();
      await expect(page.getByText("Stopped", { exact: true })).toBeVisible();
    } else {
      await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
      await expect(page.getByRole("button", { name: "Stop reply" })).toHaveCount(0);
      if (mode === "failed")
        await expect(page.getByText("Reply interrupted. Partial answer saved.")).toBeVisible();
    }
    const stats = await page.request.get(`/api/chat/sessions/${session.id}/stats`);
    expect((await stats.json()).sends).toBe(1);
  });
}

test("mobile drawer, Chat/Search mode, and leaving a running chat only closes the reader", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const response = await page.request.post("/api/chat/test-fixture", {
    data: { title: "Mobile running", mode: "slow" },
  });
  const session = await response.json();
  await page.goto(`/chat/${session.id}`);
  await page.getByRole("textbox", { name: "Message", exact: true }).fill("Keep running");
  await page.getByRole("button", { name: "Send message" }).click();
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  const box = await page.getByRole("textbox", { name: "Message", exact: true }).boundingBox();
  expect(box!.y + box!.height).toBeLessThan(844);
  await page.getByRole("button", { name: "Chat, switch mode" }).click();
  await page.getByRole("link", { name: "Search", exact: true }).last().click();
  await expect(page).toHaveURL(/\/search$/);
  await expect
    .poll(
      async () =>
        (await (await page.request.get(`/api/chat/sessions/${session.id}/stats`)).json()).readers,
    )
    .toBe(0);
  const history = await (
    await page.request.get(`/api/chat/sessions/${session.id}/messages`)
  ).json();
  expect(history.at(-1).status).toBe("RUNNING");
  await page.getByRole("button", { name: "Open navigation" }).click();
  await page.getByRole("link", { name: "Mobile running", exact: true }).last().click();
  await expect(page.getByRole("button", { name: "Stop reply" })).toBeVisible();
  await page.getByRole("button", { name: "Stop reply" }).click();
  await expect(page.getByText("Stopped", { exact: true })).toBeVisible();
});

test("a denied stream hides private messages and sends no further model request", async ({
  page,
}) => {
  const response = await page.request.post("/api/chat/test-fixture", {
    data: { title: "Denied stream" },
  });
  const session = await response.json();
  await page.route("**/events", (route) => route.fulfill({ status: 403 }));
  await page.goto(`/chat/${session.id}`);
  await page.getByRole("textbox", { name: "Message", exact: true }).fill("Private question");
  await page.getByRole("button", { name: "Send message" }).click();
  await expect(page.getByText("This conversation is no longer available.")).toBeVisible();
  await expect(page.getByText("Private question", { exact: true })).toHaveCount(0);
  const stats = await page.request.get(`/api/chat/sessions/${session.id}/stats`);
  expect((await stats.json()).sends).toBe(1);
});

test("streaming preserves the reader's scroll position and offers return to the latest message", async ({
  page,
}) => {
  const response = await page.request.post("/api/chat/test-fixture", {
    data: { title: "Reading position", mode: "long" },
  });
  const session = await response.json();
  await page.goto(`/chat/${session.id}`);
  await page.getByRole("textbox", { name: "Message", exact: true }).fill("Long answer");
  await page.getByRole("button", { name: "Send message" }).click();
  await expect(page.getByText(/^Paragraph 50:/)).toBeVisible();
  const viewport = page.getByTestId("chat-viewport");
  await viewport.hover();
  await page.mouse.wheel(0, -10000);
  await expect.poll(() => viewport.evaluate((element) => element.scrollTop)).toBeLessThan(10);
  await expect(page.getByText("The final paragraph arrived.")).toBeAttached();
  await expect.poll(() => viewport.evaluate((element) => element.scrollTop)).toBeLessThan(10);
  await page.getByRole("button", { name: "Scroll to latest message" }).click();
  await expect
    .poll(() =>
      viewport.evaluate(
        (element) => element.scrollHeight - element.clientHeight - element.scrollTop,
      ),
    )
    .toBeLessThan(10);
  await expect(page.getByRole("button", { name: "Scroll to latest message" })).toBeHidden();
});
