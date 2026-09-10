import { expect, test } from "@playwright/test";
import { fixtureModels, fixtureSource } from "../fixtures/chat-data";

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

test("shows effective search queries, open time bounds and selected documents before citations", async ({
  page,
}) => {
  const session = await (
    await page.request.post("/api/chat/test-fixture", {
      data: { mode: "grounded-progress", title: "Search progress" },
    })
  ).json();
  await page.goto(`/chat/${session.id}`);
  await page
    .getByRole("textbox", { name: "Message", exact: true })
    .fill("Find the latest annual leave policy");
  await page.getByRole("button", { name: "Send message" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Reading document context…" }),
  ).toBeVisible();
  await page.getByText("Search details", { exact: true }).click();
  await expect(page.getByText("annual leave policy", { exact: true })).toBeVisible();
  await expect(page.getByText("HR-2026", { exact: true })).toBeVisible();
  await expect(page.getByText("Sources: Uploaded files", { exact: true })).toBeVisible();
  await expect(page.getByText(/Updated:.*UTC.*Unbounded/)).toBeVisible();
  await expect(page.getByText("Reading documents", { exact: true })).toBeVisible();
  await expect(page.getByText(fixtureSource.title, { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: /1 source/i })).toHaveCount(0);
  await page.screenshot({ path: "../.tmp/onyx-parity-search-progress.png", fullPage: true });
  await page.getByRole("button", { name: "Stop reply" }).click();
  await expect(page.getByText("Reading documents", { exact: true })).toHaveCount(0);
});

test("keeps the new conversation mounted through server ID promotion and resets only when switching", async ({
  page,
}) => {
  const historyReads: string[] = [];
  const streams: string[] = [];
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (request.method() !== "GET") return;
    if (/\/api\/chat\/sessions\/[0-9a-f-]+(?:\/messages)?$/.test(path)) historyReads.push(path);
    if (path.endsWith("/events")) streams.push(path);
  });
  await page.goto("/");
  const input = page.getByRole("textbox", { name: "Message", exact: true });
  await input.fill("Keep this conversation visible");
  const composer = await input.elementHandle();
  await input.press("Enter");
  await expect(page).toHaveURL(/\/chat\/[0-9a-f-]+$/);
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Stop reply" })).toHaveCount(0);
  expect(await composer!.evaluate((element) => element.isConnected)).toBe(true);
  expect(historyReads).toEqual([]);
  expect(streams).toHaveLength(1);
  await expect(page.getByRole("button", { name: "Copy answer", exact: true })).toBeVisible();
  const sessionUrl = page.url();

  await page.getByRole("link", { name: "New chat", exact: true }).click();
  await expect(page.getByRole("heading", { name: "How can I help you today?" })).toBeVisible();
  await expect(
    page.getByRole("main").getByText("Keep this conversation visible", { exact: true }),
  ).toHaveCount(0);
  expect(await composer!.evaluate((element) => element.isConnected)).toBe(false);
  await page.goBack();
  await expect(page).toHaveURL(sessionUrl);
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  expect(historyReads.length).toBeGreaterThan(0);
  await input.fill("A follow-up after switching back");
  await input.press("Enter");
  await expect(page.getByText("Hello 👋", { exact: true })).toHaveCount(2);
});

