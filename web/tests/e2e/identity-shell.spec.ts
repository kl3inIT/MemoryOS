import { expect, test } from "@playwright/test";

const ACTOR_ID = "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1";
const OWNER_SESSION = {
  actorId: ACTOR_ID,
  organization: {
    displayName: "Tasco",
    role: "OWNER",
  },
  capabilities: ["INVITATIONS_MANAGE"],
};
const MEMBER_SESSION = {
  ...OWNER_SESSION,
  actorId: "97c41cb9-55ae-4a52-94ab-7aad59be91e5",
  organization: { ...OWNER_SESSION.organization, role: "MEMBER" },
  capabilities: [],
};

test("offers the backend OAuth2 flow when no session exists", async ({ page }) => {
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({ status: 401 });
  });

  await page.goto("/");

  await expect(page.getByRole("heading", { name: /sign in to memoryos/i })).toBeVisible();
  await expect(page.getByRole("heading", { name: /keep what matters/i })).toHaveCount(0);
  await expect(page.getByText(/authentication and mfa|authorized members only/i)).toHaveCount(0);
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
      body: JSON.stringify(OWNER_SESSION),
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

test("hides owner UI and blocks member administration deep links without requests", async ({
  page,
}) => {
  let invitationRequests = 0;
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(MEMBER_SESSION),
    });
  });
  await page.route("**/api/invitations*", async (route) => {
    invitationRequests += 1;
    await route.fulfill({ status: 403 });
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "Open navigation" }).click();
  await expect(page.getByRole("button", { name: "Organization member" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Admin Panel" })).toHaveCount(0);

  await page.goto("/admin/invitations?status=PENDING");
  await expect(page).toHaveURL(/\/admin\/invitations\?status=PENDING/);
  await expect(
    page.getByRole("heading", { name: "You don’t have access to this area." }),
  ).toBeVisible();
  expect(invitationRequests).toBe(0);
});

test("signs out from the account menu with the same-origin guard", async ({ page }) => {
  let logoutMethod: string | undefined;
  let logoutGuard: string | undefined;
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(OWNER_SESSION),
    });
  });
  await page.route("**/logout", async (route) => {
    logoutMethod = route.request().method();
    logoutGuard = route.request().headers()["x-memoryos-csrf"];
    await route.fulfill({
      status: 204,
      headers: { "X-MemoryOS-Logout-Location": "/signed-out-test" },
    });
  });
  await page.route("**/signed-out-test", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "text/html",
      body: "<title>Signed out</title>",
    });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Organization owner" }).click();
  await page.getByRole("button", { name: "Sign out" }).click();

  await expect(page).toHaveURL(/\/signed-out-test$/);
  expect(logoutMethod).toBe("POST");
  expect(logoutGuard).toBe("1");
});

test("persists the selected dark theme", async ({ page }) => {
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(OWNER_SESSION),
    });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Organization owner" }).click();
  await page.getByRole("button", { name: "Use dark theme" }).click();
  await expect(page.locator("html")).toHaveClass(/dark/);

  await page.reload();
  await expect(page.locator("html")).toHaveClass(/dark/);
  await page.getByRole("button", { name: "Organization owner" }).click();
  await expect(page.getByRole("button", { name: "Use light theme" })).toBeVisible();
});

test("opens the separate administration shell", async ({ page }) => {
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(OWNER_SESSION),
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

test("keeps one document, identity session, and admin shell across internal routes", async ({
  page,
}) => {
  let identityRequests = 0;
  await page.route("**/api/identity/me", async (route) => {
    identityRequests += 1;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(OWNER_SESSION),
    });
  });
  await page.route("**/api/invitations?*", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: [],
        page: 0,
        size: 20,
        totalItems: 0,
        totalPages: 0,
      }),
    });
  });

  await page.goto("/");
  await page.evaluate(() => {
    (window as typeof window & { memoryOsDocumentSentinel?: string }).memoryOsDocumentSentinel =
      "same-document";
  });
  await page.getByRole("link", { name: "Admin Panel" }).click();

  await expect(page).toHaveURL(/\/admin$/);
  expect(
    await page.evaluate(
      () =>
        (window as typeof window & { memoryOsDocumentSentinel?: string }).memoryOsDocumentSentinel,
    ),
  ).toBe("same-document");
  expect(identityRequests).toBe(1);

  await page.getByRole("button", { name: "Collapse sidebar" }).click();
  await expect(page.getByRole("button", { name: "Expand sidebar" })).toBeVisible();
  await page.getByRole("link", { name: "Invitations", exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/invitations(?:\?|$)/);
  await expect(page.getByRole("button", { name: "Expand sidebar" })).toBeVisible();
  expect(identityRequests).toBe(1);
});

