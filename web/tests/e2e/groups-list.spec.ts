import { expect, test, type Page } from "@playwright/test";

const adminId = "00000000-0000-0000-0000-000000000001";
const basicId = "00000000-0000-0000-0000-000000000002";
const customId = "50000000-0000-0000-0000-000000000001";

async function mockGroups(page: Page, editable = true) {
  const groups = [
    {
      id: adminId,
      name: "Admin",
      systemKey: "ADMIN",
      memberCount: 1,
      managerCount: 0,
      capabilities: ["SYSTEM_ADMIN"],
      actions: [],
    },
    {
      id: basicId,
      name: "Basic",
      systemKey: "BASIC",
      memberCount: 0,
      managerCount: 0,
      capabilities: ["SYSTEM_BASIC"],
      actions: [],
    },
    {
      id: customId,
      name: "HROD",
      systemKey: null,
      memberCount: 0,
      managerCount: 0,
      capabilities: [],
      actions: ["rename", "manage_members", "manage_managers", "manage_sources"],
    },
  ];
  const searches: string[] = [];
  let renameGuard: string | undefined;
  await page.addInitScript(() => localStorage.setItem("memoryos-theme", "dark"));
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        actorId: "10000000-0000-0000-0000-000000000001",
        uiLanguage: "en",
        tenant: { displayName: "MemoryOS", role: editable ? "OWNER" : "MEMBER" },
        capabilities: editable
          ? [
              "SYSTEM_ADMIN",
              "SYSTEM_BASIC",
              "SEARCH_READ",
              "CHAT_READ",
              "CHAT_WRITE",
              "IMAGE_GENERATE",
              "LLM_GATEWAY_USE",
              "GROUPS_READ",
              "GROUPS_MANAGE",
              "USERS_MANAGE",
              "SOURCES_READ",
              "SOURCES_MANAGE",
              "SOURCES_DELETE",
              "MODELS_MANAGE",
            ]
          : [
              "SYSTEM_BASIC",
              "SEARCH_READ",
              "CHAT_READ",
              "CHAT_WRITE",
              "IMAGE_GENERATE",
              "LLM_GATEWAY_USE",
            ],
        scopedCapabilities: editable
          ? []
          : ["GROUPS_READ", "GROUPS_MANAGE", "SOURCES_READ", "SOURCES_MANAGE"],
        authorizationVersion: 20,
      },
    }),
  );
  await page.route("**/api/groups**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/groups") {
      const search = url.searchParams.get("search") ?? "";
      searches.push(search);
      const items = groups.filter(
        (group) =>
          (editable || group.systemKey === null) &&
          group.name.toLowerCase().includes(search.toLowerCase()),
      );
      await route.fulfill({
        json: {
          items,
          page: 0,
          size: 20,
          totalItems: items.length,
          totalPages: items.length ? 1 : 0,
        },
      });
      return;
    }
    if (url.pathname === `/api/groups/${customId}/rename`) {
      renameGuard = route.request().headers()["x-memoryos-csrf"];
      groups[2]!.name = route.request().postDataJSON().name;
      await route.fulfill({ json: groups[2] });
      return;
    }
    const group = groups.find((item) => url.pathname === `/api/groups/${item.id}`);
    if (group) {
      await route.fulfill({ json: group });
      return;
    }
    await route.fulfill({ json: { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 } });
  });
  return {
    searches,
    get renameGuard() {
      return renameGuard;
    },
  };
}

test("Groups list separates defaults, searches server-side and opens group details", async ({
  page,
}) => {
  const fixture = await mockGroups(page);
  await page.goto("/admin/groups");
  await expect(page.getByRole("heading", { name: "Groups", exact: true })).toBeVisible();
  await expect(page.getByText("Permissions have changed")).toBeVisible();
  await expect(page.getByRole("link", { name: "Learn more" })).toHaveAttribute(
    "href",
    /docs\/specs\/identity\.md/,
  );
  await expect(page.getByText("Default", { exact: true })).toHaveCount(2);
  await expect(page.getByRole("article").getByRole("heading")).toHaveText([
    "Admin",
    "Basic",
    "HROD",
  ]);
  await expect(page.getByText("1 Member", { exact: true })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Group pages" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "New Group", exact: true })).toHaveAttribute(
    "href",
    "/admin/groups/new",
  );
  await page.getByRole("searchbox", { name: "Search groups", exact: true }).fill("HRO");
  await expect(page).toHaveURL(/search=HRO/);
  await expect(page.getByRole("article").getByRole("heading")).toHaveText(["HROD"]);
  expect(fixture.searches).toContain("HRO");
  await page.getByRole("link", { name: "Open HROD" }).click();
  await expect(page).toHaveURL(new RegExp(`/admin/groups/${customId}`));
  await expect(page.getByRole("textbox")).toHaveValue("HROD");
});

