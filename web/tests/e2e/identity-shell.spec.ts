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

test("renders the stable actor returned by an authenticated session", async ({ page }) => {
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ actorId: ACTOR_ID }),
    });
  });

  await page.goto("/");

  await expect(page.getByText("Private session")).toBeVisible();
  await expect(page.getByLabel(`Actor ID ${ACTOR_ID}`)).toBeVisible();

  await page.reload();
  await expect(page.getByLabel(`Actor ID ${ACTOR_ID}`)).toBeVisible();
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
  await expect(page.getByLabel(`Actor ID ${ACTOR_ID}`)).toBeVisible();
});
