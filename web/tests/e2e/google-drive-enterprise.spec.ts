import { expect, test, type Page } from "@playwright/test";
import type {
  CreateGoogleDriveSourceData,
  GetGoogleDriveConfigurationResponse,
  GoogleDriveSelectionDraftResponse,
  GoogleDriveSelectionItemResponse,
  GoogleDriveSelectionTreeItemResponse,
  SourceOperation,
  SourceSummary,
} from "../../src/lib/hey-api/types.gen";

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
  name: "Shared Google account",
  accountEmail: "owner@example.com",
  status: "ACTIVE",
  credentialRevision: 3,
  oauthClientConfigured: true,
  createdAt: "2026-09-01T00:00:00Z",
  updatedAt: "2026-09-01T00:00:00Z",
  sourceCount: 0,
  actions: ["reauthorize", "replace_oauth_client", "revoke", "delete"],
};
const source: SourceSummary = {
  id: "46337ebd-a134-41de-b322-196cd9be22c4",
  name: "Team knowledge",
  type: "GOOGLE_DRIVE",
  access: "RESTRICTED",
  status: "ACTIVE",
  pendingWork: false,
  documentCount: 0,
  lastSucceededAt: null,
  errorCode: null,
  actions: [
    "reindex",
    "remove_items",
    "delete",
    "manage_groups",
    "rename",
    "manage_configuration",
    "synchronize",
    "manage_schedule",
    "pause_sync",
  ],
};
const secondSourceId = "7c6d85d0-ddd4-445c-9fd0-280f2b3e5b19";
const fileLink = "https://docs.google.com/document/d/document-a/edit";
const folderLink = "https://drive.google.com/drive/folders/folder-a";
const initialItems: GoogleDriveSelectionItemResponse[] = [
  {
    id: "folder-a",
    name: "Project archive",
    mimeType: "application/vnd.google-apps.folder",
    kind: "FOLDER",
    selected: true,
    coveredByRoots: false,
    status: "AVAILABLE",
    origins: [],
  },
  {
    id: "document-a",
    name: "Project index",
    mimeType: "application/vnd.google-apps.spreadsheet",
    kind: "FILE",
    selected: true,
    coveredByRoots: false,
    status: "AVAILABLE",
    origins: [],
  },
  {
    id: "budget",
    name: "Project budget",
    mimeType: "application/vnd.google-apps.spreadsheet",
    kind: "LINKED",
    selected: false,
    coveredByRoots: false,
    status: "AVAILABLE",
    origins: [
      {
        rootId: "document-a",
        parentId: "document-a",
        parentName: "Project index",
        location: "Overview!B2",
      },
      {
        rootId: "overlapping-origin",
        parentId: "document-a",
        parentName: "Project index",
        location: "Overview!B2",
      },
      {
        rootId: "folder-a",
        parentId: "archive-index",
        parentName: "Archive index",
        location: "Projects!D4",
      },
    ],
  },
  {
    id: "retained",
    name: "Retained project notes",
    mimeType: "application/pdf",
    kind: "LINKED",
    selected: true,
    coveredByRoots: false,
    status: "UNAVAILABLE",
    origins: [],
  },
];

type SavedSource = {
  source: SourceSummary;
  configuration: GetGoogleDriveConfigurationResponse;
  draft: GoogleDriveSelectionDraftResponse;
  items: GoogleDriveSelectionItemResponse[];
};
type Proposal = {
  requestId: string;
  sourceId: string;
  name: string;
  scopeMode: "GENERAL" | "SPECIFIC";
  links: string[];
  linkedDocumentIds: string[];
  operation: SourceOperation;
};

