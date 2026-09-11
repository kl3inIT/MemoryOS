import { expect, test } from "@playwright/test";
import type {
  CurrentIdentity,
  ListChatProviderAdaptersResponse,
} from "../../src/lib/hey-api/types.gen";

const manager: CurrentIdentity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  tenant: { displayName: "Model manager", role: "MEMBER" },
  capabilities: ["MODELS_MANAGE"],
  scopedCapabilities: [],
};
const adapters: ListChatProviderAdaptersResponse = [
  {
    type: "openai",
    credentialRequirement: "REQUIRED",
    tokenizerProfiles: [
      { id: "openai-o200k-v1", displayName: "OpenAI O200K" },
      { id: "smollm2-135m-12fd25f-v1", displayName: "SmolLM2 135M" },
    ],
  },
];

for (const width of [1280, 390]) {
  test(`Models-only manager reaches both admin entries and the credential editor at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.route("**/api/identity/me", (route) => route.fulfill({ json: manager }));
    await page.route("**/api/chat/providers", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/chat/provider-adapters", (route) => route.fulfill({ json: adapters }));
    await page.route("**/api/chat/model-default", (route) =>
      route.fulfill({
        json: { modelConfigurationId: "00000000-0000-0000-0000-000000000001", revision: 1 },
      }),
    );
    await page.route("**/api/chat/model-personas?*", (route) =>
      route.fulfill({ json: { items: [], nextCursor: null } }),
    );
    await page.route("**/api/chat/sessions?*", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/chat/projects?*", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/chat/personas?*", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/chat/models", (route) => route.fulfill({ json: [] }));
    await page.goto("/");
    if (width < 768) await page.getByRole("button", { name: "Mở điều hướng" }).click();
    await expect(page.getByRole("link", { name: "Admin Panel", exact: true })).toHaveAttribute(
      "href",
      "/admin/models",
    );
    await page.getByRole("link", { name: "Admin Panel", exact: true }).click();
    await expect(page).toHaveURL(/\/admin\/models$/);
    await expect(page.getByRole("heading", { name: "Models", exact: true })).toBeVisible();
    if (width < 768) await page.getByRole("button", { name: "Mở điều hướng" }).click();
    await expect(page.getByRole("link", { name: "Models", exact: true })).toHaveAttribute(
      "aria-current",
      "page",
    );
    await expect(page.getByRole("link", { name: "Sources", exact: true })).toHaveCount(0);
    await page.getByRole("link", { name: "Back to MemoryOS", exact: true }).click();
    if (width < 768) await page.getByRole("button", { name: "Mở điều hướng" }).click();
    await page.getByRole("button", { name: "Tenant member", exact: true }).click();
    // The account menu and sidebar are distinct real entry points.
    const accountEntry = page.getByRole("link", { name: "Admin Panel", exact: true }).last();
    await expect(accountEntry).toHaveAttribute("href", "/admin/models");
    await accountEntry.click();
    await expect(page).toHaveURL(/\/admin\/models$/);
    await page.getByRole("button", { name: "Add provider", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Add provider", exact: true });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel("Provider name", { exact: true })).toBeFocused();
    await dialog.getByLabel("Provider name", { exact: true }).fill("Private CPU service");
    await dialog
      .getByLabel("Endpoint URL", { exact: true })
      .fill("http://inference-gateway:8080/v1");
    await dialog.getByLabel("API key", { exact: true }).fill("synthetic-transient-browser-secret");
    await expect(dialog.getByRole("button", { name: "Save provider", exact: true })).toBeEnabled();
    await page.keyboard.press("Escape");
    await expect(dialog).toHaveCount(0);
    await page.getByRole("button", { name: "Add provider", exact: true }).click();
    await expect(page.getByRole("dialog").getByLabel("API key", { exact: true })).toHaveValue("");
    await expect(
      page.getByRole("dialog").getByLabel("Provider name", { exact: true }),
    ).toBeFocused();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
  });
}

test("denied Models deep link never requests the protected catalog", async ({ page }) => {
  let protectedRequests = 0;
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({ json: { ...manager, capabilities: [] } }),
  );
  await page.route("**/api/chat/**", async (route) => {
    protectedRequests += 1;
    await route.fulfill({ status: 403 });
  });
  await page.goto("/admin/models");
  await expect(
    page.getByRole("heading", { name: "You don’t have access to this area." }),
  ).toBeVisible();
  expect(protectedRequests).toBe(0);
});