test("closes mobile administration navigation after a client route change", async ({ page }) => {
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(OWNER_SESSION),
    });
  });
  await page.route("**/api/invitations?*", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: [],
        page: 0,
        size: 20,
        totalItems: 0,
        totalPages: 0,
      }),
    });
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/admin");
  await page.getByRole("button", { name: "Open navigation" }).click();
  await expect(page.getByRole("dialog", { name: "MemoryOS navigation" })).toBeVisible();
  await page.getByRole("link", { name: "Invitations", exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/invitations(?:\?|$)/);
  await expect(page.getByRole("dialog", { name: "MemoryOS navigation" })).toHaveCount(0);

  await page.getByRole("button", { name: "Open navigation" }).click();
  await page.getByRole("button", { name: "Organization owner" }).click();
  await page.getByRole("link", { name: "Admin Panel" }).click();
  await expect(page).toHaveURL(/\/admin$/);
  await expect(page.getByRole("dialog", { name: "MemoryOS navigation" })).toHaveCount(0);
});

test("keeps unprovisioned access separate from signed-out state", async ({ page }) => {
  await page.goto("/access-not-provisioned");

  await expect(page.getByRole("heading", { name: /don’t have access yet/i })).toBeVisible();
  await expect(page.getByText(/has not been added to this memoryos organization/i)).toBeVisible();
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
      body: JSON.stringify(OWNER_SESSION),
    });
  });

  await page.goto("/");

  await expect(page.getByRole("heading", { name: /couldn’t confirm your session/i })).toBeVisible();
  await page.getByRole("button", { name: /try again/i }).click();
  await expect(page.getByRole("heading", { name: "How can I help?" })).toBeVisible();
});

