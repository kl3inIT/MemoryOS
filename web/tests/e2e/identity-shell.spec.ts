import { expect, test } from "@playwright/test";

const ACTOR_ID = "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1";

test("offers the backend OAuth2 flow when no session exists", async ({ page }) => {
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({ status: 401 });
  });

  await page.goto("/");

  await expect(page.getByRole("heading", { name: /sign in to memoryos/i })).toBeVisible();
  await expect(page.getByRole("heading", { name: /keep what matters/i })).toHaveCount(0);
  await expect(
    page.getByText(/private workspace|authentication and mfa|authorized members only/i),
  ).toHaveCount(0);
  await expect(page.getByRole("link", { name: /continue with company account/i })).toHaveAttribute(
    "href",
    "/oauth2/authorization/memoryos",
  );
});

test("retains the OAuth session across the local HTTP callback", async ({ page }) => {
  await page.goto("/oauth2/authorization/memoryos");

  await expect(page.locator("body")).toContainText("SESSION=oauth-state");
});

test("renders the authenticated application shell", async ({ page }) => {
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ actorId: ACTOR_ID }),
    });
  });

  await page.goto("/");

  await expect(page.getByRole("navigation", { name: "Primary navigation" })).toBeVisible();
  await expect(page.getByRole("link", { name: "New Session", exact: true })).toHaveAttribute(
    "aria-current",
    "page",
  );
  await expect(page.getByRole("link", { name: "Admin Panel" })).toHaveAttribute("href", "/admin");
  await expect(page.getByRole("heading", { name: "How can I help?" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Ask MemoryOS" })).toBeDisabled();
  await page.reload();
  await expect(page.getByRole("heading", { name: "How can I help?" })).toBeVisible();
});

test("persists the selected dark theme", async ({ page }) => {
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ actorId: ACTOR_ID }),
    });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Workspace owner" }).click();
  await page.getByRole("button", { name: "Use dark theme" }).click();
  await expect(page.locator("html")).toHaveClass(/dark/);

  await page.reload();
  await expect(page.locator("html")).toHaveClass(/dark/);
  await page.getByRole("button", { name: "Workspace owner" }).click();
  await expect(page.getByRole("button", { name: "Use light theme" })).toBeVisible();
});

test("opens the separate administration shell", async ({ page }) => {
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ actorId: ACTOR_ID }),
    });
  });

  await page.goto("/");
  await page.getByRole("link", { name: "Admin Panel" }).click();

  await expect(page).toHaveURL(/\/admin$/);
  await expect(page.getByRole("navigation", { name: "Administration navigation" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Sources", exact: true })).toHaveAttribute(
    "aria-current",
    "page",
  );
  await expect(page.getByRole("heading", { name: "Sources", exact: true })).toBeVisible();
});

test("keeps unprovisioned access separate from signed-out state", async ({ page }) => {
  await page.goto("/access-not-provisioned");

  await expect(
    page.getByRole("heading", { name: /workspace doesn’t know you yet/i }),
  ).toBeVisible();
  await expect(page.getByText(/has not been invited into this memoryos workspace/i)).toBeVisible();
});

test("recovers from an unavailable identity endpoint without treating it as signed out", async ({
  page,
}) => {
  let requestCount = 0;
  await page.route("**/api/identity/me", async (route) => {
    requestCount += 1;
    if (requestCount === 1) {
      await route.fulfill({ status: 503 });
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ actorId: ACTOR_ID }),
    });
  });

  await page.goto("/");

  await expect(page.getByRole("heading", { name: /couldn’t confirm your session/i })).toBeVisible();
  await page.getByRole("button", { name: /try again/i }).click();
  await expect(page.getByRole("heading", { name: "How can I help?" })).toBeVisible();
});
