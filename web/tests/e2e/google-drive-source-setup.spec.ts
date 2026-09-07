import { expect, test } from "@playwright/test";
import type { GetGoogleDriveConfigurationResponse } from "../../src/lib/hey-api/types.gen";

const owner = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  tenant: { displayName: "Team", role: "OWNER" },
  capabilities: ["SOURCES_MANAGE"],
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
};
const source = {
  id: "46337ebd-a134-41de-b322-196cd9be22c4",
  name: "Team knowledge",
  type: "GOOGLE_DRIVE",
  access: "RESTRICTED",
  status: "ACTIVE",
  pendingWork: false,
  documentCount: 0,
  lastSucceededAt: null,
  errorCode: null,
};
const secondSourceId = "7c6d85d0-ddd4-445c-9fd0-280f2b3e5b19";
const fileLink = "https://docs.google.com/document/d/document-a/edit";
const folderLink = "https://drive.google.com/drive/folders/folder-a";

test("one authorization supplies two independently named Sources with separate explicit roots", async ({
  page,
}, testInfo) => {
  await page.clock.install();
  let authorized = false;
  const sources: Array<typeof source> = [];
  const rootsBySource = new Map<string, Array<{ id: string; name: string; mimeType: string }>>();
  const rootRevisions = new Map<string, number>();
  const schedulesBySource = new Map<
    string,
    { syncIntervalMinutes: number; scheduleRevision: number }
  >();
  const configuration = (sourceId: string) => ({
    sourceId,
    credentialId: credential.id,
    accountEmail: credential.accountEmail,
    credentialStatus: credential.status,
    credentialRevision: credential.credentialRevision,
    oauthClientConfigured: true,
    revision: rootRevisions.get(sourceId) ?? 1,
    ...schedulesBySource.get(sourceId)!,
    scopeMode: "SPECIFIC",
    roots: rootsBySource.get(sourceId),
    lastSyncedAt: null,
    pendingWork: false,
    errorCode: null,
  });
  let authorizationRequests = 0;
  let candidateRequests = 0;
  const requestUrls: string[] = [];
  const consoleMessages: string[] = [];
  const secret = "synthetic-e2e-client-secret";
  page.on("request", (request) => requestUrls.push(request.url()));
  page.on("console", (message) => consoleMessages.push(message.text()));
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: owner }));
  await page.route("**/api/credentials/google-drive**", async (route) => {
    const request = route.request();
    if (request.method() === "GET") {
      await route.fulfill({
        json: authorized ? [{ ...credential, sourceCount: sources.length }] : [],
      });
    } else if (new URL(request.url()).pathname.endsWith("/authorization")) {
      authorizationRequests += 1;
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      const body = request.postDataJSON();
      expect(body.name).toBe(credential.name);
      expect(body).not.toHaveProperty("sourceId");
      expect(JSON.parse(body.oauthClientJson).web.client_secret).toBe(secret);
      authorized = true;
      await route.fulfill({
        json: {
          authorizationUrl: "https://accounts.google.com/o/oauth2/v2/auth?state=synthetic-state",
        },
      });
    } else await route.fulfill({ status: 405 });
  });
  await page.route("**/api/sources**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === "GET" && url.pathname.endsWith("/roots")) {
      candidateRequests += 1;
      await route.fulfill({ status: 404 });
    } else if (request.method() === "POST" && url.pathname === "/api/sources/google-drive") {
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      const body = request.postDataJSON();
      expect(body).toEqual({
        name: sources.length ? "Project archive" : source.name,
        credentialId: credential.id,
        scopeMode: "SPECIFIC",
        links: sources.length ? [folderLink] : [fileLink],
      });
      const created = {
        ...source,
        id: sources.length ? secondSourceId : source.id,
        name: body.name,
      };
      rootsBySource.set(
        created.id,
        sources.length
          ? [
              {
                id: "folder-a",
                name: "Project archive folder",
                mimeType: "application/vnd.google-apps.folder",
              },
            ]
          : [
              {
                id: "document-a",
                name: "Project notes",
                mimeType: "application/vnd.google-apps.document",
              },
            ],
      );
      schedulesBySource.set(created.id, { syncIntervalMinutes: 5, scheduleRevision: 1 });
      sources.push(created);
      await route.fulfill({ status: 201, json: { source: created, items: [] } });
    } else if (request.method() === "GET" && url.pathname.endsWith("/google-drive")) {
      const sourceId = url.pathname.split("/")[3]!;
      await route.fulfill({ json: configuration(sourceId) });
    } else if (request.method() === "PUT" && url.pathname.endsWith("/google-drive/roots")) {
      const sourceId = url.pathname.split("/")[3]!;
      const revision = rootRevisions.get(sourceId) ?? 1;
      expect(request.headers()["if-match"]).toBe(`"${revision}"`);
      expect(request.postDataJSON()).toEqual({
        scopeMode: "SPECIFIC",
        links: [`${fileLink}?usp=sharing`],
      });
      rootRevisions.set(sourceId, revision + 1);
      await route.fulfill({ json: configuration(sourceId) });
    } else if (request.method() === "PUT" && url.pathname.endsWith("/google-drive/schedule")) {
      const sourceId = url.pathname.split("/")[3]!;
      const schedule = schedulesBySource.get(sourceId)!;
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      if (request.headers()["if-match"] !== `"${schedule.scheduleRevision}"`) {
        await route.fulfill({ status: 409, json: { code: "SOURCE_GOOGLE_REVISION_CONFLICT" } });
        return;
      }
      const { syncIntervalMinutes } = request.postDataJSON();
      schedulesBySource.set(sourceId, {
        syncIntervalMinutes,
        scheduleRevision: schedule.scheduleRevision + 1,
      });
      await route.fulfill({
        json: configuration(sourceId),
        headers: { etag: `"${schedule.scheduleRevision + 1}"`, "cache-control": "no-store" },
      });
    } else if (request.method() === "GET" && url.pathname === "/api/sources") {
      await route.fulfill({ json: sources });
    } else if (request.method() === "GET") {
      const selected = sources.find((entry) => url.pathname === `/api/sources/${entry.id}`);
      await route.fulfill(selected ? { json: { source: selected, items: [] } } : { status: 404 });
    } else await route.fulfill({ status: 405 });
  });
  await page.route("https://accounts.google.com/o/oauth2/v2/auth**", (route) =>
    route.fulfill({
      status: 302,
      headers: {
        location: `${new URL(page.url()).origin}/admin/sources/new/google-drive?googleDrive=connected&credentialId=${credential.id}`,
      },
    }),
  );
  await page.goto("/admin/sources/new/google-drive");
  await expect(page.getByRole("button", { name: "Continue", exact: true })).toBeDisabled();
  await page.screenshot({
    path: testInfo.outputPath("google-credential-card-desktop.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "Create New" }).click();
  await expect(
    page.getByRole("dialog", { name: "Create a Google Drive credential" }),
  ).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("google-credential-modal-desktop.png"),
    fullPage: true,
  });
  const callback = `${new URL(page.url()).origin}/login/oauth2/code/google-drive`;
  await page.getByText("Setup instructions", { exact: true }).click();
  await expect(
    page.getByRole("textbox", {
      name: "Authorized redirect URI for this MemoryOS instance:",
    }),
  ).toHaveValue(callback);
  await page.getByText("Setup instructions", { exact: true }).click();
  await page.getByLabel("Credential name").fill(credential.name);
  await expect(page.getByLabel("Source name")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Authenticate" })).toBeDisabled();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
  await page.screenshot({
    path: testInfo.outputPath("google-oauth-setup-mobile.png"),
    fullPage: true,
  });
  await page.getByLabel("Upload OAuth client JSON").setInputFiles({
    name: "client.json",
    mimeType: "application/json",
    buffer: Buffer.from(
      JSON.stringify({
        web: {
          client_id: "synthetic.apps.googleusercontent.com",
          client_secret: secret,
          redirect_uris: [callback],
        },
      }),
    ),
  });
  await page.getByRole("button", { name: "Authenticate" }).click();
  await expect(page).toHaveURL(new RegExp(`googleDrive=connected&credentialId=${credential.id}$`));
  await expect(page.getByRole("radio", { name: `Select ${credential.name}` })).toBeChecked();
  await expect(
    page.getByRole("listitem", { name: "Credential connected", exact: true }),
  ).toContainText(credential.name);
  await page.screenshot({
    path: testInfo.outputPath("google-credential-metadata-mobile.png"),
    fullPage: true,
  });
  await expect(page.getByLabel("Source name")).toHaveCount(0);
  await page.getByRole("button", { name: "Open navigation" }).click();
  await expect(
    page.getByRole("dialog", { name: "MemoryOS navigation" }).locator('[aria-current="step"]'),
  ).toContainText("Credential");
  await page.getByRole("button", { name: "Close navigation" }).click();
  expect(sources).toEqual([]);
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await expect(page).toHaveURL(/step=connector/);
  await page.getByRole("button", { name: "Open navigation" }).click();
  await expect(
    page.getByRole("dialog", { name: "MemoryOS navigation" }).locator('[aria-current="step"]'),
  ).toContainText("Connector");
  await page.getByRole("button", { name: "Close navigation" }).click();
  await page.getByLabel("Source name").fill(source.name);
  await page.getByRole("textbox", { name: "File or folder links" }).fill(fileLink);
  await page.getByRole("button", { name: "Create Source" }).click();
  await expect(page).toHaveURL(new RegExp(`/admin/sources/${source.id}$`));
  const createdNotice = page.getByRole("listitem", { name: "Source created", exact: true });
  await expect(createdNotice).toContainText(source.name);
  await expect(
    page.getByRole("listitem", { name: "Synchronization complete", exact: true }),
  ).toHaveCount(0);
  await createdNotice.hover();
  await page.clock.runFor(5_100);
  await expect(createdNotice).toHaveCount(0);
  await expect(page.getByRole("list", { name: "Saved roots" })).toContainText("Project notes");
  await expect(page.getByRole("list", { name: "Saved roots" })).not.toContainText(
    "Project archive folder",
  );
  for (const label of ["Synchronization", "Selected content"]) {
    const trigger = page.getByRole("button", { name: `${label} help`, exact: true });
    await trigger.focus();
    await trigger.press("Enter");
    const help = page.getByRole("dialog", { name: `${label} help`, exact: true });
    await expect(help).toBeVisible();
    await page.screenshot({
      path: testInfo.outputPath(
        `google-${label.toLowerCase().replaceAll(" ", "-")}-help-mobile.png`,
      ),
      fullPage: true,
    });
    await help.press("Escape");
    await expect(help).toHaveCount(0);
    await expect(trigger).toBeFocused();
  }
  await page.getByText("Manage connection", { exact: true }).click();
  await expect(page.getByText("Close", { exact: true })).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("google-credential-disclosure-mobile.png"),
    animations: "disabled",
    fullPage: true,
  });
  await page.locator("summary").filter({ hasText: "Credentials" }).press("Enter");
  await expect(page.getByText("Manage connection", { exact: true })).toBeVisible();
  await page.getByRole("textbox", { name: "File or folder links" }).fill(`${fileLink}?usp=sharing`);
  await page.getByRole("button", { name: "Save selection", exact: true }).click();
  const selectionNotice = page.getByRole("listitem", { name: "Selection saved", exact: true });
  await expect(selectionNotice).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("google-selection-saved-mobile.png"),
    fullPage: true,
  });
  await page.mouse.move(0, 0);
  await page.clock.runFor(5_100);
  await expect(selectionNotice).toHaveCount(0);
  await expect(page.getByText("5 minutes", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Edit interval", exact: true }).click();
  const interval = page.getByRole("spinbutton", { name: "Interval in minutes" });
  await expect(interval).toBeFocused();
  await interval.fill("15");
  await expect(page.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
  await page.screenshot({
    path: testInfo.outputPath("google-automatic-interval-editor-mobile.png"),
    fullPage: true,
  });
  await interval.press("Enter");
  await expect(page.getByText("15 minutes", { exact: true })).toBeVisible();
  const intervalNotice = page.getByRole("listitem", {
    name: "Automatic interval saved",
    exact: true,
  });
  await expect(intervalNotice).toBeVisible();
  await expect(page.getByRole("button", { name: "Edit interval", exact: true })).toBeFocused();
  await page.screenshot({
    path: testInfo.outputPath("google-interval-saved-mobile.png"),
    fullPage: true,
  });
  await page.mouse.move(0, 0);
  await page.clock.runFor(5_100);
  await expect(intervalNotice).toHaveCount(0);
  await page.reload();
  await expect(page.getByText("15 minutes", { exact: true })).toBeVisible();
  await expect(page.getByRole("list", { name: "Saved roots" })).toContainText("Project notes");
  await page.screenshot({
    path: testInfo.outputPath("google-automatic-interval-persisted-mobile.png"),
    fullPage: true,
  });
  await page.goto("/admin/sources/new/google-drive");
  const row = page
    .getByRole("rowgroup")
    .filter({ has: page.getByRole("radio", { name: `Select ${credential.name}` }) });
  await row.getByRole("button", { name: `Manage ${credential.name}` }).click();
  await expect(row.getByRole("button", { name: "Delete", exact: true })).toBeDisabled();
  await row.getByRole("radio").focus();
  await row.getByRole("radio").press(" ");
  await expect(row.getByRole("radio")).toBeChecked();
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await page.getByLabel("Source name").fill("Project archive");
  await page.getByRole("textbox", { name: "File or folder links" }).fill(folderLink);
  await page.getByRole("button", { name: "Create Source" }).click();
  await expect(page).toHaveURL(new RegExp(`/admin/sources/${secondSourceId}$`));
  await expect(page.getByRole("listitem", { name: "Source created", exact: true })).toContainText(
    "Project archive",
  );
  await expect(page.getByRole("list", { name: "Saved roots" })).toContainText(
    "Project archive folder",
  );
  await expect(page.getByRole("list", { name: "Saved roots" })).not.toContainText("Project notes");
  await expect(page.getByText("5 minutes", { exact: true })).toBeVisible();
  expect(schedulesBySource.get(source.id)?.syncIntervalMinutes).toBe(15);
  await page.screenshot({
    path: testInfo.outputPath("google-reused-credential-source-mobile.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole("button", { name: "Edit interval", exact: true }).click();
  await page.getByRole("spinbutton", { name: "Interval in minutes" }).fill("30");
  await page.screenshot({
    path: testInfo.outputPath("google-automatic-interval-editor-desktop.png"),
    fullPage: true,
  });
  await page.getByRole("spinbutton", { name: "Interval in minutes" }).press("Escape");
  await expect(page.getByRole("button", { name: "Edit interval", exact: true })).toBeFocused();
  await expect(page.getByText("5 minutes", { exact: true })).toBeVisible();
  expect(sources.map((entry) => entry.id)).toEqual([source.id, secondSourceId]);
  expect(authorizationRequests).toBe(1);
  expect(candidateRequests).toBe(0);
  expect(requestUrls.join("\n")).not.toContain(secret);
  expect(consoleMessages.join("\n")).not.toContain(secret);
  expect(await page.evaluate(() => JSON.stringify([localStorage, sessionStorage]))).not.toContain(
    secret,
  );
});

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

test("General creation and scope changes preserve drafts and save only the chosen scope", async ({
  page,
}, testInfo) => {
  let created = false;
  let rejectCreation = true;
  const selections: Array<{
    scopeMode: GetGoogleDriveConfigurationResponse["scopeMode"];
    links: string[];
  }> = [];
  let configuration: GetGoogleDriveConfigurationResponse = {
    sourceId: source.id,
    credentialId: credential.id,
    accountEmail: credential.accountEmail,
    credentialStatus: credential.status,
    credentialRevision: credential.credentialRevision,
    oauthClientConfigured: true,
    revision: 1,
    syncIntervalMinutes: 1,
    scheduleRevision: 1,
    scopeMode: "GENERAL",
    roots: [],
    lastSyncedAt: null,
    pendingWork: false,
    errorCode: null,
  };
  let syncRequests = 0;
  const operation = {
    id: "28c76a92-1199-476c-991a-9fc539ae9730",
    type: "SYNC_SOURCE",
    status: "SUCCEEDED",
    createdAt: "2026-09-01T10:00:00Z",
    completedAt: "2026-09-01T10:00:01Z",
    errorCode: null,
  };
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: owner }));
  await page.route("**/api/credentials/google-drive", (route) =>
    route.fulfill({ json: [{ ...credential, sourceCount: created ? 1 : 0 }] }),
  );
  await page.route("**/api/source-operations/**", (route) => route.fulfill({ json: operation }));
  await page.route("**/api/sources**", async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (request.method() === "POST" && pathname === "/api/sources/google-drive") {
      expect(request.postDataJSON()).toEqual({
        name: source.name,
        credentialId: credential.id,
        scopeMode: "GENERAL",
        links: [],
      });
      if (rejectCreation) {
        await route.fulfill({ status: 503, json: { code: "SOURCE_UNAVAILABLE" } });
        return;
      }
      created = true;
      await route.fulfill({ status: 201, json: { source, items: [] } });
    } else if (request.method() === "PUT" && pathname.endsWith("/google-drive/roots")) {
      expect(request.headers()["if-match"]).toBe(`"${configuration.revision}"`);
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      const selection = request.postDataJSON();
      selections.push(selection);
      configuration = {
        ...configuration,
        revision: configuration.revision + 1,
        scopeMode: selection.scopeMode,
        roots:
          selection.scopeMode === "GENERAL"
            ? []
            : [
                {
                  id: "document-a",
                  name: "Project notes",
                  mimeType: "application/vnd.google-apps.document",
                },
              ],
      };
      await route.fulfill({ json: configuration });
    } else if (request.method() === "POST" && pathname.endsWith("/sync")) {
      expect(configuration.scopeMode).toBe("GENERAL");
      expect(configuration.roots).toEqual([]);
      syncRequests += 1;
      await route.fulfill({ status: 202, json: operation });
    } else if (request.method() === "GET" && pathname.endsWith("/google-drive")) {
      await route.fulfill({ json: configuration });
    } else if (request.method() === "GET" && pathname === "/api/sources") {
      await route.fulfill({ json: created ? [source] : [] });
    } else if (request.method() === "GET" && pathname === `/api/sources/${source.id}`) {
      await route.fulfill({ json: { source, items: [] } });
    } else await route.fulfill({ status: 404 });
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`/admin/sources/new/google-drive?credentialId=${credential.id}&step=connector`);
  await page.getByLabel("Source name").fill(source.name);
  const general = page.getByRole("radio", { name: "General", exact: true });
  const specific = page.getByRole("radio", { name: "Specific", exact: true });
  const links = page.getByRole("textbox", { name: "File or folder links" });
  await expect(specific).toBeChecked();
  await expect(links).toHaveValue("");
  await expect(page.getByRole("button", { name: "Create Source" })).toBeDisabled();
  await specific.focus();
  await specific.press("ArrowRight");
  await expect(general).toBeChecked();
  await expect(links).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Create Source" })).toBeEnabled();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    .toBe(true);
  await page.screenshot({
    path: testInfo.outputPath("google-general-setup-mobile.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "Create Source" }).click();
  await expect(
    page.getByRole("listitem", { name: "Source creation failed", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("listitem", { name: "Source created", exact: true })).toHaveCount(0);
  await expect(general).toBeChecked();
  await expect(page.getByLabel("Source name")).toHaveValue(source.name);
  rejectCreation = false;
  await page.getByRole("button", { name: "Create Source" }).click();
  await expect(page).toHaveURL(new RegExp(`/admin/sources/${source.id}$`));
  await expect(page.getByRole("listitem", { name: "Source created", exact: true })).toContainText(
    source.name,
  );
  await expect(general).toBeChecked();
  await expect(page.getByRole("list", { name: "Saved roots" })).toHaveCount(0);
  await page.getByRole("button", { name: "Synchronize now" }).click();
  await expect(
    page.getByRole("listitem", { name: "Synchronization complete", exact: true }),
  ).toBeVisible();
  expect(syncRequests).toBe(1);
  await specific.check();
  await expect(page.getByRole("button", { name: "Save selection" })).toBeDisabled();
  await links.fill(fileLink);
  await general.check();
  await expect(page.getByRole("button", { name: "Save selection" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
  await specific.check();
  await expect(links).toHaveValue(fileLink);
  await Promise.all([
    page.waitForResponse((response) => response.url().endsWith("/google-drive")),
    page.getByRole("button", { name: "Refresh status" }).click(),
  ]);
  await expect(specific).toBeChecked();
  await expect(links).toHaveValue(fileLink);
  await expect(page.getByRole("button", { name: "Synchronize now" })).toBeDisabled();
  expect(configuration.scopeMode).toBe("GENERAL");
  expect(selections).toEqual([]);
  await page.getByRole("button", { name: "Save selection" }).click();
  await expect(page.getByRole("list", { name: "Saved roots" })).toContainText("Project notes");
  expect(selections).toEqual([{ scopeMode: "SPECIFIC", links: [fileLink] }]);
  await page.reload();
  await expect(specific).toBeChecked();
  await expect(links).toHaveValue("https://drive.google.com/file/d/document-a/view");
  await general.check();
  await Promise.all([
    page.waitForResponse((response) => response.url().endsWith("/google-drive")),
    page.getByRole("button", { name: "Refresh status" }).click(),
  ]);
  await expect(general).toBeChecked();
  await expect(page.getByRole("list", { name: "Saved roots" })).toContainText("Project notes");
  expect(configuration.scopeMode).toBe("SPECIFIC");
  await page.getByRole("button", { name: "Save selection" }).click();
  await expect(page.getByRole("list", { name: "Saved roots" })).toHaveCount(0);
  expect(selections).toEqual([
    { scopeMode: "SPECIFIC", links: [fileLink] },
    { scopeMode: "GENERAL", links: [] },
  ]);
  await page.reload();
  await expect(general).toBeChecked();
  await expect(links).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
  await expect(page.getByText("1 minute", { exact: true })).toBeVisible();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    .toBe(true);
  await page
    .getByRole("region", { name: "Selected content", exact: true })
    .scrollIntoViewIfNeeded();
  await page.screenshot({
    path: testInfo.outputPath("google-general-saved-mobile.png"),
    fullPage: true,
  });
});
