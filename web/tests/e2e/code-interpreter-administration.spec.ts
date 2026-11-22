import { expect, test, type Page } from "@playwright/test";

async function stub(page: Page, configured = true) {
  const state = { configured, enabled: false, revision: 3 };
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
        authorizationVersion: 1,
        uiLanguage: "vi",
        tenant: { displayName: "Công ty Mẫu", role: "MEMBER" },
        capabilities: ["MODELS_MANAGE"],
        scopedCapabilities: [],
      },
    }),
  );
  await page.route("**/api/chat/interpreter", async (route) => {
    if (route.request().method() === "PUT") {
      const body = route.request().postDataJSON() as { enabled: boolean; revision: number };
      state.enabled = body.enabled;
      state.revision = body.revision + 1;
    }
    await route.fulfill({ json: state });
  });
  await page.route("**/api/chat/interpreter/health", (route) =>
    route.fulfill({ json: { connected: true, error: "", version: "0.3.0" } }),
  );
  return state;
}

test("a model manager turns Code Interpreter on and sees the service's health", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const state = await stub(page);
  await page.goto("/admin/code-interpreter");
  await expect(page.getByRole("heading", { name: "Code Interpreter" })).toBeVisible({
    timeout: 60_000,
  });
  await expect(page.getByRole("link", { name: "Code Interpreter", exact: true })).toBeVisible();
  await expect(page.getByText("Dịch vụ hoạt động (phiên bản 0.3.0)")).toBeVisible();
  await page.screenshot({
    path: "test-results/code-interpreter-settings-1440.png",
    fullPage: true,
  });

  await page.getByRole("switch", { name: "Bật Code Interpreter" }).click();
  await expect(page.getByRole("switch", { name: "Bật Code Interpreter" })).toBeChecked();
  expect(state).toMatchObject({ enabled: true, revision: 4 });
});

test("a deployment without the interpreter says so and offers no switch", async ({ page }) => {
  await stub(page, false);
  await page.goto("/admin/code-interpreter");
  await expect(page.getByText("Máy chủ này chưa cấu hình dịch vụ Code Interpreter.")).toBeVisible({
    timeout: 60_000,
  });
  await expect(page.getByRole("switch", { name: "Bật Code Interpreter" })).toHaveCount(0);
});

for (const [label, width, scheme] of [
  ["dark", 1440, "dark"],
  ["390", 390, "light"],
] as const) {
  test(`the Code Interpreter page reads at ${label}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ colorScheme: scheme });
    await stub(page);
    await page.goto("/admin/code-interpreter");
    await expect(page.getByText("Dịch vụ hoạt động (phiên bản 0.3.0)")).toBeVisible({
      timeout: 60_000,
    });
    await page.screenshot({
      path: `test-results/code-interpreter-settings-${label}.png`,
      fullPage: true,
    });
  });
}