for (const mode of ["waiting", "grounded-waiting"]) {
  test(`shows one waiting indicator and no empty copy action for ${mode}`, async ({ page }) => {
    const session = await (
      await page.request.post("/api/chat/test-fixture", { data: { mode, title: mode } })
    ).json();
    await page.goto(`/chat/${session.id}`);
    await page.getByRole("textbox", { name: "Message", exact: true }).fill("Wait for evidence");
    await page.getByRole("button", { name: "Send message" }).click();
    const label = mode === "waiting" ? "Thinking…" : "Searching your documents…";
    await expect(page.getByRole("status").filter({ hasText: label })).toHaveCount(1);
    await expect(page.locator(".aui-md")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Copy answer", exact: true })).toHaveCount(0);
    await expect(page.getByText("●", { exact: true })).toHaveCount(0);
    await page.getByRole("button", { name: "Stop reply" }).click();
    await expect(page.getByRole("button", { name: "Requesting stop" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Stop reply" })).toHaveCount(0);
    await expect(page.getByRole("status").filter({ hasText: label })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Copy answer", exact: true })).toHaveCount(0);
  });
}

test("centers the empty composer and selects a catalog model with the keyboard for each turn", async ({
  page,
}) => {
  await page.goto("/");
  const input = page.getByRole("textbox", { name: "Message", exact: true });
  const heading = await page
    .getByRole("heading", { name: "How can I help you today?" })
    .boundingBox();
  const composer = await input.boundingBox();
  expect(composer!.y - heading!.y - heading!.height).toBeLessThan(90);
  const picker = page.getByRole("combobox", { name: "Choose model" });
  await picker.focus();
  await picker.press("ArrowDown");
  const search = page.getByRole("combobox", { name: "Search models" });
  await search.fill("Qwen");
  await search.press("ArrowDown");
  await search.press("Enter");
  await expect(picker).toContainText("Qwen3.5 9B");
  await input.fill("Explain this model");
  await input.press("Enter");
  await expect(page.getByText("Hello 👋", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Stop reply" })).toHaveCount(0);
  await page.reload();
  await expect(picker).toContainText("Qwen3.5 9B");
  await input.fill("And a follow-up");
  await input.press("Enter");
  await expect(page.getByText("Hello 👋", { exact: true })).toHaveCount(2);
  const sessionId = page.url().split("/").at(-1);
  const stats = await (await page.request.get(`/api/chat/sessions/${sessionId}/stats`)).json();
  const qwen = fixtureModels.find((model) => model.displayName === "Qwen3.5 9B")!;
  expect(stats.selectedModels).toEqual([qwen.id, qwen.id]);
});

test("grounds prose citations in message sources, opens the cited range, and preserves history after source denial", async ({
  page,
}) => {
  const session = await (
    await page.request.post("/api/chat/test-fixture", {
      data: { mode: "grounded", title: "Annual leave" },
    })
  ).json();
  const documentReads: URL[] = [];
  await page.route(`**/api/search/documents/${fixtureSource.documentId}?*`, (route) => {
    const url = new URL(route.request().url());
    documentReads.push(url);
    const from = Number(url.searchParams.get("from"));
    return route.fulfill({
      json: {
        ...fixtureSource,
        firstOrdinal: from,
        totalChunks: 5,
        hasMore: false,
        passages: Array.from({ length: 5 - from }, (_, index) => from + index).map((ordinal) => ({
          ordinal,
          content:
            ordinal === 3
              ? "Annual leave is 17 days."
              : ordinal === 4
                ? "Applies to full-time employees."
                : `Handbook context ${ordinal}. `.repeat(120),
          provenanceJson: "{}",
        })),
      },
    });
  });
  await page.goto(`/chat/${session.id}`);
  await page.getByRole("textbox", { name: "Message", exact: true }).fill("How much annual leave?");
  await page.getByRole("button", { name: "Send message" }).click();
  await expect(page.getByText("Searching your documents…")).toBeVisible();
  const citation = page.getByRole("button", { name: "Open source 1: Employee handbook" });
  await expect(citation).toHaveCount(1);
  await expect(page.locator("code").filter({ hasText: "[1]" })).toHaveCount(2);
  await citation.focus();
  await expect(
    page.getByText("Annual leave is 17 days. Applies to full-time employees.", { exact: true }),
  ).toBeVisible();
  await citation.press("Escape");
  await expect(
    page.getByText("Annual leave is 17 days. Applies to full-time employees.", { exact: true }),
  ).toBeHidden();
  await page.getByRole("textbox", { name: "Message", exact: true }).focus();
  await citation.focus();
  await expect(
    page.getByText("Annual leave is 17 days. Applies to full-time employees.", { exact: true }),
  ).toBeVisible();
  expect(documentReads).toHaveLength(1);
  await citation.press("Enter");
  const panel = page.getByRole("complementary", { name: "Sources" });
  await expect(panel).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("textbox", { name: "Message", exact: true })).toBeEnabled();
  await expect(page.getByRole("article", { name: "Selected match" })).toHaveCount(2);
  await panel.getByRole("button", { name: "Earlier context" }).click();
  await expect(panel.getByRole("article").first()).toBeInViewport();
  await expect
    .poll(() => panel.locator("[aria-busy]").evaluate((element) => element.scrollTop))
    .toBe(0);
  await panel.getByRole("button", { name: "Back to cited passage" }).click();
  await expect(panel.getByRole("button", { name: "Back to cited passage" })).toBeHidden();
  await expect(page.getByRole("article", { name: "Selected match" })).toHaveCount(2);
  await page.getByRole("textbox", { name: "Message", exact: true }).focus();
  await page.keyboard.press("Escape");
  await expect(panel).toBeHidden();
  await expect(citation).toBeFocused();
  await expect(page.getByRole("button", { name: "Stop reply" })).toHaveCount(0);
  await page.reload();
  await expect(citation).toHaveCount(1);
  await page.getByRole("button", { name: "Sources 1" }).click();
  await page.getByRole("button", { name: "Read source 1: Employee handbook" }).click();
  await expect(panel).toContainText("Annual leave is 17 days.");
  await page.getByRole("button", { name: "Back to sources" }).click();
  await expect(
    page.getByRole("button", { name: "Read source 1: Employee handbook" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Sources 1" }).click();
  await expect(panel).toBeHidden();
  await page.route(`**/api/search/documents/${fixtureSource.documentId}?*`, (route) =>
    route.fulfill({ status: 404, json: {} }),
  );
  await citation.click();
  await expect(page.getByRole("alert")).toContainText("unavailable or has changed");
  await page.getByRole("button", { name: "Close sources" }).click();
  await expect(page.getByText("17 days", { exact: true })).toBeVisible();
  for (const url of documentReads) {
    expect(url.searchParams.get("generation")).toBe(fixtureSource.generation);
    expect(["0", "1"]).toContain(url.searchParams.get("from"));
  }
});

for (const mode of [
  "grounded-gap",
  "grounded-disconnect",
  "grounded-slow",
  "grounded-failed",
  "grounded-split",
]) {
  test(`keeps sources through ${mode} and reload`, async ({ page }) => {
    const session = await (
      await page.request.post("/api/chat/test-fixture", { data: { mode } })
    ).json();
    await page.goto(`/chat/${session.id}`);
    await page.getByRole("textbox", { name: "Message", exact: true }).fill("Annual leave?");
    await page.getByRole("button", { name: "Send message" }).click();
    const citation = page.getByRole("button", { name: "Open source 1: Employee handbook" });
    await expect(citation).toBeVisible();
    if (mode === "grounded-slow") {
      await expect(page.getByRole("combobox", { name: "Choose model" })).toBeDisabled();
      await page.getByRole("button", { name: "Stop reply" }).click();
      await expect(page.getByText("Stopped", { exact: true })).toBeVisible();
    }
    await expect(page.getByRole("button", { name: "Stop reply" })).toHaveCount(0);
    await page.reload();
    await expect(citation).toBeVisible();
    expect(
      (await (await page.request.get(`/api/chat/sessions/${session.id}/stats`)).json()).sends,
    ).toBe(1);
  });
}

test("shows catalog errors, emptiness and the actual authorized fallback selection", async ({
  page,
}) => {
  await page.route("**/api/chat/models*", (route) => route.fulfill({ status: 503, json: {} }));
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Reload models" })).toBeVisible();
  await page.route("**/api/chat/models*", (route) => route.fulfill({ json: [] }));
  await page.getByRole("button", { name: "Reload models" }).click();
  await expect(page.getByRole("combobox", { name: "Choose model" })).toBeDisabled();
  await expect(page.getByText("No models available")).toBeVisible();
  await page.unroute("**/api/chat/models*");
  await page.evaluate(
    (actor) =>
      sessionStorage.setItem(
        `memoryos.chat.model:${actor}`,
        JSON.stringify({ id: "90000000-0000-4000-8000-000000000009" }),
      ),
    identity.actorId,
  );
  await page.reload();
  await expect(page.getByText("Selected model unavailable")).toBeVisible();
  await page
    .getByRole("textbox", { name: "Message", exact: true })
    .fill("Use the available default");
  await page.getByRole("button", { name: "Send message" }).click();
  await expect(page.getByText(/The selected model is unavailable/)).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Choose model" })).toContainText("GPT-5 mini");
  await expect(page.getByRole("button", { name: "Stop reply" })).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole("combobox", { name: "Choose model" })).toContainText("GPT-5 mini");
  await expect(page.getByText(/The selected model is unavailable/)).toHaveCount(0);
});

test("new chat, native keyboard/IME, server IDs, multiple turns, markdown and reload", async ({
  page,
  context,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "How can I help you today?" })).toBeVisible();
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
  await page.evaluate(() => navigator.clipboard.writeText("Replaced clipboard"));
  await expect(page.getByRole("button", { name: "Copy code" })).toHaveAttribute(
    "title",
    "Copy code",
  );
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

test("mobile model picker stays within the screen and restores focus", async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  const picker = page.getByRole("combobox", { name: "Choose model" });
  await expect(picker).toBeEnabled();
  await picker.click();
  await expect(page.getByRole("combobox", { name: "Search models" })).toBeFocused();
  const bounds = await page.locator('[data-slot="model-selector-content"]').boundingBox();
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390);
  expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(844);
  await page.screenshot({ path: testInfo.outputPath("mobile-model-picker.png") });
  await page.getByRole("option", { name: /Qwen3.5 9B/ }).click();
  await expect(picker).toBeFocused();
  await expect(picker).toContainText("Qwen3.5 9B");
  await page.evaluate(() => document.documentElement.classList.add("dark"));
  await picker.click();
  await page.screenshot({ path: testInfo.outputPath("mobile-model-picker-dark.png") });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

for (const mobile of [false, true]) {
  test(`source panel switches documents and restores focus on ${mobile ? "mobile" : "desktop"}`, async ({
    page,
  }) => {
    await page.setViewportSize(mobile ? { width: 390, height: 844 } : { width: 1440, height: 900 });
    const session = await (
      await page.request.post("/api/chat/test-fixture", { data: { mode: "grounded" } })
    ).json();
    await page.goto(`/chat/${session.id}`);
    await page
      .getByRole("textbox", { name: "Message", exact: true })
      .fill("Compare these policies");
    await page.getByRole("button", { name: "Send message" }).click();
    await expect(
      page.getByRole("button", { name: "Open source 1: Employee handbook" }),
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Stop reply" })).toHaveCount(0);
    const second = {
      ...fixtureSource,
      citationId: 2,
      documentId: "30000000-0000-4000-8000-000000000002",
      title: "Employee handbook",
    };
    await page.route(`**/api/chat/sessions/${session.id}/messages?*`, async (route) => {
      const response = await route.fetch();
      const messages = (await response.json()) as Array<{ role: string }>;
      await route.fulfill({
        json: messages.map((message) =>
          message.role === "ASSISTANT"
            ? {
                ...message,
                content:
                  "Leave is covered by the handbook [1]. Requests have their own procedure [2].",
                sources: [fixtureSource, second],
              }
            : message,
        ),
      });
    });
    await page.route("**/api/search/documents/*?*", (route) => {
      const source = route.request().url().includes(second.documentId!) ? second : fixtureSource;
      return route.fulfill({
        json: {
          ...source,
          firstOrdinal: 1,
          totalChunks: 5,
          hasMore: false,
          passages: [
            {
              ordinal: 3,
              content:
                source.citationId === 1
                  ? "Employees receive 17 days."
                  : "Submit requests to your manager.",
            },
          ],
        },
      });
    });
    await page.reload();
    await expect(page.getByRole("button", { name: "Open source 1: Employee handbook" })).toHaveText(
      "1. Employee handbook",
    );
    await expect(page.getByRole("button", { name: "Open source 2: Employee handbook" })).toHaveText(
      "2. Employee handbook",
    );
    const trigger = page.getByRole("button", { name: "Sources 2" });
    await trigger.click();
    const panel = mobile
      ? page.getByRole("dialog")
      : page.getByRole("complementary", { name: "Sources" });
    await expect(panel).toBeVisible();
    await expect(panel.getByRole("button", { name: /^Read source/ })).toHaveCount(2);
    await expect(panel).toContainText("Submit requests to your manager.");
    await panel.getByRole("button", { name: "Read source 2: Employee handbook" }).click();
    await expect(
      panel.getByRole("heading", { name: "Employee handbook", exact: true }),
    ).toBeVisible();
    await expect(panel.getByRole("article", { name: "Selected match" })).toContainText(
      "Submit requests to your manager.",
    );
    await panel.getByRole("button", { name: "Back to sources" }).click();
    await panel.getByRole("button", { name: "Read source 1: Employee handbook" }).click();
    await expect(panel.getByRole("article", { name: "Selected match" })).toContainText(
      "Employees receive 17 days.",
    );
    const bounds = await panel.boundingBox();
    expect(bounds!.x).toBeGreaterThanOrEqual(0);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(mobile ? 390 : 1440);
    if (mobile) {
      await page.keyboard.press("Tab");
      expect(await panel.evaluate((element) => element.contains(document.activeElement))).toBe(
        true,
      );
    }
    if (!mobile) {
      await page.getByRole("combobox", { name: "Choose model" }).click();
      await page.keyboard.press("Escape");
      await expect(page.getByRole("combobox", { name: "Search models" })).toBeHidden();
      await expect(panel).toBeVisible();
      await page.getByRole("textbox", { name: "Message", exact: true }).focus();
      await expect(page.getByRole("textbox", { name: "Message", exact: true })).toBeFocused();
    }
    await page.keyboard.press("Escape");
    await expect(panel).toBeHidden();
    await expect(trigger).toBeFocused();
    await expect(page.getByRole("textbox", { name: "Message", exact: true })).toBeVisible();
  });
}
