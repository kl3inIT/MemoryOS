import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.route("**/api/sources/group-options**", (route) =>
    route.fulfill({ json: { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 } }),
  );
  await page.route("**/api/sources/google-drive/selection-policy", (route) =>
    route.fulfill({
      json: {
        maxExplicitRootsPerSource: 1000,
        maxRequestBytes: 2_097_152,
        maxLinkedDocuments: 500,
      },
    }),
  );
});

test("scoped setup uses credential actions and requires managed groups before proposing Drive creation", async ({
  page,
}) => {
  const managedGroup = {
    id: "6d11ec56-34c6-44fe-9ad0-f147f37f571c",
    name: "Managed team",
    systemKey: null,
  };
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        ...owner,
        capabilities: [],
        scopedCapabilities: ["SOURCES_READ", "SOURCES_MANAGE"],
      },
    }),
  );
  await page.route("**/api/credentials/google-drive", (route) =>
    route.fulfill({ json: [{ ...credential, actions: [] }] }),
  );
  await page.route("**/api/sources/group-options**", (route) =>
    route.fulfill({
      json: {
        items: [managedGroup],
        page: 0,
        size: 25,
        totalItems: 1,
        totalPages: 1,
      },
    }),
  );
  await page.goto("/admin/sources/new/google-drive");
  await expect(page.getByRole("button", { name: `Manage ${credential.name}` })).toHaveCount(0);
  await page.getByRole("radio", { name: `Select ${credential.name}` }).check();
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByLabel("Source name").fill("Managed Drive");
  await page
    .getByRole("textbox", { name: "File or folder links" })
    .fill("https://drive.google.com/file/d/file-a/view");
  await expect(page.getByRole("button", { name: "Create Source", exact: true })).toBeDisabled();
  await page.getByRole("checkbox", { name: /Managed team/ }).check();
  const submitted = page.waitForRequest(
    (request) =>
      request.method() === "POST" &&
      new URL(request.url()).pathname === "/api/sources/google-drive",
  );
  await page.route("**/api/sources/google-drive", (route) =>
    route.fulfill({
      status: 403,
      json: { code: "IAM_ACCESS_DENIED", status: 403 },
    }),
  );
  await page.getByRole("button", { name: "Create Source", exact: true }).click();
  expect((await submitted).postDataJSON().groupIds).toEqual([managedGroup.id]);
  await expect(page.getByRole("alert").filter({ hasText: /permissions changed/ })).toBeVisible();
});

const owner = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Team", role: "OWNER" },
  capabilities: ["SOURCES_READ", "SOURCES_MANAGE", "SOURCES_DELETE"],
  scopedCapabilities: [],
};
const credential = {
  id: "81c51573-31a9-4e67-91c5-f276960c94af",
  name: "Shared team Google account",
  accountEmail: "owner@example.com",
  status: "ACTIVE",
  credentialRevision: 3,
  oauthClientConfigured: true,
  createdAt: "2026-09-01T10:00:00Z",
  updatedAt: "2026-09-02T14:30:00Z",
  sourceCount: 0,
  actions: ["reauthorize", "replace_oauth_client", "revoke", "delete"],
};

test("pasted and uploaded OAuth JSON are cleared on rejection and modal close", async ({
  page,
}) => {
  const secret = "synthetic-paste-secret";
  const json = JSON.stringify({
    web: { client_id: "test.apps.googleusercontent.com", client_secret: secret },
  });
  let authorizationRequests = 0;
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: owner }));
  await page.route("**/api/credentials/google-drive**", async (route) => {
    if (route.request().method() === "POST") {
      authorizationRequests += 1;
      await route.fulfill({
        status: 400,
        json: { code: "GOOGLE_DRIVE_OAUTH_CLIENT_INVALID", detail: json },
      });
    } else await route.fulfill({ json: [] });
  });
  await page.goto("/admin/sources/new/google-drive");
  await page.getByRole("button", { name: "Create New" }).click();
  await page.getByLabel("Credential name").fill(credential.name);
  const input = page.getByRole("textbox", { name: "Upload or paste OAuth app JSON" });
  await input.fill(JSON.stringify({ installed: { client_id: "desktop", client_secret: secret } }));
  await expect(page.getByRole("button", { name: "Authenticate" })).toBeDisabled();
  expect(authorizationRequests).toBe(0);
  await input.fill(json);
  await page.getByRole("button", { name: "Authenticate" }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByRole("alert")).not.toContainText(secret);
  await expect(
    page.getByRole("listitem", { name: "Credential connected", exact: true, includeHidden: true }),
  ).toHaveCount(0);
  await expect(input).toHaveValue("");
  await expect(page.getByRole("button", { name: "Authenticate" })).toBeDisabled();
  expect(authorizationRequests).toBe(1);
  await input.fill(json);
  await page.getByRole("button", { name: "Close credential dialog" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Create New" })).toBeFocused();
  await page.getByRole("button", { name: "Create New" }).click();
  await expect(input).toHaveValue("");
  await expect(page.getByLabel("Credential name")).toHaveValue("");
  await page.getByLabel("Upload OAuth client JSON").setInputFiles({
    name: "client.json",
    mimeType: "application/json",
    buffer: Buffer.from(json),
  });
  await expect(input).toHaveValue("client.json");
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Create New" }).click();
  await expect(input).toHaveValue("");
  await expect(page.getByLabel("Upload OAuth client JSON")).toHaveValue("");
  await expect(page.getByRole("button", { name: "Authenticate" })).toBeDisabled();
  expect(authorizationRequests).toBe(1);
  expect(await page.evaluate(() => JSON.stringify([localStorage, sessionStorage]))).not.toContain(
    secret,
  );
});

