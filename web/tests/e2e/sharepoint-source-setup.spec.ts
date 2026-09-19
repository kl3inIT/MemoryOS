import { expect, test } from "@playwright/test";

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
  name: "Contoso SharePoint app",
  directoryId: "d1f4a9b0-0000-4000-8000-000000000001",
  clientId: "d1f4a9b0-0000-4000-8000-000000000002",
  cloud: "GLOBAL",
  authMethod: "CLIENT_SECRET",
  status: "ACTIVE",
  tenantHost: "contoso.sharepoint.com",
  credentialRevision: 2,
  createdAt: "2026-09-01T10:00:00Z",
  updatedAt: "2026-09-02T14:30:00Z",
  sourceCount: 0,
  actions: ["test", "rename", "replace_authentication", "delete"],
};

test.beforeEach(async ({ page }) => {
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: owner }));
  await page.route("**/api/sources/group-options**", (route) =>
    route.fulfill({ json: { items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 } }),
  );
  await page.route("**/api/credentials/sharepoint", (route) =>
    route.fulfill({ json: [credential] }),
  );
  await page.route("**/api/sources/sharepoint/selection-policy", (route) =>
    route.fulfill({
      json: { maxRootsPerSource: 100, maxExclusionsPerKind: 100, maxRequestBytes: 2_097_152 },
    }),
  );
});

test("four-step setup proposes SharePoint creation and shows the pending receipt", async ({
  page,
}) => {
  const operation = {
    id: "op-sharepoint-1",
    type: "VALIDATE_SHAREPOINT_SELECTION",
    status: "PENDING",
    createdAt: "2026-09-16T10:00:00Z",
    completedAt: null,
    errorCode: null,
  };
  await page.route("**/api/sources/sharepoint", (route) => {
    if (route.request().method() === "POST")
      return route.fulfill({
        status: 202,
        json: { sourceId: "new-source-id", operation },
      });
    return route.fallback();
  });
  await page.route("**/api/source-operations/**", (route) => route.fulfill({ json: operation }));

  await page.goto("/admin/sources/new/sharepoint");
  await page.getByRole("radio", { name: `Select ${credential.name}` }).check();
  await page.getByRole("button", { name: "Continue" }).click();

  await page
    .getByRole("textbox", { name: "Site, library or folder addresses" })
    .fill("https://contoso.sharepoint.com/sites/Finance");
  await page.getByRole("button", { name: "Continue" }).click();

  await page.getByLabel("Source name").fill("Finance SharePoint");
  await page.getByRole("button", { name: "Continue" }).click();

  await expect(page.getByText("Finance SharePoint")).toBeVisible();
  await expect(page.getByText("1 addresses")).toBeVisible();
  const submitted = page.waitForRequest(
    (request) =>
      request.method() === "POST" && new URL(request.url()).pathname === "/api/sources/sharepoint",
  );
  await page.getByRole("button", { name: "Create Source", exact: true }).click();
  const body = (await submitted).postDataJSON();
  expect(body.name).toBe("Finance SharePoint");
  expect(body.credentialId).toBe(credential.id);
  expect(body.scope.siteUrls).toEqual(["https://contoso.sharepoint.com/sites/Finance"]);
  expect(body.requestId).toBeTruthy();
  await expect(page.getByText(/resolving every address/)).toBeVisible();
});

test("invalid addresses are reported per line before submission", async ({ page }) => {
  await page.goto("/admin/sources/new/sharepoint");
  await page.getByRole("radio", { name: `Select ${credential.name}` }).check();
  await page.getByRole("button", { name: "Continue" }).click();
  const addresses = page.getByRole("textbox", { name: "Site, library or folder addresses" });
  await addresses.fill(
    "https://contoso.sharepoint.com/sites/Finance\nhttps://contoso.sharepoint.com/sites/Finance",
  );
  await expect(page.getByText(/already listed above/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Continue" })).toBeDisabled();
});
