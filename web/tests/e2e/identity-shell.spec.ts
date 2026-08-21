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

test("creates a production invitation from the People administration page", async ({ page }) => {
  const expiresAt = "2026-08-24T10:00:00Z";
  const invitations: Array<Record<string, unknown>> = [];
  let mutationHeader: string | undefined;

  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ actorId: ACTOR_ID }),
    });
  });
  await page.route("**/api/invitations", async (route) => {
    if (route.request().method() === "POST") {
      mutationHeader = route.request().headers()["x-memoryos-csrf"];
      const invitation = {
        id: "75c4e810-e1f2-45cb-9480-8e713a934bca",
        email: "member@example.com",
        status: "PENDING",
        secretVersion: 1,
        createdAt: "2026-08-21T10:00:00Z",
        expiresAt,
        acceptedActorId: null,
        acceptedAt: null,
        revokedAt: null,
      };
      invitations.push(invitation);
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({
          invitation,
          invitationUrl: "/invite/one-time-secret",
        }),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(invitations),
    });
  });
  await page.route("**/api/invitations/**", async (route) => {
    mutationHeader = route.request().headers()["x-memoryos-csrf"];
    const invitation = invitations[0];
    if (!invitation) {
      await route.fulfill({ status: 404 });
      return;
    }
    if (route.request().method() === "POST" && route.request().url().endsWith("/rotate")) {
      invitation.secretVersion = 2;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          invitation,
          invitationUrl: "/invite/rotated-secret",
        }),
      });
      return;
    }
    if (route.request().method() === "DELETE") {
      invitation.status = "REVOKED";
      invitation.revokedAt = "2026-08-21T11:00:00Z";
      await route.fulfill({ status: 204 });
      return;
    }
    await route.fulfill({ status: 405 });
  });

  await page.goto("/admin/people");
  await expect(page.getByRole("link", { name: "People", exact: true })).toHaveAttribute(
    "aria-current",
    "page",
  );
  await expect(page.getByRole("heading", { name: "People", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Invite member" }).click();
  await page.getByRole("textbox", { name: "Email address" }).fill("member@example.com");
  await page.getByRole("button", { name: "Create invitation" }).click();

  await expect(page.getByRole("heading", { name: "Invitation link ready" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Secure invitation link" })).toHaveValue(
    /\/invite\/one-time-secret$/,
  );
  expect(mutationHeader).toBe("1");
  await page.getByRole("button", { name: "Done" }).click();
  await expect(page.getByText("member@example.com")).toBeVisible();
  await expect(page.getByText("Pending", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Rotate link" }).click();
  await expect(page.getByRole("textbox", { name: "Secure invitation link" })).toHaveValue(
    /\/invite\/rotated-secret$/,
  );
  await page.getByRole("button", { name: "Done" }).click();
  await page.getByRole("button", { name: "Revoke" }).click();
  await expect(page.getByText("Revoked", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Rotate link" })).toHaveCount(0);
  expect(mutationHeader).toBe("1");
});

test("shows the recipient invitation landing and recovery states", async ({ page }) => {
  await page.route("**/api/invitations/current", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        organizationDisplayName: "Tasco",
        expiresAt: "2026-08-24T10:00:00Z",
        continueUrl: "/invite/continue?nonce=recipient-state",
      }),
    });
  });

  await page.goto("/invitation");
  await expect(page.getByRole("heading", { name: "Join Tasco" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Continue to sign in" })).toHaveAttribute(
    "href",
    "/invite/continue?nonce=recipient-state",
  );
  await expect(page.getByText(/does not grant admin permissions/i)).toBeVisible();

  await page.goto("/invitation?reason=email-mismatch");
  await expect(page.getByRole("heading", { name: "Use the invited email" })).toBeVisible();
  await expect(page.getByText(/verified email does not match/i)).toBeVisible();
});
