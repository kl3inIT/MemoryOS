import { createHash } from "node:crypto";
import { expect, test } from "@playwright/test";

const ACTOR_ID = "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1";
const OWNER_SESSION = {
  actorId: ACTOR_ID,
  tenant: {
    displayName: "Tasco",
    role: "OWNER",
  },
  capabilities: ["INVITATIONS_MANAGE", "SOURCES_MANAGE"],
};
const MEMBER_SESSION = {
  ...OWNER_SESSION,
  actorId: "97c41cb9-55ae-4a52-94ab-7aad59be91e5",
  tenant: { ...OWNER_SESSION.tenant, role: "MEMBER" },
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
  let sourceRequests = 0;
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
  await page.route("**/api/sources**", async (route) => {
    sourceRequests += 1;
    await route.fulfill({ status: 403 });
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "Open navigation" }).click();
  await expect(page.getByRole("button", { name: "Tenant member" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Admin Panel" })).toHaveCount(0);

  await page.goto("/admin/invitations?status=PENDING");
  await expect(page).toHaveURL(/\/admin\/invitations\?status=PENDING/);
  await expect(
    page.getByRole("heading", { name: "You don’t have access to this area." }),
  ).toBeVisible();
  expect(invitationRequests).toBe(0);

  await page.goto("/admin?sourceId=15f8cb72-2628-4d75-bcf1-8f6cda95a120");
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

test("creates a production invitation from the Invitations administration page", async ({
  page,
}) => {
  const expiresAt = "2026-08-24T10:00:00Z";
  const invitations: Array<Record<string, unknown>> = [];
  let createMutationHeader: string | undefined;
  let createRequests = 0;
  let revokeMutationHeader: string | undefined;

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
          delivery: "ACTIVATION_EMAIL_SENT",
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
          delivery: "RECOVERY_LINK_ONLY",
        }),
      });
      return;
    }
    if (route.request().method() === "POST" && route.request().url().endsWith("/revoke")) {
      revokeMutationHeader = route.request().headers()["x-memoryos-csrf"];
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
  await expect(page.getByText("The invitation link could not be copied.")).toBeVisible();
  await page.getByRole("button", { name: "Done" }).click();
  await expect(page.getByText("member@example.com")).toBeVisible();
  await expect(
    page.getByRole("table", { name: "Tenant invitations" }).getByText("Pending", {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Rotate invitation link for member@example.com" }).click();
  await expect(page.getByRole("textbox", { name: "Secure invitation link" })).toHaveValue(
    /\/invite\/rotated-secret$/,
  );
  await expect(page.getByRole("heading", { name: "Recovery link rotated" })).toBeVisible();
  await page.getByRole("button", { name: "Done" }).click();
  await page.getByRole("button", { name: "Revoke invitation for member@example.com" }).click();
  await expect(
    page.getByRole("table", { name: "Tenant invitations" }).getByText("Revoked", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Rotate invitation link for member@example.com" }),
  ).toHaveCount(0);
  expect(revokeMutationHeader).toBe("1");
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

  await page.goto("/admin?sourceId=%20%20");
  await expect(page.getByRole("heading", { name: otherSource.name })).toBeVisible();
  await page.getByRole("link", { name: "Add source" }).first().click();
  await expect(page).toHaveURL(/\/admin\/sources\/new\/?$/);
  await expect(page.getByRole("heading", { name: "Add a source" })).toBeVisible();

  const fileProvider = page.getByRole("link", { name: /Files Upload PDF, DOCX, TXT/ });
  await expect(fileProvider).toHaveCount(1);
  await fileProvider.click();
  await expect(page).toHaveURL(/\/admin\/sources\/new\/file$/);
  await expect(page.getByRole("heading", { name: "Name this file source" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Setup progress" })).toContainText("Configuration");

  const createSource = page.getByRole("button", { name: "Create source" });
  await expect(createSource).toBeDisabled();
  await page.getByRole("textbox", { name: "Source name" }).fill(source.name);
  await createSource.evaluate((button: HTMLButtonElement) => {
    button.click();
    button.click();
  });
  await expect(page).toHaveURL(new RegExp(`/admin\\?sourceId=${source.id}$`));
  await expect(page.getByRole("heading", { name: source.name })).toBeVisible();
  expect(createAttempts).toBe(1);

  await page.goBack();
  await expect(page).toHaveURL(/\/admin\/sources\/new\/file$/);
  await page.goForward();
  await expect(page).toHaveURL(new RegExp(`/admin\\?sourceId=${source.id}$`));
  await expect(page.getByRole("heading", { name: source.name })).toBeVisible();

  await page.getByLabel("Choose PDF, DOCX, TXT, or Markdown file").setInputFiles({
    name: "knowledge.txt",
    mimeType: "text/plain",
    buffer: uploadedFile,
  });
  await page.getByRole("button", { name: "Upload file" }).click();
  await expect(page.getByRole("button", { name: "Retry finalization" })).toBeVisible();
  await expect(page.getByRole("alert")).toContainText(
    "The file reached object storage; retry finalization without uploading it again.",
  );
  await page.getByText(otherSource.name, { exact: true }).click();
  await expect(page.getByRole("heading", { name: otherSource.name })).toBeVisible();
  await page.getByRole("button", { name: "Retry finalization" }).click();
  await page.getByText(source.name, { exact: true }).click();
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
  await expect(page.getByText("No sources connected")).toBeVisible();
  expect(mutationHeaders).toEqual(["1", "1", "1", "1", "1", "1", "1", "1"]);
});