async function enterprisePage(page: Page, existing = false) {
  const saved = new Map<string, SavedSource>();
  const proposals: Proposal[] = [];
  const requestBodies: unknown[] = [];
  const requestedUrls: string[] = [];
  let loseCreateResponse = false;
  let latest: Proposal | undefined;
  function activate(proposal: Proposal) {
    const prior = saved.get(proposal.sourceId);
    const items =
      proposal.scopeMode === "GENERAL"
        ? []
        : initialItems.map((item) => ({
            ...item,
            selected: item.kind !== "LINKED" || proposal.linkedDocumentIds.includes(item.id),
          }));
    const revision = (prior?.configuration.revision ?? 0) + 1;
    saved.set(proposal.sourceId, {
      source: { ...source, id: proposal.sourceId, name: proposal.name },
      items,
      configuration: {
        sourceId: proposal.sourceId,
        credentialId: credential.id,
        accountEmail: credential.accountEmail,
        credentialStatus: "ACTIVE",
        credentialRevision: 3,
        oauthClientConfigured: true,
        revision,
        scheduleRevision: prior?.configuration.scheduleRevision ?? 1,
        syncPaused: prior?.configuration.syncPaused ?? false,
        syncIntervalMinutes: prior?.configuration.syncIntervalMinutes ?? 5,
        scopeMode: proposal.scopeMode,
        discoveryRevision: 2,
        discoveredAt: "2026-09-08T09:00:00Z",
        discoveryErrors: [],
        lastSyncedAt: null,
        pendingWork: false,
        errorCode: null,
        counts: {
          folders: items.filter((item) => item.kind === "FOLDER").length,
          files: items.filter((item) => item.kind === "FILE").length,
          linkedDocuments: items.filter((item) => item.kind === "LINKED").length,
          approvedLinkedDocuments: proposal.linkedDocumentIds.length,
        },
        pendingSelectionOperation: null,
      },
      draft: {
        revision,
        discoveryRevision: 2,
        credentialRevision: 3,
        links: proposal.links,
        linkedDocumentIds: proposal.linkedDocumentIds,
      },
    });
  }
  if (existing)
    activate({
      requestId: "seed",
      sourceId: source.id,
      name: source.name,
      scopeMode: "SPECIFIC",
      links: [fileLink, folderLink],
      linkedDocumentIds: ["retained"],
      operation: {
        id: "seed",
        type: "VALIDATE_GOOGLE_DRIVE_SELECTION",
        status: "SUCCEEDED",
        createdAt: "2026-09-08T08:00:00Z",
        completedAt: "2026-09-08T08:01:00Z",
        errorCode: null,
      },
    });
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: owner }));
  await page.route("**/api/credentials/google-drive", (route) =>
    route.fulfill({ json: [{ ...credential, sourceCount: saved.size }] }),
  );
  await page.route("**/api/source-operations/**", (route) => {
    const operation = proposals.find((proposal) =>
      route.request().url().endsWith(proposal.operation.id),
    )?.operation;
    return operation ? route.fulfill({ json: operation }) : route.fulfill({ status: 404 });
  });
  await page.route("**/api/sources**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    requestedUrls.push(request.url());
    if (path.endsWith("/selection-policy")) {
      await route.fulfill({
        json: {
          maxExplicitRootsPerSource: 1000,
          maxRequestBytes: 2_097_152,
          maxLinkedDocuments: 500,
        },
      });
      return;
    }
    if (path.includes("/selection-requests/")) {
      const proposal = proposals.find((entry) => path.endsWith(entry.requestId));
      await route.fulfill(
        proposal
          ? { json: { sourceId: proposal.sourceId, operation: proposal.operation } }
          : { status: 404 },
      );
      return;
    }
    if (
      (request.method() === "POST" && path === "/api/sources/google-drive") ||
      (request.method() === "PUT" && path.endsWith("/roots"))
    ) {
      const body = request.postDataJSON();
      requestBodies.push(body);
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      expect(body.requestId).toMatch(/^[0-9a-f-]{36}$/i);
      let proposal = proposals.find((entry) => entry.requestId === body.requestId);
      if (!proposal) {
        const sourceId =
          request.method() === "POST"
            ? saved.size
              ? secondSourceId
              : source.id
            : path.split("/")[3]!;
        const prior = saved.get(sourceId);
        if (prior) expect(request.headers()["if-match"]).toBe(`"${prior.configuration.revision}"`);
        proposal = {
          ...body,
          sourceId,
          name: body.name ?? prior!.source.name,
          linkedDocumentIds: body.linkedDocumentIds ?? [],
          operation: {
            id: `28c76a92-1199-476c-991a-9fc539ae973${proposals.length}`,
            type: "VALIDATE_GOOGLE_DRIVE_SELECTION",
            status: "PENDING",
            createdAt: "2026-09-08T10:00:00Z",
            completedAt: null,
            errorCode: null,
          },
        };
        proposals.push(proposal!);
        if (prior) prior.configuration.pendingSelectionOperation = proposal!.operation;
      }
      latest = proposal;
      if (loseCreateResponse && request.method() === "POST") {
        loseCreateResponse = false;
        await route.abort("failed");
        return;
      }
      await route.fulfill({
        status: 202,
        json: { sourceId: proposal!.sourceId, operation: proposal!.operation },
      });
      return;
    }
    if (path === "/api/sources") {
      await route.fulfill({ json: [...saved.values()].map((entry) => entry.source) });
      return;
    }
    if (path === "/api/sources/group-options") {
      await route.fulfill({
        json: {
          items: [
            { id: "8d11ec56-34c6-44fe-9ad0-f147f37f571c", name: "Knowledge team", systemKey: null },
          ],
          page: 0,
          size: 25,
          totalItems: 1,
          totalPages: 1,
        },
      });
      return;
    }
    const entry = saved.get(path.split("/")[3]!);
    if (!entry) {
      await route.fulfill({ status: 404 });
      return;
    }
    if (path.endsWith("/groups")) {
      await route.fulfill({
        json: {
          items: [
            { id: "8d11ec56-34c6-44fe-9ad0-f147f37f571c", name: "Knowledge team", systemKey: null },
          ],
        },
      });
      return;
    }
    if (path.endsWith("/google-drive")) await route.fulfill({ json: entry.configuration });
    else if (path.endsWith("/selection-draft")) await route.fulfill({ json: entry.draft });
    else if (path.endsWith("/selection-tree")) {
      const parentId = url.searchParams.get("parentId");
      let items: GoogleDriveSelectionTreeItemResponse[];
      if (!parentId) {
        items = entry.items
          .filter((item) => item.kind !== "LINKED" || !item.origins.length)
          .map((item) => ({ ...item, expandable: item.kind !== "LINKED" }));
      } else if (parentId === "folder-a") {
        items = [
          {
            id: "archive-index",
            name: "Archive index",
            mimeType: "application/vnd.google-apps.spreadsheet",
            kind: "FILE",
            selected: true,
            coveredByRoots: true,
            status: "AVAILABLE",
            origins: [],
            expandable: true,
          },
          {
            id: "empty-file",
            name: "Notes without recorded links",
            mimeType: "application/vnd.google-apps.document",
            kind: "FILE",
            selected: true,
            coveredByRoots: true,
            status: "AVAILABLE",
            origins: [],
            expandable: true,
          },
        ];
      } else if (parentId === "budget" && entry.draft.linkedDocumentIds.includes("budget")) {
        items = [
          {
            ...entry.items.find((item) => item.id === "document-a")!,
            kind: "LINKED",
            coveredByRoots: true,
            expandable: true,
            origins: [
              {
                rootId: "document-a",
                parentId: "budget",
                parentName: "Project budget",
                location: "Summary!A1",
              },
            ],
          },
        ];
      } else {
        items = entry.items
          .filter((item) => item.origins.some((origin) => origin.parentId === parentId))
          .map((item) => ({
            ...item,
            origins: item.origins.filter((origin) => origin.parentId === parentId),
            expandable: item.selected && item.status === "AVAILABLE",
          }));
      }
      const pageSize = !parentId ? 2 : parentId === "folder-a" ? 1 : 25;
      const cursor = url.searchParams.get("cursor");
      const offset = cursor ? Number(cursor.split(":").at(-1)) : 0;
      await route.fulfill({
        json: {
          revision: entry.draft.revision,
          discoveryRevision: entry.draft.discoveryRevision,
          credentialRevision: entry.draft.credentialRevision,
          counts: entry.configuration.counts,
          items: items.slice(offset, offset + pageSize),
          nextCursor:
            items.length > offset + pageSize ? `${parentId ?? "root"}:${offset + pageSize}` : null,
        },
      });
    } else if (path.endsWith("/selection")) {
      let items = entry.items;
      const search = url.searchParams.get("search")?.toLowerCase();
      const kind = url.searchParams.get("kind");
      if (search) items = items.filter((item) => item.name.toLowerCase().includes(search));
      if (kind) items = items.filter((item) => item.kind === kind);
      const pageSize = kind === "LINKED" ? 1 : 3;
      const offset = Number(url.searchParams.get("cursor") ?? 0);
      await route.fulfill({
        json: {
          ...entry.draft,
          links: undefined,
          linkedDocumentIds: undefined,
          counts: entry.configuration.counts,
          items: items.slice(offset, offset + pageSize),
          nextCursor: items.length > offset + pageSize ? String(offset + pageSize) : null,
        },
      });
    } else if (path.endsWith("/pause")) {
      const body = request.postDataJSON();
      expect(body.expectedRevision).toBe(entry.configuration.scheduleRevision);
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      entry.configuration = {
        ...entry.configuration,
        syncPaused: body.paused,
        scheduleRevision: entry.configuration.scheduleRevision + 1,
      };
      entry.source.actions = entry.source.actions.filter(
        (action) => action !== "pause_sync" && action !== "resume_sync",
      );
      entry.source.actions.push(body.paused ? "resume_sync" : "pause_sync");
      await route.fulfill({ json: entry.configuration });
    } else if (path.endsWith("/schedule")) {
      expect(request.headers()["if-match"]).toBe(`"${entry.configuration.scheduleRevision}"`);
      entry.configuration = {
        ...entry.configuration,
        syncIntervalMinutes: request.postDataJSON().syncIntervalMinutes,
        scheduleRevision: entry.configuration.scheduleRevision + 1,
      };
      await route.fulfill({ json: entry.configuration });
    } else if (path.endsWith("/index-attempts"))
      await route.fulfill({ json: { items: [], nextCursor: null, totalItems: 0 } });
    else if (path.endsWith("/runs"))
      await route.fulfill({
        json: {
          items: [],
          nextCursor: null,
          totalItems: 0,
          current: null,
          lastCompleted: null,
          lastSuccessful: null,
        },
      });
    else if (path.endsWith("/items"))
      await route.fulfill({ json: { items: [], nextCursor: null, totalItems: 0 } });
    else await route.fulfill({ json: entry.source });
  });
  return {
    saved,
    proposals,
    requestBodies,
    requestedUrls,
    loseNextCreateResponse() {
      loseCreateResponse = true;
    },
    finish(status = "SUCCEEDED", errorCode: string | null = null) {
      if (!latest) throw new Error("No accepted selection");
      latest.operation.status = status;
      latest.operation.errorCode = errorCode;
      latest.operation.completedAt = "2026-09-08T10:01:00Z";
      if (status === "SUCCEEDED") activate(latest);
    },
  };
}