test("shared credential revoke is confirmed, reconnect reuses its app, and attached or stale deletion is guarded", async ({
  page,
}) => {
  let shared = { ...credential, sourceCount: 2 };
  let unusedVisible = true;
  const unused = {
    ...credential,
    id: "03f7a52d-3b31-40a7-a790-c005e6477dda",
    name: "Unused account",
  };
  let rejectDelete = true;
  let rejectRevoke = true;
  let revokes = 0;
  let deletes = 0;
  let reconnects = 0;
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: owner }));
  await page.route("**/api/sources", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/credentials/google-drive**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === "GET") {
      await route.fulfill({ json: [shared, ...(unusedVisible ? [unused] : [])] });
    } else {
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      if (url.pathname.endsWith("/revoke")) {
        revokes += 1;
        expect(url.pathname).toBe(`/api/credentials/google-drive/${credential.id}/revoke`);
        expect(request.postDataJSON()).toEqual({ expectedCredentialRevision: 3 });
        if (rejectRevoke) {
          await route.fulfill({ status: 409, json: { code: "SOURCE_CONFLICT" } });
          return;
        }
        shared = { ...shared, status: "REVOKED", credentialRevision: 4 };
        await route.fulfill({ status: 204 });
      } else if (url.pathname.endsWith("/authorization")) {
        reconnects += 1;
        expect(request.postDataJSON()).toEqual({
          name: credential.name,
          credentialId: credential.id,
          expectedCredentialRevision: 4,
        });
        shared = { ...shared, status: "ACTIVE", credentialRevision: 5 };
        await route.fulfill({
          json: {
            authorizationUrl: `/admin/sources/new/google-drive?googleDrive=connected&credentialId=${credential.id}`,
          },
        });
      } else if (request.method() === "DELETE") {
        deletes += 1;
        expect(url.pathname).toBe(`/api/credentials/google-drive/${unused.id}`);
        expect(request.headers()["if-match"]).toBe('"3"');
        if (rejectDelete)
          await route.fulfill({
            status: 409,
            json: { code: "SOURCE_CONFLICT", detail: "private-provider-detail" },
          });
        else {
          unusedVisible = false;
          await route.fulfill({ status: 204 });
        }
      } else await route.fulfill({ status: 405 });
    }
  });
  await page.goto("/admin/sources/new/google-drive");
  const sharedRow = page
    .getByRole("rowgroup")
    .filter({ has: page.getByRole("radio", { name: `Select ${credential.name}` }) });
  const unusedRow = page
    .getByRole("rowgroup")
    .filter({ has: page.getByRole("radio", { name: "Select Unused account" }) });
  await sharedRow.getByRole("button", { name: `Manage ${credential.name}` }).click();
  await expect(sharedRow.getByRole("button", { name: "Delete", exact: true })).toBeDisabled();
  await sharedRow.getByRole("radio").check();
  await sharedRow.getByRole("button", { name: "Revoke", exact: true }).click();
  const confirmation = page.getByRole("alertdialog");
  await expect(confirmation).toContainText("all 2 Sources");
  await expect(confirmation.getByRole("button", { name: "Cancel" })).toBeFocused();
  await confirmation.getByRole("button", { name: "Cancel" }).click();
  expect(revokes).toBe(0);
  await sharedRow.getByRole("button", { name: "Revoke", exact: true }).click();
  await confirmation.getByRole("button", { name: "Revoke", exact: true }).click();
  await expect(confirmation.getByRole("alert")).toBeVisible();
  await expect(
    page.getByRole("listitem", { name: "Credential revoked", exact: true, includeHidden: true }),
  ).toHaveCount(0);
  rejectRevoke = false;
  await confirmation.getByRole("button", { name: "Revoke", exact: true }).click();
  await expect(confirmation).toHaveCount(0);
  await expect(
    page.getByRole("listitem", { name: "Credential revoked", exact: true }),
  ).toContainText(credential.name);
  await expect(sharedRow.getByRole("radio")).toBeDisabled();
  await expect(page.getByRole("button", { name: "Continue", exact: true })).toBeDisabled();
  await sharedRow.getByRole("button", { name: "Reconnect", exact: true }).click();
  await expect(page.getByRole("dialog")).toContainText("all 2 Sources");
  await expect(page.getByRole("textbox", { name: "Upload or paste OAuth app JSON" })).toHaveCount(
    0,
  );
  await page.getByRole("button", { name: "Authenticate" }).click();
  await expect(sharedRow.getByRole("radio")).toBeChecked();
  await expect(
    page.getByRole("listitem", { name: "Credential connected", exact: true }),
  ).toContainText(credential.name);
  expect(reconnects).toBe(1);
  await unusedRow.getByRole("button", { name: "Manage Unused account" }).click();
  await unusedRow.getByRole("button", { name: "Delete", exact: true }).click();
  await confirmation.getByRole("button", { name: "Delete credential", exact: true }).click();
  await expect(confirmation.getByRole("alert")).toBeVisible();
  await expect(confirmation.getByRole("alert")).not.toContainText("private-provider-detail");
  await expect(
    page.getByRole("listitem", { name: "Credential deleted", exact: true, includeHidden: true }),
  ).toHaveCount(0);
  await confirmation.getByRole("button", { name: "Cancel" }).click();
  await expect(unusedRow).toBeVisible();
  rejectDelete = false;
  await unusedRow.getByRole("button", { name: "Delete", exact: true }).click();
  await confirmation.getByRole("button", { name: "Delete credential", exact: true }).click();
  await expect(unusedRow).toHaveCount(0);
  await expect(
    page.getByRole("listitem", { name: "Credential deleted", exact: true }),
  ).toContainText(unused.name);
  expect(deletes).toBe(2);
  expect(revokes).toBe(2);
});

