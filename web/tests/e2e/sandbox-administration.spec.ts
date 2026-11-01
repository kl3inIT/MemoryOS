import { expect, test, type Page } from "@playwright/test";

async function stub(page: Page, allowedDomains: string[] = ["pypi.org", "files.pythonhosted.org"]) {
  const state = { configured: true, enabled: false, revision: 3, allowedDomains };
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
  await page.route("**/api/chat/sandbox", async (route) => {
    if (route.request().method() === "PUT") {
      const body = route.request().postDataJSON() as { enabled: boolean; revision: number };
      state.enabled = body.enabled;
      state.revision = body.revision + 1;
    }
    await route.fulfill({ json: state });
  });
  await page.route("**/api/chat/sandbox/health", (route) =>
    route.fulfill({ json: { connected: true, error: "", version: "OpenSandbox" } }),
  );
  return state;
}

test("a model manager turns the Sandbox on and sees its health and allowed domains", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const state = await stub(page);
  await page.goto("/admin/sandbox");
  await expect(page.getByRole("heading", { name: "Sandbox" })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole("link", { name: "Sandbox", exact: true })).toBeVisible();
  await expect(page.getByText("Sandbox hoạt động (OpenSandbox)")).toBeVisible();
  const domains = page.getByRole("list", { name: "Tên miền được phép truy cập" });
  await expect(domains.getByRole("listitem")).toHaveText(["pypi.org", "files.pythonhosted.org"]);
  await page.screenshot({ path: "test-results/sandbox-settings-1440.png", fullPage: true });

  await page.getByRole("switch", { name: "Bật Sandbox" }).click();
  await expect(page.getByRole("switch", { name: "Bật Sandbox" })).toBeChecked();
  expect(state).toMatchObject({ enabled: true, revision: 4 });
});

test("a deployment that denies all egress lists no domain", async ({ page }) => {
  await stub(page, []);
  await page.goto("/admin/sandbox");
  await expect(page.getByText("Không có", { exact: true })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole("list", { name: "Tên miền được phép truy cập" })).toHaveCount(0);
});

for (const [label, width, scheme] of [
  ["dark", 1440, "dark"],
  ["390", 390, "light"],
] as const) {
  test(`the Sandbox page reads at ${label}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ colorScheme: scheme });
    await stub(page);
    await page.goto("/admin/sandbox");
    await expect(page.getByText("files.pythonhosted.org")).toBeVisible({ timeout: 60_000 });
    await page.screenshot({ path: `test-results/sandbox-settings-${label}.png`, fullPage: true });
  });
}
