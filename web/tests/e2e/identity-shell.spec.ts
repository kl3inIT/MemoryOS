import { createHash } from "node:crypto";
import { expect, test } from "@playwright/test";

const ACTOR_ID = "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1";
const OWNER_SESSION = {
  actorId: ACTOR_ID,
  authorizationVersion: 1,
  tenant: {
    displayName: "Tasco",
    role: "OWNER",
  },
  capabilities: ["USERS_MANAGE", "SOURCES_READ", "SOURCES_MANAGE", "SOURCES_DELETE"],
  scopedCapabilities: [],
};
const MEMBER_SESSION = {
  ...OWNER_SESSION,
  actorId: "97c41cb9-55ae-4a52-94ab-7aad59be91e5",
  tenant: { ...OWNER_SESSION.tenant, role: "MEMBER" },
  capabilities: [],
  scopedCapabilities: [],
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
  let userRequests = 0;
  let sourceRequests = 0;
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(MEMBER_SESSION),
    });
  });
  await page.route("**/api/users*", async (route) => {
    userRequests += 1;
    await route.fulfill({ status: 403 });
  });
  await page.route("**/api/sources**", async (route) => {
    sourceRequests += 1;
    await route.fulfill({ status: 403 });
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "Open navigation" }).click();
  await expect(page.getByRole("button", { name: "Tenant member" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Admin Panel" })).toHaveCount(0);

  await page.goto("/admin/users?status=ACTIVE");
  await expect(page).toHaveURL(/\/admin\/users\?status=ACTIVE/);
  await expect(
    page.getByRole("heading", { name: "You don’t have access to this area." }),
  ).toBeVisible();
  expect(userRequests).toBe(0);

  await page.goto("/admin/sources/15f8cb72-2628-4d75-bcf1-8f6cda95a120");
  await expect(
    page.getByRole("heading", { name: "You don’t have access to this area." }),
  ).toBeVisible();
  await page.goto("/admin/sources/new/file");
  await expect(
    page.getByRole("heading", { name: "You don’t have access to this area." }),
  ).toBeVisible();
  expect(sourceRequests).toBe(0);
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
  await page.getByRole("button", { name: "Tenant owner" }).click();
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
  await page.getByRole("button", { name: "Tenant owner" }).click();
  await page.getByRole("button", { name: "Use dark theme" }).click();
  await expect(page.locator("html")).toHaveClass(/dark/);

  await page.reload();
  await expect(page.locator("html")).toHaveClass(/dark/);
  await page.getByRole("button", { name: "Tenant owner" }).click();
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
  await expect(page.getByRole("heading", { name: "Existing sources", exact: true })).toBeVisible();
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
  await page.route("**/api/users?*", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: [],
        page: 0,
        size: 20,
        totalItems: 0,
        totalPages: 0,
        counts: { active: 0, inactive: 0, invited: 0 },
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
  await page.getByRole("link", { name: "Users", exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/users(?:\?|$)/);
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
  await page.route("**/api/users?*", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: [],
        page: 0,
        size: 20,
        totalItems: 0,
        totalPages: 0,
        counts: { active: 0, inactive: 0, invited: 0 },
      }),
    });
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/admin");
  await page.getByRole("button", { name: "Open navigation" }).click();
  await expect(page.getByRole("dialog", { name: "MemoryOS navigation" })).toBeVisible();
  await page.getByRole("link", { name: "Users", exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/users(?:\?|$)/);
  await expect(page.getByRole("dialog", { name: "MemoryOS navigation" })).toHaveCount(0);

  await page.getByRole("button", { name: "Open navigation" }).click();
  await page.getByRole("button", { name: "Tenant owner" }).click();
  await page.getByRole("link", { name: "Admin Panel" }).click();
  await expect(page).toHaveURL(/\/admin$/);
  await expect(page.getByRole("dialog", { name: "MemoryOS navigation" })).toHaveCount(0);
});

test("keeps unprovisioned access separate from signed-out state", async ({ page }) => {
  await page.goto("/access-not-provisioned");

  await expect(page.getByRole("heading", { name: /don’t have access yet/i })).toBeVisible();
  await expect(page.getByText(/has not been added to this memoryos tenant/i)).toBeVisible();
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

test("manages members and one-time invitation recovery from the Users view", async ({ page }) => {
  const expiresAt = "2026-09-20T10:00:00Z";
  const invitationId = "75c4e810-e1f2-45cb-9480-8e713a934bca";
  const memberActorId = "97c41cb9-55ae-4a52-94ab-7aad59be91e5";
  const userEntries: Array<Record<string, unknown>> = [
    {
      actorId: ACTOR_ID,
      invitationId: null,
      displayName: "Alex Morgan",
      email: "alex@example.com",
      emailVerified: true,
      profileIssuer: "https://identity.example.com",
      role: "OWNER",
      status: "ACTIVE",
      accountType: "STANDARD",
      groups: [
        {
          id: "6d11ec56-34c6-44fe-9ad0-f147f37f571c",
          name: "Admin",
          systemKey: "ADMIN",
        },
      ],
      invitationExpiresAt: null,
    },
    {
      actorId: memberActorId,
      invitationId: null,
      displayName: "Rowan Brooks",
      email: "rowan@example.com",
      emailVerified: true,
      profileIssuer: "https://identity.example.com",
      role: "MEMBER",
      status: "ACTIVE",
      accountType: "STANDARD",
      groups: [
        {
          id: "234e1244-b81e-471a-8a67-84f67ebc57b8",
          name: "Research",
          systemKey: null,
        },
      ],
      invitationExpiresAt: null,
    },
  ];
  let createMutationHeader: string | undefined;
  let membershipMutationHeader: string | undefined;
  let revokeMutationHeader: string | undefined;
  let createRequests = 0;
  let failNextUsersRefresh = false;
  let invitation: Record<string, unknown> | undefined;

  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(OWNER_SESSION),
    });
  });
  await page.route("**/api/users*", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname !== "/api/users") {
      await route.fallback();
      return;
    }
    if (failNextUsersRefresh) {
      failNextUsersRefresh = false;
      await route.fulfill({ status: 503 });
      return;
    }
    const counts = {
      active: userEntries.filter((entry) => entry.status === "ACTIVE").length,
      inactive: userEntries.filter((entry) => entry.status === "INACTIVE").length,
      invited: userEntries.filter((entry) => entry.status === "INVITED").length,
    };
    const status = url.searchParams.get("status");
    const visibleEntries = userEntries.filter((entry) => !status || entry.status === status);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: visibleEntries,
        page: 0,
        size: 20,
        totalItems: visibleEntries.length,
        totalPages: visibleEntries.length > 0 ? 1 : 0,
        counts,
      }),
    });
  });
  await page.route("**/api/invitations*", async (route) => {
    if (
      new URL(route.request().url()).pathname !== "/api/invitations" ||
      route.request().method() !== "POST"
    ) {
      await route.fallback();
      return;
    }
    createRequests += 1;
    createMutationHeader = route.request().headers()["x-memoryos-csrf"];
    invitation = {
      id: invitationId,
      email: "member@example.com",
      status: "PENDING",
      createdAt: "2026-09-06T10:00:00Z",
      expiresAt,
      acceptedActorId: null,
      acceptedAt: null,
      revokedAt: null,
    };
    userEntries.push({
      actorId: null,
      invitationId,
      displayName: null,
      email: "member@example.com",
      emailVerified: null,
      profileIssuer: null,
      role: null,
      status: "INVITED",
      accountType: null,
      groups: [],
      invitationExpiresAt: expiresAt,
    });
    failNextUsersRefresh = true;
    await route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({
        invitation,
        invitationUrl: "/invite/one-time-secret",
        delivery: "ACTIVATION_EMAIL_SENT",
      }),
    });
  });
  await page.route("**/api/invitations/**", async (route) => {
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
          delivery: "RECOVERY_LINK_ONLY",
        }),
      });
      return;
    }
    if (route.request().method() === "POST" && route.request().url().endsWith("/revoke")) {
      revokeMutationHeader = route.request().headers()["x-memoryos-csrf"];
      const invitedIndex = userEntries.findIndex((entry) => entry.invitationId === invitationId);
      if (invitedIndex >= 0) userEntries.splice(invitedIndex, 1);
      await route.fulfill({ status: 204 });
      return;
    }
    await route.fulfill({ status: 405 });
  });
  await page.route("**/api/users/**", async (route) => {
    const url = new URL(route.request().url());
    const member = userEntries.find((entry) => entry.actorId === memberActorId);
    if (!member || route.request().method() !== "POST") {
      await route.fulfill({ status: 404 });
      return;
    }
    membershipMutationHeader = route.request().headers()["x-memoryos-csrf"];
    if (url.pathname.endsWith("/deactivate")) member.status = "INACTIVE";
    else if (url.pathname.endsWith("/activate")) member.status = "ACTIVE";
    else {
      await route.fulfill({ status: 405 });
      return;
    }
    await route.fulfill({ status: 204 });
  });

  await page.goto("/admin/users");
  await expect(page.getByRole("link", { name: "Users", exact: true })).toHaveAttribute(
    "aria-current",
    "page",
  );
  await expect(page.getByRole("heading", { name: "Users", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Invite member" }).click();
  await page.getByRole("textbox", { name: "Email address" }).fill("member@example.com");
  await page.getByRole("textbox", { name: "Email address" }).evaluate((input) => {
    const form = input.closest("form");
    if (!form) throw new Error("Invitation form is missing");
    form.dispatchEvent(new SubmitEvent("submit", { bubbles: true, cancelable: true }));
    form.dispatchEvent(new SubmitEvent("submit", { bubbles: true, cancelable: true }));
  });

  await expect(page.getByRole("heading", { name: "Activation email sent" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Secure invitation link" })).toHaveValue(
    /\/invite\/one-time-secret$/,
  );
  expect(createMutationHeader).toBe("1");
  expect(createRequests).toBe(1);
  await page.evaluate(() => {
    navigator.clipboard.writeText = () => Promise.reject(new Error("clipboard denied"));
  });
  await page.getByRole("button", { name: "Copy" }).click();
  await expect(page.getByText(/invitation link could not be copied/i)).toBeVisible();
  await page.getByRole("button", { name: "Done" }).click();

  await page.getByRole("button", { name: "Retry refresh" }).click();
  await expect(page.getByText("member@example.com")).toBeVisible();
  await expect(
    page.getByRole("table", { name: "Tenant users" }).getByText("Invited", { exact: true }),
  ).toBeVisible();

  await page.getByRole("button", { name: "Actions for member@example.com" }).click();
  await page.getByRole("button", { name: "Rotate recovery link" }).click();
  await expect(page.getByRole("textbox", { name: "Secure invitation link" })).toHaveValue(
    /\/invite\/rotated-secret$/,
  );
  await expect(page.getByRole("heading", { name: "Recovery link rotated" })).toBeVisible();
  await page.getByRole("button", { name: "Done" }).click();
  await expect(page.getByRole("button", { name: "Actions for member@example.com" })).toBeFocused();

  await page.getByRole("button", { name: "Actions for member@example.com" }).click();
  await page.getByRole("button", { name: "Revoke invitation" }).click();
  const revokeDialog = page.getByRole("alertdialog");
  await expect(revokeDialog.getByRole("button", { name: "Cancel" })).toBeFocused();
  await revokeDialog.getByRole("button", { name: "Revoke invitation" }).click();
  await expect(page.getByText("member@example.com")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Invite member" })).toBeFocused();
  expect(revokeMutationHeader).toBe("1");

  await page.getByRole("button", { name: "Show active users, 2" }).click();
  await page.getByRole("button", { name: "Actions for Rowan Brooks" }).click();
  await page.getByRole("button", { name: "Deactivate member" }).click();
  const deactivateDialog = page.getByRole("alertdialog");
  await expect(deactivateDialog.getByRole("button", { name: "Cancel" })).toBeFocused();
  await deactivateDialog.getByRole("button", { name: "Deactivate member" }).click();
  await expect(page.getByRole("row").filter({ hasText: "Rowan Brooks" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Invite member" })).toBeFocused();
  await page.getByRole("button", { name: "Show inactive users, 1" }).click();
  await expect(page.getByRole("row").filter({ hasText: "Rowan Brooks" })).toContainText("Inactive");

  await page.getByRole("button", { name: "Actions for Rowan Brooks" }).click();
  await page.getByRole("button", { name: "Activate member" }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Activate member" }).click();
  await expect(page.getByRole("row").filter({ hasText: "Rowan Brooks" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Invite member" })).toBeFocused();
  await page.getByRole("button", { name: "Clear filters" }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "Rowan Brooks" }).getByText("Active"),
  ).toBeVisible();
  expect(membershipMutationHeader).toBe("1");
});

test("restores and updates the bounded server-driven Users view from the URL", async ({ page }) => {
  await page.clock.install();
  const entries = [
    {
      actorId: ACTOR_ID,
      invitationId: null,
      displayName: "Alex Morgan",
      email: "alex@example.com",
      emailVerified: true,
      profileIssuer: "https://identity.example.com",
      role: "OWNER",
      status: "ACTIVE",
      accountType: "STANDARD",
      groups: [],
      invitationExpiresAt: null,
    },
    ...Array.from({ length: 25 }, (_, index) => {
      const sequence = String(index + 1).padStart(2, "0");
      return {
        actorId: `97c41cb9-55ae-4a52-94ab-${String(index + 1).padStart(12, "0")}`,
        invitationId: null,
        displayName: `Member ${sequence}`,
        email: `member${sequence}@example.com`,
        emailVerified: true,
        profileIssuer: "https://identity.example.com",
        role: "MEMBER",
        status: index === 24 ? "INACTIVE" : "ACTIVE",
        accountType: "STANDARD",
        groups: [],
        invitationExpiresAt: null,
      };
    }),
  ];

  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(OWNER_SESSION),
    });
  });
  let delayRestoredPage = false;
  const restoredPageResponse = Promise.withResolvers<void>();
  await page.route("**/api/users?*", async (route) => {
    const url = new URL(route.request().url());
    const status = url.searchParams.get("status");
    const role = url.searchParams.get("role");
    const search = url.searchParams.get("search")?.toLowerCase();
    const sort = url.searchParams.get("sort") ?? "NAME_ASC";
    const pageIndex = Number(url.searchParams.get("page") ?? 0);
    const pageSize = Number(url.searchParams.get("size") ?? 20);
    if (delayRestoredPage && status === "ACTIVE" && pageIndex === 1) {
      await restoredPageResponse.promise;
    }
    const field = sort.slice(0, sort.lastIndexOf("_"));
    const descending = sort.endsWith("_DESC");
    const filtered = entries
      .filter((entry) => !status || entry.status === status)
      .filter((entry) => !role || entry.role === role)
      .filter(
        (entry) =>
          !search ||
          entry.displayName.toLowerCase().includes(search) ||
          entry.email.toLowerCase().includes(search),
      )
      .toSorted((left, right) => {
        const leftValue = String(
          field === "NAME"
            ? left.displayName
            : field === "EMAIL"
              ? left.email
              : field === "STATUS"
                ? left.status
                : left.role,
        );
        const rightValue = String(
          field === "NAME"
            ? right.displayName
            : field === "EMAIL"
              ? right.email
              : field === "STATUS"
                ? right.status
                : right.role,
        );
        return (descending ? rightValue : leftValue).localeCompare(
          descending ? leftValue : rightValue,
        );
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
        counts: { active: 25, inactive: 1, invited: 0 },
      }),
    });
  });

  await page.goto(
    "/admin/users?status=ACTIVE&search=member&role=MEMBER&sort=EMAIL_ASC&page=1&size=20",
  );

  await expect(page.getByRole("searchbox", { name: "Search users" })).toHaveValue("member");
  await expect(page.getByRole("combobox", { name: "Filter by role" })).toHaveValue("MEMBER");
  await expect(page.getByText("member21@example.com")).toBeVisible();
  await expect(page.getByText("Showing 21–24 of 24")).toBeVisible();
  await expect(page.getByRole("button", { name: "Next" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Show inactive users, 1" })).toBeVisible();

  await page.getByRole("button", { name: "Show inactive users, 1" }).click();
  await expect(page).toHaveURL(/status=INACTIVE/);
  await expect(page).toHaveURL(/page=0/);
  await expect(page.getByText("member25@example.com")).toBeVisible();

  await page.clock.fastForward(301_000);
  delayRestoredPage = true;
  const restoredRequest = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return (
      url.pathname === "/api/users" &&
      url.searchParams.get("status") === "ACTIVE" &&
      url.searchParams.get("page") === "1"
    );
  });
  await page.goBack();
  await restoredRequest;
  await expect(page).toHaveURL(/page=1/);
  delayRestoredPage = false;
  restoredPageResponse.resolve();
  await expect(page.getByText("Showing 21–24 of 24")).toBeVisible();
  await expect(page).toHaveURL(/page=1/);
  await page.goForward();
  await expect(page.getByText("member25@example.com")).toBeVisible();

  await page.getByRole("button", { name: "Show active users, 25" }).click();
  await page.getByRole("button", { name: "Sort by name" }).click();
  await expect(page).toHaveURL(/sort=NAME_ASC/);
  await page.getByRole("searchbox", { name: "Search users" }).fill("member02");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await expect(page).toHaveURL(/search=member02/);
  await expect(page.getByText("member02@example.com")).toBeVisible();

  await page.getByRole("combobox", { name: "Rows per page" }).selectOption("50");
  await expect(page).toHaveURL(/size=50/);
  await page.reload();
  await expect(page.getByText("member02@example.com")).toBeVisible();
  await expect(page.getByRole("searchbox", { name: "Search users" })).toHaveValue("member02");
});

test("shows the recipient invitation landing and recovery states", async ({ page }) => {
  await page.route("**/api/invitations/current", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        tenantDisplayName: "Tasco",
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

test("creates, indexes, removes, and deletes a FILE source", async ({ page }) => {
  const source = {
    id: "15f8cb72-2628-4d75-bcf1-8f6cda95a120",
    name: "Product documentation",
    type: "FILE",
    access: "PUBLIC",
    status: "NOT_STARTED",
    pendingWork: false,
    documentCount: 0,
    lastSucceededAt: null,
    errorCode: null,
    actions: ["upload", "reindex", "remove_items", "delete", "manage_groups"],
  };
  const otherSource = {
    ...source,
    id: "25f8cb72-2628-4d75-bcf1-8f6cda95a120",
    name: "Support notes",
  };
  const items: Array<Record<string, unknown>> = [];
  const mutationHeaders: string[] = [];
  const apiUploadBodies: Array<Buffer | null> = [];
  const uploadedFile = Buffer.from("MemoryOS browser source");
  const checksum = createHash("sha256").update(uploadedFile).digest("hex");
  const checksumBase64 = createHash("sha256").update(uploadedFile).digest("base64");
  let sourceDeleted = false;
  let sourceCreated = false;
  let createAttempts = 0;
  let removeAttempts = 0;
  let finalizeAttempts = 0;
  let objectStoragePuts = 0;
  let includeOtherSource = true;
  let storedBytes: Buffer | null = null;
  await page.route("**/api/identity/me", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(OWNER_SESSION),
    });
  });
  await page.route("**/api/source-operations/**", async (route) => {
    sourceDeleted = true;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: "4e54f788-33b7-4f20-b3e0-12bd445a598a",
        type: "DELETE_SOURCE",
        status: "SUCCEEDED",
        createdAt: "2026-08-27T10:00:00Z",
        completedAt: "2026-08-27T10:00:01Z",
        errorCode: null,
      }),
    });
  });
  await page.route("https://objects.example.test/**", async (route) => {
    const request = route.request();
    if (request.method() === "OPTIONS") {
      await route.fulfill({
        status: 204,
        headers: {
          "access-control-allow-origin": "*",
          "access-control-allow-methods": "PUT",
          "access-control-allow-headers": "content-type,x-amz-checksum-sha256",
        },
      });
      return;
    }
    expect(request.method()).toBe("PUT");
    expect(request.headers()["content-type"]).toBe("text/plain");
    expect(request.headers()["x-amz-checksum-sha256"]).toBe(checksumBase64);
    objectStoragePuts += 1;
    storedBytes = request.postDataBuffer();
    await route.fulfill({
      status: 200,
      headers: { "access-control-allow-origin": "*" },
    });
  });

  await page.route("**/api/sources**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === "GET" && path === "/api/sources/group-options") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          items: [
            {
              id: "6d11ec56-34c6-44fe-9ad0-f147f37f571c",
              name: "Admin",
              systemKey: "ADMIN",
            },
          ],
          page: 0,
          size: 25,
          totalItems: 1,
          totalPages: 1,
        }),
      });
      return;
    }
    if (request.method() === "GET" && path.endsWith("/groups")) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          items: [
            {
              id: "6d11ec56-34c6-44fe-9ad0-f147f37f571c",
              name: "Admin",
              systemKey: "ADMIN",
            },
          ],
        }),
      });
      return;
    }
    if (request.method() === "GET" && path === "/api/sources") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          sourceDeleted
            ? []
            : [...(sourceCreated ? [source] : []), ...(includeOtherSource ? [otherSource] : [])],
        ),
      });
      return;
    }
    if (request.method() === "POST" && path === "/api/sources/file") {
      createAttempts += 1;
      mutationHeaders.push(request.headers()["x-memoryos-csrf"] ?? "");
      expect(request.postDataJSON()).toEqual({ name: source.name });
      sourceCreated = true;
      await page.waitForTimeout(100);
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({ source, items }),
      });
      return;
    }
    if (request.method() === "GET" && path === `/api/sources/${source.id}`) {
      await route.fulfill({
        status: sourceDeleted ? 404 : 200,
        contentType: "application/json",
        body: sourceDeleted ? "{}" : JSON.stringify({ source, items }),
      });
      return;
    }
    if (request.method() === "GET" && path === `/api/sources/${otherSource.id}`) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ source: otherSource, items: [] }),
      });
      return;
    }
    if (request.method() === "POST" && path === `/api/sources/${source.id}/uploads`) {
      mutationHeaders.push(request.headers()["x-memoryos-csrf"] ?? "");
      apiUploadBodies.push(request.postDataBuffer());
      expect(request.postDataJSON()).toEqual({
        filename: "knowledge.txt",
        mediaType: "text/plain",
        sizeBytes: uploadedFile.length,
        sha256: checksum,
      });
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({
          uploadId: "ac15afe3-88b3-4627-a737-51d8c4c1b290",
          method: "PUT",
          uploadUrl: "https://objects.example.test/memoryos/raw/upload",
          requiredHeaders: {
            "content-type": "text/plain",
            "x-amz-checksum-sha256": checksumBase64,
          },
          expiresAt: "2026-08-27T10:10:00Z",
        }),
      });
      return;
    }
    if (
      request.method() === "POST" &&
      path === `/api/sources/${source.id}/uploads/ac15afe3-88b3-4627-a737-51d8c4c1b290/finalize`
    ) {
      mutationHeaders.push(request.headers()["x-memoryos-csrf"] ?? "");
      apiUploadBodies.push(request.postDataBuffer());
      finalizeAttempts += 1;
      if (finalizeAttempts === 1) {
        await route.fulfill({
          status: 503,
          contentType: "application/problem+json",
          body: JSON.stringify({ title: "Unavailable", status: 503 }),
        });
        return;
      }
      includeOtherSource = false;
      source.status = "ACTIVE";
      source.documentCount = 1;
      items.push({
        id: "71923275-0c07-44e0-9537-1f4f67259dc7",
        filename: "knowledge.txt",
        sha256: checksum,
        sizeBytes: uploadedFile.length,
        status: "INDEXED",
        uploadedAt: "2026-08-27T10:00:00Z",
        latestOperationId: "3aca91f5-53e8-4c9b-8e3a-1afedbd4a18f",
        errorCode: null,
      });
      await route.fulfill({
        status: 202,
        contentType: "application/json",
        body: JSON.stringify({
          item: items[0],
          operation: {
            id: "3aca91f5-53e8-4c9b-8e3a-1afedbd4a18f",
            type: "INDEX",
            status: "NOT_STARTED",
            createdAt: "2026-08-27T10:00:00Z",
          },
        }),
      });
      return;
    }
    if (request.method() === "POST" && path.endsWith("/index-attempts")) {
      mutationHeaders.push(request.headers()["x-memoryos-csrf"] ?? "");
      await route.fulfill({
        status: 409,
        contentType: "application/problem+json",
        body: JSON.stringify({
          title: "Conflict",
          status: 409,
          code: "SOURCE_CONFLICT",
        }),
      });
      return;
    }
    if (request.method() === "POST" && path.endsWith("/remove")) {
      mutationHeaders.push(request.headers()["x-memoryos-csrf"] ?? "");
      removeAttempts += 1;
      if (removeAttempts === 1) {
        await route.fulfill({
          status: 409,
          contentType: "application/problem+json",
          body: JSON.stringify({
            title: "Conflict",
            status: 409,
            code: "SOURCE_CONFLICT",
          }),
        });
        return;
      }
      await page.waitForTimeout(250);
      items.length = 0;
      source.status = "NOT_STARTED";
      source.documentCount = 0;
      await route.fulfill({
        status: 202,
        contentType: "application/json",
        body: JSON.stringify({
          id: "19d557e6-fd95-461e-b2e7-0a7f858170dd",
          type: "REMOVE_ITEM",
          status: "NOT_STARTED",
          createdAt: "2026-08-27T10:00:00Z",
        }),
      });
      return;
    }
    if (request.method() === "POST" && path.endsWith("/delete")) {
      mutationHeaders.push(request.headers()["x-memoryos-csrf"] ?? "");
      source.status = "DELETING";
      await route.fulfill({
        status: 202,
        contentType: "application/json",
        body: JSON.stringify({
          id: "4e54f788-33b7-4f20-b3e0-12bd445a598a",
          type: "DELETE_SOURCE",
          status: "NOT_STARTED",
          createdAt: "2026-08-27T10:00:00Z",
        }),
      });
      return;
    }
    await route.fulfill({ status: 405 });
  });

  await page.goto("/admin");
  const sourceTable = page.getByRole("table", { name: "Connected sources" });
  await expect(page.getByRole("heading", { name: "Existing sources" })).toBeVisible();
  await expect(
    sourceTable.getByRole("columnheader", { name: "Permissions / Access" }),
  ).toBeVisible();
  await expect(sourceTable.getByText("Scheduled", { exact: true })).toBeVisible();
  await expect(sourceTable.getByText("Organization Public", { exact: true })).toBeVisible();
  const fileGroup = sourceTable.getByRole("button", {
    name: /File group, 1 sources, 0 documents/,
  });
  const supportSource = sourceTable.getByRole("link", { name: otherSource.name, exact: true });
  await expect(fileGroup).toHaveAttribute("aria-expanded", "true");
  await expect(fileGroup.locator("xpath=ancestor::tr")).toContainText("Total sources");
  await expect(fileGroup.locator("xpath=ancestor::tr")).toContainText("Active sources");
  await expect(fileGroup.locator("xpath=ancestor::tr")).toContainText("Public sources");
  await expect(fileGroup.locator("xpath=ancestor::tr")).toContainText("Total docs indexed");
  const sourceSearch = page.getByRole("searchbox", { name: "Search sources" });
  await sourceSearch.fill("missing source");
  await expect(page.getByText("No sources match your search and filters.")).toBeVisible();
  await sourceSearch.fill("");
  await page.getByRole("button", { name: "Filter sources" }).click();
  await page.getByLabel("Status").selectOption("ACTIVE");
  await expect(page.getByText("No sources match your search and filters.")).toBeVisible();
  await page.getByRole("button", { name: "Clear filters" }).click();
  await expect(supportSource).toBeVisible();
  await page.getByRole("button", { name: "Filter sources" }).click();
  await page.getByRole("button", { name: "Collapse all" }).click();
  await expect(supportSource).toBeHidden();
  await page.getByRole("button", { name: "Expand all" }).click();
  await expect(supportSource).toBeVisible();
  await fileGroup.click();
  await expect(supportSource).toBeHidden();
  await fileGroup.click();
  await expect(supportSource).toBeVisible();
  await supportSource.click();
  await expect(page).toHaveURL(new RegExp(`/admin/sources/${otherSource.id}$`));
  await expect(page.getByRole("heading", { name: otherSource.name })).toBeVisible();
  await page.goBack();
  await expect(page).toHaveURL(/\/admin$/);
  await page.getByRole("link", { name: "Add source" }).first().click();
  await expect(page).toHaveURL(/\/admin\/sources\/new\/?$/);
  await expect(page.getByRole("heading", { name: "Add a source" })).toBeVisible();
  const providerSearch = page.getByRole("searchbox", { name: "Search sources" });
  await expect(providerSearch).toBeFocused();
  await providerSearch.fill("not implemented");
  await expect(page.getByText("No sources match your search.")).toBeVisible();
  await providerSearch.press("Enter");
  await expect(page).toHaveURL(/\/admin\/sources\/new\/?$/);
  await providerSearch.fill("");

  const fileProvider = page.getByRole("link", { name: "File", exact: true });
  await expect(fileProvider).toHaveCount(1);
  await fileProvider.click();
  await expect(page).toHaveURL(/\/admin\/sources\/new\/file$/);
  await expect(page.getByRole("heading", { name: "Add file source" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Setup progress" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Continue", exact: true })).toHaveCount(0);

  const createSource = page.getByRole("button", { name: "Upload and create" });
  await expect(createSource).toBeDisabled();
  await page.getByRole("textbox", { name: "Source name" }).fill(source.name);
  await expect(createSource).toBeDisabled();
  await page.getByLabel("Choose PDF, DOCX, PPTX, TXT, or Markdown file").setInputFiles({
    name: "knowledge.txt",
    mimeType: "text/plain",
    buffer: uploadedFile,
  });
  await createSource.evaluate((button: HTMLButtonElement) => {
    button.click();
    button.click();
  });
  await expect(page).toHaveURL(/\/admin\/sources\/new\/file$/);
  await expect(page.getByRole("button", { name: "Retry finalization" })).toBeVisible();
  expect(createAttempts).toBe(1);
  await expect(page.getByRole("status")).toContainText(
    "The file reached object storage; retry finalization without uploading it again.",
  );
  await page.getByRole("link", { name: "Sources", exact: true }).last().click();
  await expect(page).toHaveURL(/\/admin$/);
  await page
    .getByRole("table", { name: "Connected sources" })
    .getByRole("link", { name: otherSource.name, exact: true })
    .click();
  await expect(page.getByRole("heading", { name: otherSource.name })).toBeVisible();
  await page.getByRole("link", { name: "Return to pending upload" }).click();
  await expect(page).toHaveURL(new RegExp(`/admin/sources/${source.id}$`));
  await page.getByRole("button", { name: "Retry finalization" }).click();
  await expect(page.getByText("knowledge.txt")).toBeVisible();
  expect(objectStoragePuts).toBe(1);
  expect(storedBytes).toEqual(uploadedFile);
  expect(apiUploadBodies.some((body) => body?.equals(uploadedFile))).toBe(false);
  await page.getByRole("button", { name: "Reindex" }).click();
  await expect(page.getByRole("alert")).toHaveText(
    "The source cannot accept that operation right now.",
  );

  const removeTrigger = page.getByRole("button", { name: "Remove" });
  await removeTrigger.click();
  let confirmation = page.getByRole("alertdialog");
  await expect(confirmation.getByRole("heading", { name: "Remove knowledge.txt?" })).toBeVisible();
  await expect(
    confirmation.getByText(
      "Removing “knowledge.txt” makes its indexed document unavailable. Cleanup continues asynchronously.",
    ),
  ).toBeVisible();
  await expect(confirmation.getByRole("button", { name: "Cancel" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(confirmation).not.toBeVisible();
  expect(removeAttempts).toBe(0);

  await removeTrigger.click();
  confirmation = page.getByRole("alertdialog");
  await confirmation.getByRole("button", { name: "Cancel" }).click();
  expect(removeAttempts).toBe(0);

  await removeTrigger.click();
  confirmation = page.getByRole("alertdialog");
  await confirmation.getByRole("button", { name: "Remove file" }).click();
  await expect(confirmation.getByRole("alert")).toHaveText(
    "This file is already changing. Refresh the source and try again.",
  );
  await expect(page.getByRole("alert")).toHaveCount(1);
  await expect(confirmation).toBeVisible();

  await confirmation.getByRole("button", { name: "Remove file" }).click();
  const pendingRemove = confirmation.getByRole("button", { name: "Removing file" });
  await expect(pendingRemove).toBeDisabled();
  await expect(pendingRemove).toHaveAttribute("aria-busy", "true");
  await pendingRemove.evaluate((button: HTMLButtonElement) => button.click());
  expect(removeAttempts).toBe(2);
  await expect(confirmation).not.toBeVisible();
  await expect(page.getByText("No files yet")).toBeVisible();

  await page.getByRole("button", { name: "Delete source" }).click();
  confirmation = page.getByRole("alertdialog");
  await expect(
    confirmation.getByRole("heading", { name: "Delete Product documentation?" }),
  ).toBeVisible();
  await expect(
    confirmation.getByText(
      "Deleting “Product documentation” makes every indexed document from this source unavailable. Cleanup continues asynchronously and cannot be undone.",
    ),
  ).toBeVisible();
  await confirmation.getByRole("button", { name: "Delete source" }).click();
  await expect(page.getByText("No sources yet")).toBeVisible();
  expect(mutationHeaders).toEqual(["1", "1", "1", "1", "1", "1", "1", "1"]);
});
