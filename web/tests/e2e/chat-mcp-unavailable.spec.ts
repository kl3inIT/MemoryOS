import { expect, test, type Page } from "@playwright/test";
import type { McpConnection } from "../../src/lib/hey-api/types.gen";
import { LINEAR_SERVER } from "../fixtures/chat-server";

const linear = (connectionState: McpConnection["connectionState"]): McpConnection => ({
  id: LINEAR_SERVER,
  slug: "linear",
  name: "Linear",
  description: null,
  url: "https://mcp.linear.app/mcp",
  authType: "OAUTH",
  authPerformer: "PER_USER",
  status: "CONNECTED",
  connectionState,
  oauthClients: [{ id: "50000000-0000-4000-8000-0000000000b1", label: "Linear" }],
  enabledToolCount: 12,
  credentialUpdatedAt: null,
  revision: 1,
});

async function connections(page: Page, state: McpConnection["connectionState"]) {
  await page.unroute("**/api/mcp/connections");
  await page.route("**/api/mcp/connections", (route) => route.fulfill({ json: [linear(state)] }));
}

test("a reply whose app is not connected offers Connect under the answer, also after a reload", async ({
  page,
}) => {
  await connections(page, "NOT_CONNECTED");
  const authorization = page.waitForRequest(
    (request) =>
      request.method() === "POST" &&
      request.url().endsWith(`/api/mcp/connections/${LINEAR_SERVER}/authorization`),
  );
  await page.route(`**/api/mcp/connections/${LINEAR_SERVER}/authorization`, (route) =>
    route.fulfill({ json: { authorizationUrl: null } }),
  );
  const session = await (
    await page.request.post("/api/chat/test-fixture", {
      data: { title: "Việc trên Linear", mode: "mcp-unavailable" },
    })
  ).json();
  await page.goto(`/chat/${session.id}`);
  await page
    .getByRole("textbox", { name: "Câu hỏi", exact: true })
    .fill("Các issue đang giao cho tôi?");
  await page.getByRole("button", { name: "Gửi câu hỏi" }).click();

  const card = page.getByText("Linear chưa kết nối", { exact: true });
  await expect(card).toBeVisible();
  await expect(page.getByText(/bạn chưa kết nối Linear/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Sao chép câu trả lời" })).toBeVisible();
  await page.screenshot({ path: "../output/playwright/chat-mcp-unavailable.png", fullPage: true });

  // The saved reply keeps the card; Connect starts the app's sign-in and returns to this conversation.
  await page.reload();
  await expect(card).toBeVisible();
  await page.getByRole("button", { name: "Kết nối", exact: true }).click();
  expect((await authorization).postDataJSON()).toEqual({
    oauthClientId: "50000000-0000-4000-8000-0000000000b1",
    returnPath: `/chat/${session.id}`,
  });

  // An expired sign-in reads as such; once connected, the card is gone.
  await connections(page, "REAUTH_REQUIRED");
  await page.reload();
  await expect(page.getByText("Linear đã hết hạn đăng nhập", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Kết nối lại", exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "../output/playwright/chat-mcp-unavailable-mobile.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 800 });
  await connections(page, "CONNECTED");
  await page.reload();
  await expect(page.getByText(/bạn chưa kết nối Linear/)).toBeVisible();
  await expect(page.getByText(/Linear (chưa kết nối|đã hết hạn đăng nhập)/)).toHaveCount(0);
});