test("creates a production invitation from the Invitations administration page", async ({
  page,
}) => {
  const expiresAt = "2026-08-24T10:00:00Z";
  const invitations: Array<Record<string, unknown>> = [];
  let createMutationHeader: string | undefined;
  let createRequests = 0;
  let deleteMutationHeader: string | undefined;

  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(OWNER_SESSION),
    });
  });
  await page.route("**/api/invitations*", async (route) => {
    if (new URL(route.request().url()).pathname !== "/api/invitations") {
      await route.fallback();
      return;
    }
    if (route.request().method() === "POST") {
      createRequests += 1;
      createMutationHeader = route.request().headers()["x-memoryos-csrf"];
      const invitation = {
        id: "75c4e810-e1f2-45cb-9480-8e713a934bca",
        email: "member@example.com",
        status: "PENDING",
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
      body: JSON.stringify({
        items: invitations,
        page: 0,
        size: 20,
        totalItems: invitations.length,
        totalPages: invitations.length === 0 ? 0 : 1,
      }),
    });
  });
  await page.route("**/api/invitations/**", async (route) => {
    const invitation = invitations[0];
    if (!invitation) {
      await route.fulfill({ status: 404 });
      return;
    }
    if (route.request().method() === "POST" && route.request().url().endsWith("/rotate")) {
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
      deleteMutationHeader = route.request().headers()["x-memoryos-csrf"];
      invitation.status = "REVOKED";
      invitation.revokedAt = "2026-08-21T11:00:00Z";
      await route.fulfill({ status: 204 });
      return;
    }
    await route.fulfill({ status: 405 });
  });

  await page.goto("/admin/invitations");
  await expect(page.getByRole("link", { name: "Invitations", exact: true })).toHaveAttribute(
    "aria-current",
    "page",
  );
  await expect(page.getByRole("heading", { name: "Invitations", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Invite member" }).click();
  await page.getByRole("textbox", { name: "Email address" }).fill("member@example.com");
  await page.getByRole("textbox", { name: "Email address" }).evaluate((input) => {
    const form = input.closest("form");
    if (!form) throw new Error("Invitation form is missing");
    form.dispatchEvent(new SubmitEvent("submit", { bubbles: true, cancelable: true }));
    form.dispatchEvent(new SubmitEvent("submit", { bubbles: true, cancelable: true }));
  });

  await expect(page.getByRole("heading", { name: "Invitation link ready" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Secure invitation link" })).toHaveValue(
    /\/invite\/one-time-secret$/,
  );
  expect(createMutationHeader).toBe("1");
  expect(createRequests).toBe(1);
  await page.evaluate(() => {
    navigator.clipboard.writeText = () => Promise.reject(new Error("clipboard denied"));
  });
  await page.getByRole("button", { name: "Copy" }).click();
  await expect(page.getByText("The invitation link could not be copied.")).toBeVisible();
  await page.getByRole("button", { name: "Done" }).click();
  await expect(page.getByText("member@example.com")).toBeVisible();
  await expect(
    page.getByRole("table", { name: "Organization invitations" }).getByText("Pending", {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Rotate invitation link for member@example.com" }).click();
  await expect(page.getByRole("textbox", { name: "Secure invitation link" })).toHaveValue(
    /\/invite\/rotated-secret$/,
  );
  await page.getByRole("button", { name: "Done" }).click();
  await page.getByRole("button", { name: "Revoke invitation for member@example.com" }).click();
  await expect(
    page.getByRole("table", { name: "Organization invitations" }).getByText("Revoked", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Rotate invitation link for member@example.com" }),
  ).toHaveCount(0);
  expect(deleteMutationHeader).toBe("1");
});
test("restores and updates the server-driven invitation view from the URL", async ({ page }) => {
  const invitations = Array.from({ length: 25 }, (_, index) => ({
    id: `75c4e810-e1f2-45cb-9480-${String(index + 1).padStart(12, "0")}`,
    email: `member${String(index + 1).padStart(2, "0")}@example.com`,
    status: index === 24 ? "REVOKED" : "PENDING",
    createdAt: new Date(Date.parse("2026-08-21T10:00:00Z") + index * 60_000).toISOString(),
    expiresAt: "2026-08-24T10:00:00Z",
    acceptedActorId: null,
    acceptedAt: null,
    revokedAt: index === 24 ? "2026-08-21T11:00:00Z" : null,
  }));

  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(OWNER_SESSION),
    });
  });
  await page.route("**/api/invitations?*", async (route) => {
    const url = new URL(route.request().url());
    const status = url.searchParams.get("status");
    const email = url.searchParams.get("email")?.toLowerCase();
    const sort = url.searchParams.get("sort") ?? "CREATED_AT_DESC";
    const pageIndex = Number(url.searchParams.get("page") ?? 0);
    const pageSize = Number(url.searchParams.get("size") ?? 20);
    const filtered = invitations
      .filter((invitation) => !status || invitation.status === status)
      .filter((invitation) => !email || invitation.email.includes(email))
      .toSorted((left, right) => {
        if (sort === "EMAIL_ASC") return left.email.localeCompare(right.email);
        if (sort === "EMAIL_DESC") return right.email.localeCompare(left.email);
        if (sort === "CREATED_AT_ASC") return left.createdAt.localeCompare(right.createdAt);
        return right.createdAt.localeCompare(left.createdAt);
      });
    const start = pageIndex * pageSize;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: filtered.slice(start, start + pageSize),
        page: pageIndex,
        size: pageSize,
        totalItems: filtered.length,
        totalPages: Math.ceil(filtered.length / pageSize),
      }),
    });
  });

  await page.goto("/admin/invitations?status=PENDING&email=member&sort=EMAIL_ASC&page=1&size=20");

  await expect(page.getByText("member21@example.com")).toBeVisible();
  await expect(page.getByText("Showing 21–24 of 24")).toBeVisible();
  await expect(page.getByRole("button", { name: "Next" })).toBeDisabled();

  await page.getByRole("button", { name: "Created" }).click();
  await expect(page).toHaveURL(
    /\/admin\/invitations\?status=PENDING&email=member&sort=CREATED_AT_DESC&page=0&size=20/,
  );

  await page.getByRole("searchbox", { name: "Email" }).fill("member02");
  await page.getByRole("button", { name: "Apply" }).click();
  await expect(page).toHaveURL(/email=member02/);
  await expect(page).toHaveURL(/page=0/);
  await expect(page.getByText("member02@example.com")).toBeVisible();

  await page.getByRole("combobox", { name: "Rows per page" }).selectOption("50");
  await expect(page).toHaveURL(/size=50/);
  await page.reload();
  await expect(page.getByText("member02@example.com")).toBeVisible();
});

test("shows the recipient invitation landing and recovery states", async ({ page }) => {
  await page.route("**/api/invitations/current", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        organizationDisplayName: "Tasco",
        expiresAt: "2026-08-24T10:00:00Z",
        continueUrl: "/invite/continue",
      }),
    });
  });

  await page.goto("/invitation");
  await expect(page.getByRole("heading", { name: "Join Tasco" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Continue to sign in" })).toHaveAttribute(
    "href",
    "/invite/continue",
  );
  await expect(page.getByText(/does not grant administration permissions/i)).toBeVisible();

  await page.goto("/invitation?reason=email-mismatch");
  await expect(page.getByRole("heading", { name: "Use the invited email" })).toBeVisible();
  await expect(page.getByText(/verified email does not match/i)).toBeVisible();
});