test("credential load failure and revoked callback cannot create a Source; members cannot access setup", async ({
  page,
}) => {
  let fail = true;
  let member = false;
  let writes = 0;
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: member
        ? { ...owner, tenant: { ...owner.tenant, role: "MEMBER" }, capabilities: [] }
        : owner,
    }),
  );
  await page.route("**/api/credentials/google-drive**", (route) => {
    if (route.request().method() !== "GET") writes += 1;
    return route.fulfill(fail ? { status: 503 } : { json: [{ ...credential, status: "REVOKED" }] });
  });
  await page.goto(
    `/admin/sources/new/google-drive?googleDrive=connected&credentialId=${credential.id}`,
  );
  await expect(page.getByRole("button", { name: "Continue", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Create New" })).toBeDisabled();
  await expect(
    page.getByRole("listitem", { name: "Credential connected", exact: true }),
  ).toHaveCount(0);
  fail = false;
  await page.getByRole("button", { name: "Try again" }).click();
  await expect(page.getByRole("radio")).toBeDisabled();
  await expect(page.getByRole("button", { name: "Continue", exact: true })).toBeDisabled();
  await expect(
    page.getByRole("listitem", { name: "Credential connected", exact: true }),
  ).toHaveCount(0);
  await expect(page.getByText("Authorization completed.", { exact: false })).toHaveCount(0);
  await page.goto("/admin/sources/new/google-drive?googleDrive=authorization-failed");
  await expect(
    page.getByRole("listitem", { name: "Credential connection failed", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("alert")).toContainText("Google authorization was not completed");
  await expect(
    page.getByRole("listitem", { name: "Credential connected", exact: true }),
  ).toHaveCount(0);
  member = true;
  await page.reload();
  await expect(page.getByRole("button", { name: "Create New" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Authenticate" })).toHaveCount(0);
  expect(writes).toBe(0);
});