test("Groups list preserves guarded rename and hides global controls for scoped managers", async ({
  page,
}) => {
  const fixture = await mockGroups(page);
  await page.goto("/admin/groups");
  await page.getByRole("heading", { name: "HROD", exact: true }).hover();
  await page.getByRole("button", { name: "Rename HROD" }).click();
  await page.getByRole("textbox", { name: "Name for HROD" }).fill("People");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("heading", { name: "People", exact: true })).toBeVisible();
  expect(fixture.renameGuard).toBe("1");
  await page.unrouteAll({ behavior: "wait" });
  await mockGroups(page, false);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/admin/groups");
  await expect(page.getByRole("heading", { name: "Groups", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "New Group", exact: true })).toHaveCount(0);
  await page.getByRole("heading", { name: "HROD", exact: true }).hover();
  await page.getByRole("button", { name: "Rename HROD" }).click();
  await page.getByRole("textbox", { name: "Name for HROD" }).fill("Managed team");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Managed team", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Open Managed team" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
});

test("Add mode replaces member browsing instead of stacking searches and pagination", async ({
  page,
}) => {
  await mockGroups(page);
  await page.goto(`/admin/groups/${customId}`);
  await expect(page.getByRole("navigation", { name: "Member pages" })).toBeVisible();
  await page.getByRole("searchbox", { name: "Search group members" }).fill("existing filter");
  await page.getByRole("button", { name: "Add", exact: true }).click();
  await expect(page.getByRole("searchbox")).toHaveCount(1);
  await expect(page.getByRole("searchbox", { name: "Search member candidates" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Member pages" })).toHaveCount(0);
  await expect(page.getByRole("navigation", { name: "Candidate pages" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Add selected", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "Done", exact: true }).click();
  await expect(page.getByRole("searchbox")).toHaveCount(1);
  await expect(page.getByRole("searchbox", { name: "Search group members" })).toHaveValue(
    "existing filter",
  );
  await expect(page.getByRole("navigation", { name: "Member pages" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Candidate pages" })).toHaveCount(0);
});

test("scoped group managers can delegate peers but cannot remove their own scope or a member's last group", async ({
  page,
}) => {
  await mockGroups(page, false);
  const self = {
    actorId: "10000000-0000-0000-0000-000000000001",
    email: "manager@example.com",
    accountType: "STANDARD",
    status: "ACTIVE",
    isManager: true,
    protectedOwner: false,
  };
  const peer = {
    ...self,
    actorId: "10000000-0000-0000-0000-000000000002",
    email: "peer@example.com",
    isManager: false,
  };
  await page.route(`**/api/groups/${customId}/members**`, async (route) => {
    if (new URL(route.request().url()).pathname.endsWith("/remove")) {
      await route.fulfill({ status: 409, json: { status: 409, code: "IAM_LAST_GROUP_PROTECTED" } });
    } else {
      await route.fulfill({
        json: { items: [self, peer], page: 0, size: 10, totalItems: 2, totalPages: 1 },
      });
    }
  });
  await page.route(`**/api/groups/${customId}/members/*/*-manager`, async (route) => {
    expect(route.request().headers()["x-memoryos-csrf"]).toBe("1");
    peer.isManager = new URL(route.request().url()).pathname.endsWith("/assign-manager");
    await route.fulfill({ status: 204 });
  });
  await page.goto(`/admin/groups/${customId}`);
  await expect(
    page.getByRole("button", { name: "Remove manager for manager@example.com" }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Remove manager@example.com from HROD" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Make manager for peer@example.com" }).click();
  await page
    .getByRole("alertdialog")
    .getByRole("button", { name: "Make manager", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Remove manager for peer@example.com" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Remove peer@example.com from HROD" }).click();
  await page
    .getByRole("alertdialog")
    .getByRole("button", { name: "Remove member", exact: true })
    .click();
  await expect(page.getByRole("alertdialog")).toContainText("Add them to another group first");
  await expect(page.getByText("peer@example.com", { exact: true })).toBeVisible();
});