test("Drive action refresh withdraws deep editors while retaining allowed scoped operations", async ({
  page,
}) => {
  const server = await enterprisePage(page, true);
  const entry = server.saved.get(source.id)!;
  await page.goto(`/admin/sources/${source.id}`);
  await page.getByText("File and folder links", { exact: true }).click();
  await page.getByRole("button", { name: "Edit selection", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "File or folder links" })).toBeVisible();
  entry.source.actions = [
    "rename",
    "manage_groups",
    "synchronize",
    "manage_schedule",
    "pause_sync",
  ];
  await page.getByRole("button", { name: "Refresh status" }).click();
  await expect(page.getByRole("textbox", { name: "File or folder links" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Discover linked docs" })).toHaveCount(0);
  await page.getByRole("button", { name: "Pause automatic sync" }).click();
  await expect(page.getByRole("button", { name: "Resume automatic sync" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
  await page.getByRole("button", { name: "Edit interval" }).click();
  entry.source.actions = [];
  await page.getByRole("button", { name: "Refresh status" }).click();
  await expect(page.getByRole("spinbutton", { name: "Interval in minutes" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Resume automatic sync" })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Google Drive configuration" })).toBeVisible();
});

test("creation recovers an accepted request after a lost response and reload before activation", async ({
  page,
}) => {
  const server = await enterprisePage(page);
  server.loseNextCreateResponse();
  await page.goto(`/admin/sources/new/google-drive?credentialId=${credential.id}&step=connector`);
  await page.getByLabel("Source name").fill(source.name);
  await page.getByRole("textbox", { name: "File or folder links" }).fill(fileLink);
  await page.getByRole("button", { name: "Create Source", exact: true }).click();
  await expect(page.getByRole("button", { name: "Retry Create Source" })).toBeEnabled();
  await expect(page.getByLabel("Source name")).toBeDisabled();
  expect(server.saved.size).toBe(0);
  const body = server.requestBodies[0] as CreateGoogleDriveSourceData["body"];
  expect(await page.evaluate(() => JSON.stringify(sessionStorage))).toContain(body.requestId);
  expect(await page.evaluate(() => JSON.stringify(sessionStorage))).not.toContain(fileLink);
  await page.reload();
  await expect(page.getByText("Pending validation", { exact: true })).toBeVisible();
  expect(server.requestBodies).toHaveLength(1);
  expect(server.saved.size).toBe(0);
  server.finish();
  await expect(page).toHaveURL(new RegExp(`/admin/sources/${source.id}$`));
  await expect(page.getByRole("heading", { name: source.name })).toBeVisible();
  expect(server.saved.size).toBe(1);
  expect(server.requestBodies).toHaveLength(1);
});

test("one credential creates independent Specific and General sources with separate schedules", async ({
  page,
}, testInfo) => {
  const server = await enterprisePage(page);
  await page.goto(`/admin/sources/new/google-drive?credentialId=${credential.id}&step=connector`);
  await page.getByLabel("Source name").fill(source.name);
  await page.getByRole("textbox", { name: "File or folder links" }).fill(fileLink);
  await page.getByRole("button", { name: "Create Source", exact: true }).click();
  await expect(page.getByText("Pending validation", { exact: true })).toBeVisible();
  expect(server.saved.size).toBe(0);
  server.finish();
  await expect(page).toHaveURL(new RegExp(`/admin/sources/${source.id}$`));
  await page.getByRole("button", { name: "Edit interval" }).click();
  await page.getByRole("spinbutton", { name: "Interval in minutes" }).fill("15");
  await page.getByRole("button", { name: "Save interval" }).click();
  await expect(page.getByText("15 minutes", { exact: true })).toBeVisible();
  await page.goto(`/admin/sources/new/google-drive?credentialId=${credential.id}&step=connector`);
  await page.getByLabel("Source name").fill("Whole account");
  await page.getByRole("radio", { name: "General", exact: true }).check();
  await page.getByRole("button", { name: "Create Source", exact: true }).click();
  await expect(page.getByText("Pending validation", { exact: true })).toBeVisible();
  server.finish();
  await expect(page).toHaveURL(new RegExp(`/admin/sources/${secondSourceId}$`));
  await expect(page.getByRole("button", { name: "Edit selection", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Synchronize now" })).toBeEnabled();
  expect(server.saved.get(source.id)!.configuration.syncIntervalMinutes).toBe(15);
  expect(server.saved.get(secondSourceId)!.configuration.syncIntervalMinutes).toBe(5);
  expect(server.saved.get(source.id)!.draft.links).toEqual([fileLink]);
  expect(server.saved.get(secondSourceId)!.draft.links).toEqual([]);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    .toBe(true);
  await page.screenshot({ path: testInfo.outputPath("general-source-mobile.png"), fullPage: true });
});

test("selection preserves hidden approvals across search and paging and restores keyboard focus", async ({
  page,
}, testInfo) => {
  const server = await enterprisePage(page, true);
  await page.goto(`/admin/sources/${source.id}`);
  const selection = page.getByRole("region", { name: "Selected content", exact: true });
  const items = selection.getByRole("list", { name: "Selection items", exact: true });
  await expect(items.getByText("Project archive", { exact: true })).toBeVisible();
  await expect(items.getByText("Project index", { exact: true })).toBeVisible();
  await expect(items.getByText("Project budget", { exact: true })).toHaveCount(0);
  expect(server.requestedUrls.some((url) => url.includes("selection-draft"))).toBe(false);
  await expect(selection.getByRole("button", { name: "Edit selection", exact: true })).toBeHidden();
  const linkDisclosure = selection.getByText("File and folder links", { exact: true });
  await linkDisclosure.focus();
  await linkDisclosure.press("Enter");
  await expect(
    selection.getByRole("button", { name: "Edit selection", exact: true }),
  ).toBeVisible();
  await selection.getByRole("button", { name: "Load saved links", exact: true }).click();
  const savedLinks = selection.getByRole("textbox", { name: "File or folder links" });
  await expect(savedLinks).toHaveValue([fileLink, folderLink].join("\n"));
  await expect(savedLinks).not.toBeEditable();
  await expect(selection.getByRole("heading")).toHaveCount(1);
  const expand = selection.getByRole("button", { name: "Expand Project index", exact: true });
  await expand.focus();
  await expand.press("Enter");
  await expect(selection.getByText("Project budget", { exact: true })).toBeVisible();
  const edit = selection.getByRole("button", { name: "Edit selection", exact: true });
  await edit.focus();
  await edit.press("Enter");
  const links = selection.getByRole("textbox", { name: "File or folder links" });
  await expect(links).toBeFocused();
  await expect(selection.getByRole("heading")).toHaveCount(1);
  await selection.getByRole("checkbox", { name: "Sync Project budget" }).check();
  await selection.getByRole("combobox", { name: "Content type" }).selectOption("LINKED");
  await expect(selection.getByRole("list", { name: "Selection results" })).toBeVisible();
  await selection.getByRole("button", { name: "Next selection page" }).click();
  await expect(
    selection.getByRole("checkbox", { name: "Sync Retained project notes" }),
  ).toBeChecked();
  await selection.getByRole("textbox", { name: "Search selected content" }).fill("budget");
  await selection.getByRole("button", { name: "Search", exact: true }).click();
  await expect(selection.getByRole("checkbox", { name: "Sync Project budget" })).toBeChecked();
  await selection.locator('details[aria-label="References for Project budget"] > summary').click();
  const locations = selection.getByRole("list", { name: "Reference locations" });
  await expect(locations.getByText("Overview!B2", { exact: true })).toHaveCount(1);
  await expect(locations.getByText("Projects!D4", { exact: true })).toBeVisible();
  for (const viewport of [
    { label: "desktop", width: 1440, height: 1000 },
    { label: "mobile", width: 390, height: 844 },
  ]) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBe(true);
    await selection.screenshot({
      path: testInfo.outputPath(`selection-draft-${viewport.label}.png`),
    });
  }
  await selection.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(edit).toBeFocused();
  expect(server.requestBodies).toHaveLength(0);
  await edit.click();
  await selection.getByRole("checkbox", { name: "Sync Project budget" }).check();
  await selection.getByRole("button", { name: "Save selection", exact: true }).click();
  await expect(selection.getByText("Pending validation", { exact: true })).toBeVisible();
  expect(server.requestBodies[0]).toEqual({
    scopeMode: "SPECIFIC",
    links: [fileLink, folderLink],
    linkedDocumentIds: ["retained", "budget"],
    discoveryRevision: 2,
    credentialRevision: 3,
    requestId: expect.any(String),
  });
  expect(server.saved.get(source.id)!.configuration.revision).toBe(1);
  server.finish();
  await expect(edit).toBeFocused();
  await expect(links).toHaveCount(0);
  await page.reload();
  await selection.getByRole("button", { name: "Expand Project index", exact: true }).click();
  await linkDisclosure.click();
  await edit.click();
  await expect(selection.getByRole("checkbox", { name: "Sync Project budget" })).toBeChecked();
  await selection.getByRole("combobox", { name: "Content type" }).selectOption("LINKED");
  await selection.getByRole("button", { name: "Next selection page" }).click();
  await expect(
    selection.getByRole("checkbox", { name: "Sync Retained project notes" }),
  ).toBeChecked();
  await selection.getByRole("button", { name: "Clear filters", exact: true }).click();
  await expect(items.getByText("Project archive", { exact: true })).toBeVisible();
  await expect(selection.getByRole("list", { name: "Selection results" })).toHaveCount(0);
});

test("tree pages actual files and shares one unsaved sync choice across linked occurrences", async ({
  page,
}, testInfo) => {
  const server = await enterprisePage(page, true);
  await page.goto(`/admin/sources/${source.id}`);
  const selection = page.getByRole("region", { name: "Selected content", exact: true });
  await expect(selection.getByText("Project archive", { exact: true })).toBeVisible();
  expect(server.requestedUrls.some((url) => new URL(url).searchParams.has("parentId"))).toBe(false);
  await selection.getByRole("button", { name: "Load more selected content" }).click();
  await expect(selection.getByText("Retained project notes", { exact: true })).toBeVisible();
  const folder = selection.getByRole("button", { name: "Expand Project archive", exact: true });
  await folder.focus();
  await folder.press("Enter");
  await expect(selection.getByText("Archive index", { exact: true })).toBeVisible();
  await expect(selection.getByText("Notes without recorded links", { exact: true })).toHaveCount(0);
  await selection.getByRole("button", { name: "Load more in Project archive" }).click();
  await expect(selection.getByText("Archive index", { exact: true })).toBeVisible();
  await expect(selection.getByText("Notes without recorded links", { exact: true })).toBeVisible();
  await selection.getByRole("button", { name: "Expand Notes without recorded links" }).click();
  await expect(selection.getByText(/No discovered links are recorded for this file/)).toBeVisible();
  await selection.getByRole("button", { name: "Expand Archive index", exact: true }).click();
  await selection.getByRole("button", { name: "Expand Project index", exact: true }).click();
  const select = selection.getByRole("button", {
    name: "Select Project budget for sync",
    exact: true,
  });
  await expect(select).toHaveCount(2);
  await select.first().click();
  const checkboxes = selection.getByRole("checkbox", { name: "Sync Project budget", exact: true });
  await expect(checkboxes).toHaveCount(2);
  await expect(checkboxes.first()).toBeFocused();
  await expect(checkboxes.first()).toBeChecked();
  await expect(checkboxes.last()).toBeChecked();
  const linkDisclosure = selection.getByText("File and folder links", { exact: true });
  const rootLinks = selection.getByRole("textbox", { name: "File or folder links" });
  await expect(linkDisclosure.locator("..")).not.toHaveAttribute("open");
  await expect(rootLinks).toHaveCount(0);
  await expect(
    selection.getByRole("button", { name: "Save selection", exact: true }),
  ).toBeVisible();
  await selection.getByRole("button", { name: "Reload saved selection" }).click();
  await expect(checkboxes.first()).not.toBeChecked();
  await expect(linkDisclosure.locator("..")).not.toHaveAttribute("open");
  await expect(rootLinks).toHaveCount(0);
  await checkboxes.first().check();
  await checkboxes.first().uncheck();
  await expect(checkboxes.last()).not.toBeChecked();
  await checkboxes.last().check();
  await expect(checkboxes.first()).toBeChecked();
  await expect(
    selection.getByRole("checkbox", { name: "Sync Retained project notes" }),
  ).toBeChecked();
  expect(server.requestBodies).toHaveLength(0);
  for (const viewport of [
    { label: "desktop", width: 1440, height: 1000 },
    { label: "mobile", width: 390, height: 844 },
  ]) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBe(true);
    await selection.screenshot({
      path: testInfo.outputPath(`selection-tree-${viewport.label}.png`),
    });
  }
  await selection.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(select.first()).toBeFocused();
  await expect(select).toHaveCount(2);
  expect(server.requestBodies).toHaveLength(0);
  await select.last().click();
  await linkDisclosure.press("Enter");
  await expect(rootLinks).toHaveCount(0);
  const edit = selection.getByRole("button", { name: "Edit selection", exact: true });
  await edit.click();
  await expect(rootLinks).toBeFocused();
  await expect(rootLinks).toHaveValue([fileLink, folderLink].join("\n"));
  await expect(checkboxes.first()).toBeChecked();
  await expect(
    selection.getByRole("checkbox", { name: "Sync Retained project notes" }),
  ).toBeChecked();
  await selection.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(edit).toBeFocused();
  expect(server.requestBodies).toHaveLength(0);
  await linkDisclosure.press("Enter");
  await select.last().click();
  await selection.getByRole("button", { name: "Save selection", exact: true }).click();
  await expect(selection.getByText("Pending validation", { exact: true })).toBeVisible();
  expect(server.requestBodies).toEqual([
    expect.objectContaining({
      links: [fileLink, folderLink],
      linkedDocumentIds: ["retained", "budget"],
    }),
  ]);
  server.finish();
  await expect(selection.getByRole("button", { name: "Save selection", exact: true })).toHaveCount(
    0,
  );
  await expect(linkDisclosure.locator("..")).not.toHaveAttribute("open");
  await expect(rootLinks).toHaveCount(0);
  await selection.getByRole("button", { name: "Expand Project index", exact: true }).click();
  await selection.getByRole("button", { name: "Expand Project budget", exact: true }).click();
  const cycle = selection.getByRole("list", { name: "Contents of Project budget", exact: true });
  await expect(cycle.getByText("Project index", { exact: true })).toBeVisible();
  await expect(cycle.getByText(/Already shown earlier in this branch/)).toBeVisible();
  await expect(
    cycle.getByRole("button", { name: "Expand Project index", exact: true }),
  ).toHaveCount(0);
  await expect(
    cycle.getByRole("button", { name: /(?:Select|Deselect) Project index for sync/ }),
  ).toHaveCount(0);
  const deselect = selection.getByRole("button", {
    name: "Deselect Project budget for sync",
    exact: true,
  });
  await deselect.click();
  await expect(checkboxes).not.toBeChecked();
  await expect(checkboxes).toBeFocused();
  await expect(linkDisclosure.locator("..")).not.toHaveAttribute("open");
  await selection.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(deselect).toBeFocused();
  expect(server.requestBodies).toHaveLength(1);
  expect(server.saved.get(source.id)!.draft.linkedDocumentIds).toEqual(["retained", "budget"]);
  await deselect.click();
  await selection.getByRole("button", { name: "Save selection", exact: true }).click();
  await expect(selection.getByText("Pending validation", { exact: true })).toBeVisible();
  expect(server.requestBodies[1]).toEqual(
    expect.objectContaining({ links: [fileLink, folderLink], linkedDocumentIds: ["retained"] }),
  );
  server.finish();
  await expect(selection.getByRole("button", { name: "Save selection", exact: true })).toHaveCount(
    0,
  );
  await selection.getByRole("button", { name: "Expand Project index", exact: true }).click();
  await expect(select).toBeVisible();
});

test("unavailable approved documents can be deselected but not approved again", async ({
  page,
}) => {
  const server = await enterprisePage(page, true);
  await page.goto(`/admin/sources/${source.id}`);
  const selection = page.getByRole("region", { name: "Selected content", exact: true });
  await selection.getByRole("combobox", { name: "Content type" }).selectOption("LINKED");
  await selection.getByRole("button", { name: "Next selection page" }).click();
  const deselect = selection.getByRole("button", {
    name: "Deselect Retained project notes for sync",
    exact: true,
  });
  await expect(deselect).toBeEnabled();
  await deselect.click();
  const checkbox = selection.getByRole("checkbox", { name: "Sync Retained project notes" });
  await expect(checkbox).not.toBeChecked();
  await expect(checkbox).toBeDisabled();
  const cancel = selection.getByRole("button", { name: "Cancel", exact: true });
  await expect(cancel).toBeFocused();
  await cancel.click();
  await expect(deselect).toBeFocused();
  expect(server.requestBodies).toHaveLength(0);
  expect(server.saved.get(source.id)!.draft.linkedDocumentIds).toEqual(["retained"]);
  await deselect.click();
  await selection.getByRole("button", { name: "Save selection", exact: true }).click();
  await expect(selection.getByText("Pending validation", { exact: true })).toBeVisible();
  expect(server.requestBodies).toEqual([
    expect.objectContaining({ links: [fileLink, folderLink], linkedDocumentIds: [] }),
  ]);
  server.finish();
  await expect(selection.getByRole("button", { name: "Save selection", exact: true })).toHaveCount(
    0,
  );
  await selection.getByRole("button", { name: "Next selection page" }).click();
  await expect(
    selection.getByRole("button", { name: "Select Retained project notes for sync", exact: true }),
  ).toBeDisabled();
});

test("tree distinguishes failed branches from empty files and rejects stale authority pages", async ({
  page,
}) => {
  const server = await enterprisePage(page, true);
  let attempt = 0;
  await page.route("**/google-drive/selection-tree?*", async (route) => {
    if (new URL(route.request().url()).searchParams.get("parentId") !== "folder-a") {
      await route.fallback();
      return;
    }
    attempt++;
    if (attempt === 1) {
      await route.fulfill({ status: 503 });
      return;
    }
    if (attempt === 2) {
      await route.fulfill({
        json: {
          revision: 0,
          discoveryRevision: 2,
          credentialRevision: 3,
          counts: server.saved.get(source.id)!.configuration.counts,
          nextCursor: null,
          items: [
            {
              ...initialItems[1],
              name: "Stale folder content",
              expandable: true,
            },
          ],
        },
      });
      return;
    }
    await route.fallback();
  });
  await page.goto(`/admin/sources/${source.id}`);
  const selection = page.getByRole("region", { name: "Selected content", exact: true });
  await selection.getByRole("button", { name: "Expand Project archive", exact: true }).click();
  await expect(selection.getByRole("alert")).toContainText("This content could not be loaded");
  await expect(selection.getByText(/No accessible items/)).toHaveCount(0);
  await selection.getByRole("button", { name: "Retry loading content" }).click();
  await expect(selection.getByRole("alert")).toContainText("Selection or discovery changed");
  await expect(selection.getByText("Stale folder content", { exact: true })).toHaveCount(0);
  await selection.getByRole("button", { name: "Refresh selected content" }).click();
  await expect(selection.getByText("Archive index", { exact: true })).toBeVisible();
  expect(server.requestBodies).toHaveLength(0);
});
