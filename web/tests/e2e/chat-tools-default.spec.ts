import { expect, test, type Page } from "@playwright/test";
import { fixtureModels } from "../fixtures/chat-data";

const drive = "50000000-0000-4000-8000-000000000001";

async function stub(page: Page) {
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        actorId: "e62a621f-41d2-4853-aa76-b600dafd8e34",
        authorizationVersion: 1,
        uiLanguage: "vi",
        tenant: { displayName: "Test tenant", role: "MEMBER" },
        capabilities: ["IMAGE_GENERATE"],
        scopedCapabilities: [],
      },
    }),
  );
  await page.route(/\/api\/chat\/web(?:\?|$)/, (route) =>
    route.fulfill({
      json: {
        searchAvailable: true,
        inheritedModelId: fixtureModels[0]!.id,
        automaticModelIds: fixtureModels.map((model) => model.id),
        nativeModelIds: [],
      },
    }),
  );
  await page.route(/\/api\/chat\/images(?:\?|$)/, (route) =>
    route.fulfill({ json: { available: true, provider: "OPENAI_IMAGE", model: "gpt-image-1" } }),
  );
  await page.route("**/api/chat/preferences", (route) =>
    route.fulfill({
      json: {
        workRole: "",
        personalPreferences: "",
        defaultModelId: null,
        temperatureDefault: null,
        reasoningEffortDefault: "OFF",
        autoScroll: true,
        displayName: null,
        email: null,
      },
    }),
  );
  const server = (id: string, name: string, connectionState: string) => ({
    id,
    slug: name.toLowerCase().replace(/[^a-z]/g, ""),
    name,
    description: null,
    url: `https://mcp.example.com/${id}`,
    authType: "OAUTH",
    authPerformer: "PER_USER",
    status: "CONNECTED",
    connectionState,
    oauthClients: [],
    enabledToolCount: 4,
    credentialUpdatedAt: null,
    revision: 1,
  });
  await page.route("**/api/mcp/connections", (route) =>
    route.fulfill({
      json: [
        server(drive, "Google Drive", "CONNECTED"),
        server("50000000-0000-4000-8000-000000000002", "Jira", "NOT_CONNECTED"),
      ],
    }),
  );
}

async function ask(page: Page, text: string) {
  const sent = page.waitForRequest(
    (request) => request.method() === "POST" && request.url().endsWith("/messages"),
  );
  await page.getByRole("textbox", { name: "Câu hỏi", exact: true }).fill(text);
  await page.getByRole("button", { name: "Gửi câu hỏi" }).click();
  const body = (await sent).postDataJSON() as Record<string, unknown>;
  await expect(page.getByRole("button", { name: "Chỉnh sửa câu hỏi" }).last()).toBeEnabled();
  return body;
}

test("a conversation uses the tools it can run until the person turns one off", async ({
  page,
}) => {
  await stub(page);
  const session = await (
    await page.request.post("/api/chat/test-fixture", { data: { title: "Kế hoạch quý 4" } })
  ).json();
  await page.goto(`/chat/${session.id}`);
  const add = page.getByRole("button", { name: "Thêm vào câu hỏi", exact: true });
  await add.click();
  const web = page.getByRole("button", { name: "Tìm kiếm Web", exact: true });
  await expect(web).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("button", { name: "Tạo ảnh", exact: true })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  // A tool on by default has no chip; the menu shows its state.
  await page.keyboard.press("Escape");
  await expect(page.getByRole("button", { name: "Tắt Web" })).toHaveCount(0);

  expect(await ask(page, "Giá vàng hôm nay?")).toMatchObject({
    webSearch: "auto",
    image: "auto",
    mcpServerIds: [drive],
  });

  await add.click();
  await web.click();
  expect(await ask(page, "Tóm tắt lại giúp tôi")).toMatchObject({ webSearch: "off" });

  // Turning Web off is remembered for this conversation.
  await page.reload();
  await add.click();
  await expect(web).toHaveAttribute("aria-pressed", "false");
});
