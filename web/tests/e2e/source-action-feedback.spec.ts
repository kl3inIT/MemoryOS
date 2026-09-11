import { expect, test, type Page } from "@playwright/test";
import type { SourceIndexAttempt, SourceOperation } from "../../src/lib/hey-api/types.gen";

async function sourcePage(
  page: Page,
  provider: "FILE" | "GOOGLE_DRIVE" = "FILE",
  filenames = ["First.txt", "Second.txt"],
) {
  const source = {
    id: "46337ebd-a134-41de-b322-196cd9be22c4",
    name: "Action feedback",
    type: provider,
    access: "RESTRICTED",
    status: "ACTIVE",
    pendingWork: false,
    documentCount: 2,
    lastSucceededAt: "2026-09-01T10:00:00Z",
    errorCode: null,
    actions: [
      ...(provider === "FILE" ? ["upload"] : []),
      "reindex",
      "remove_items",
      "delete",
      "manage_groups",
    ],
  };
  const otherSource = {
    ...source,
    id: "96337ebd-a134-41de-b322-196cd9be22c4",
    name: "Other source",
  };
  const items = filenames.map((filename, index) => ({
    id: `15f8cb72-2628-4d75-bcf1-${index.toString().padStart(12, "0")}`,
    filename,
    sizeBytes: 42,
    status: "INDEXED",
    searchStatus: "READY",
    uploadedAt: "2026-09-01T10:00:00Z",
    lastIndexedAt: null,
    latestAttempt: null,
    errorCode: null,
  }));
  const operations: SourceOperation[] = [];
  const removals = new Map<string, string>();
  const operationReads: string[] = [];
  const itemPageReads: Array<string | null> = [];
  let operationGate: Promise<void> | undefined;
  const server = {
    rejectRequest: false,
    unavailableStatus: false,
    indexing: false,
    items,
    itemPageReads,
    failItemPage: false,
    operations,
    operationReads,
    holdNextOperation() {
      let release!: () => void;
      operationGate = new Promise<void>((resolve) => {
        release = resolve;
      });
      return release;
    },
  };
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
        authorizationVersion: 1,
        tenant: { displayName: "Team", role: "OWNER" },
        capabilities: ["SOURCES_READ", "SOURCES_MANAGE"],
        scopedCapabilities: [],
      },
    }),
  );
  await page.route("**/api/credentials/google-drive", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/sources**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const pathname = url.pathname;
    if (request.method() === "GET" && pathname === "/api/sources/group-options") {
      await route.fulfill({
        json: {
          items: [
            { id: "6d11ec56-34c6-44fe-9ad0-f147f37f571c", name: "Admin", systemKey: "ADMIN" },
          ],
          page: 0,
          size: 25,
          totalItems: 1,
          totalPages: 1,
        },
      });
      return;
    }
    if (request.method() === "GET" && pathname.endsWith("/groups")) {
      await route.fulfill({
        json: {
          items: [
            { id: "6d11ec56-34c6-44fe-9ad0-f147f37f571c", name: "Admin", systemKey: "ADMIN" },
          ],
        },
      });
      return;
    }
    if (
      request.method() === "POST" &&
      ["/index-attempts", "/sync", "/remove", "/delete"].some((ending) => pathname.endsWith(ending))
    ) {
      if (server.rejectRequest) {
        await route.fulfill({ status: 409, json: { code: "SOURCE_CONFLICT" } });
        return;
      }
      const operation: SourceOperation = {
        id: `28c76a92-1199-476c-991a-9fc539ae973${operations.length}`,
        type: pathname.endsWith("/sync")
          ? "SYNC_SOURCE"
          : pathname.endsWith("/remove")
            ? "REMOVE_ITEM"
            : pathname.endsWith("/delete")
              ? "DELETE_SOURCE"
              : "INDEX_ITEM",
        status: "PENDING",
        createdAt: "2026-09-01T10:00:00Z",
        completedAt: null,
        errorCode: null,
      };
      operations.push(operation);
      if (pathname.endsWith("/remove")) removals.set(operation.id, pathname.split("/").at(-2)!);
      const gate = operationGate;
      operationGate = undefined;
      await gate;
      await route.fulfill({ status: 202, json: operation });
    } else if (pathname === "/api/sources") {
      const deleted = operations.some(
        (operation) => operation.type === "DELETE_SOURCE" && operation.status === "SUCCEEDED",
      );
      await route.fulfill({ json: deleted ? [otherSource] : [source, otherSource] });
    } else if (pathname.endsWith("/selection-policy")) {
      await route.fulfill({
        json: {
          maxExplicitRootsPerSource: 1000,
          maxRequestBytes: 2_097_152,
          maxLinkedDocuments: 500,
        },
      });
    } else if (
      pathname.endsWith("/google-drive/selection") ||
      pathname.endsWith("/google-drive/selection-tree")
    ) {
      await route.fulfill({
        json: {
          revision: 1,
          discoveryRevision: 0,
          credentialRevision: 1,
          items: url.searchParams.has("parentId")
            ? []
            : [
                {
                  id: items[0]!.id,
                  name: items[0]!.filename,
                  mimeType: "text/plain",
                  kind: "FILE",
                  selected: true,
                  coveredByRoots: false,
                  status: "AVAILABLE",
                  origins: [],
                  ...(pathname.endsWith("/selection-tree") ? { expandable: true } : {}),
                },
              ],
          nextCursor: null,
          counts: { folders: 0, files: 1, linkedDocuments: 0, approvedLinkedDocuments: 0 },
        },
      });
    } else if (pathname.endsWith("/runs")) {
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
    } else if (pathname.endsWith("/google-drive")) {
      await route.fulfill({
        json: {
          sourceId: source.id,
          credentialId: "81c51573-31a9-4e67-91c5-f276960c94af",
          credentialStatus: "ACTIVE",
          credentialRevision: 1,
          accountEmail: "owner@example.com",
          oauthClientConfigured: true,
          revision: 1,
          scheduleRevision: 1,
          syncIntervalMinutes: 5,
          scopeMode: "SPECIFIC",
          counts: { folders: 0, files: 1, linkedDocuments: 0, approvedLinkedDocuments: 0 },
          pendingSelectionOperation: null,
          discoveryRevision: 0,
          discoveredAt: null,
          discoveryErrors: [],
          lastSyncedAt: null,
          pendingWork: server.indexing,
          errorCode: null,
        },
      });
    } else if (pathname.endsWith("/index-attempts")) {
      await route.fulfill({ json: { items: [], nextCursor: null, totalItems: 0 } });
    } else if (pathname.endsWith("/items")) {
      const cursor = url.searchParams.get("cursor");
      itemPageReads.push(cursor);
      const size = Number(url.searchParams.get("size") ?? 25);
      if (server.failItemPage) {
        await route.fulfill({ status: 503 });
        return;
      }
      const candidates = cursor
        ? items.slice(items.findIndex((item) => item.id === cursor) + 1)
        : items;
      const isVisible = (item: (typeof items)[number]) =>
        !operations.some(
          (operation) => removals.get(operation.id) === item.id && operation.status === "SUCCEEDED",
        );
      const visibleItems = candidates.filter(isVisible);
      const pageItems = pathname.includes(otherSource.id) ? [] : visibleItems.slice(0, size);
      await route.fulfill({
        json: {
          items: pageItems,
          nextCursor: pageItems.length && visibleItems.length > size ? pageItems.at(-1)!.id : null,
          totalItems: pathname.includes(otherSource.id) ? 0 : items.filter(isVisible).length,
        },
      });
    } else {
      const selectedSource = pathname.endsWith(otherSource.id) ? otherSource : source;
      await route.fulfill({ json: { ...selectedSource, pendingWork: server.indexing } });
    }
  });
  await page.route("**/api/source-operations/**", (route) => {
    const id = new URL(route.request().url()).pathname.split("/").at(-1);
    const operation = operations.find((entry) => entry.id === id);
    operationReads.push(id!);
    return server.unavailableStatus
      ? route.fulfill({ status: 503 })
      : route.fulfill({ json: operation });
  });
  await page.goto(`/admin/sources/${source.id}`);
  await expect(page.getByRole("heading", { name: source.name })).toBeVisible();
  return server;
}

test("Files paging preserves concurrent item operations and uploads return to the newest page", async ({
  page,
}) => {
  const server = await sourcePage(
    page,
    "FILE",
    Array.from({ length: 51 }, (_, index) => `File-${index + 1}.txt`),
  );
  const files = page.getByRole("region", { name: "Files", exact: true });
  const row = (filename: string) =>
    files.getByRole("row").filter({ has: page.getByText(filename, { exact: true }) });
  await expect(files.getByRole("row")).toHaveCount(26);
  await expect(files.getByRole("status")).toHaveText("1 / 3");
  await expect(files.getByRole("button", { name: "Previous files" })).toBeDisabled();
  const release = server.holdNextOperation();
  await row("File-1.txt").getByRole("button", { name: "Reindex" }).click();
  await expect.poll(() => server.operations.length).toBe(1);
  await files.getByRole("button", { name: "Next files" }).focus();
  await page.keyboard.press("Enter");
  await expect(files.getByRole("heading", { name: "Files", exact: true })).toBeFocused();
  await expect(row("File-26.txt")).toBeVisible();
  await expect(row("File-1.txt")).toHaveCount(0);
  await expect(files.getByRole("row")).toHaveCount(26);
  const laterCursor = server.itemPageReads.at(-1);
  await row("File-26.txt").getByRole("button", { name: "Reindex" }).click();
  await expect.poll(() => server.operations.length).toBe(2);
  release();
  await expect(page.getByRole("listitem", { name: "Reindex requested", exact: true })).toHaveCount(
    2,
  );
  await files.getByRole("button", { name: "Previous files" }).click();
  await expect(row("File-1.txt").getByRole("button", { name: "Reindex" })).toBeDisabled();
  await files.getByRole("button", { name: "Next files" }).click();
  await expect(row("File-26.txt").getByRole("button", { name: "Reindex" })).toBeDisabled();
  server.itemPageReads.length = 0;
  server.operations[0]!.status = "SUCCEEDED";
  await expect(page.getByRole("listitem", { name: "Reindex complete", exact: true })).toContainText(
    "File-1.txt",
  );
  await expect.poll(() => server.itemPageReads.length).toBeGreaterThan(0);
  expect(server.itemPageReads.every((cursor) => cursor === laterCursor)).toBe(true);
  await expect(files.getByRole("status")).toHaveText("2 / 3");

  const uploaded = {
    ...server.items[0]!,
    id: "ac15afe3-88b3-4627-a737-51d8c4c1b290",
    filename: "Newest.pdf",
    sizeBytes: 100 * 1024 * 1024,
    status: "PENDING",
    searchStatus: "WAITING",
  };
  await page.route("**/api/sources/*/uploads", (route) =>
    route.fulfill({
      status: 201,
      json: {
        uploadId: uploaded.id,
        method: "PUT",
        uploadUrl: new URL("/paging-upload", page.url()).href,
        requiredHeaders: {},
        expiresAt: "2026-10-01T00:00:00Z",
      },
    }),
  );
  await page.route("**/paging-upload", (route) => route.fulfill({ status: 204 }));
  await page.route("**/api/sources/*/uploads/*/finalize", (route) => {
    server.items.unshift(uploaded);
    server.indexing = true;
    return route.fulfill({
      status: 202,
      json: {
        item: uploaded,
        operation: { ...server.operations[0], id: "upload-operation", status: "PENDING" },
      },
    });
  });
  const input = page.locator('input[type="file"]');
  const upload = page.getByRole("button", { name: "Upload file", exact: true });
  await input.setInputFiles({
    name: "empty.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.alloc(0),
  });
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(upload).toBeDisabled();
  await input.evaluate((element: HTMLInputElement) => {
    const data = new DataTransfer();
    data.items.add(
      new File([new Uint8Array(100 * 1024 * 1024 + 1)], "oversized.pdf", {
        type: "application/pdf",
      }),
    );
    element.files = data.files;
    element.dispatchEvent(new Event("change", { bubbles: true }));
  });
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(upload).toBeDisabled();
  await input.evaluate(
    (element: HTMLInputElement, { filename, sizeBytes }) => {
      const data = new DataTransfer();
      data.items.add(
        new File([new Uint8Array(sizeBytes).fill(65)], filename, { type: "application/pdf" }),
      );
      element.files = data.files;
      element.dispatchEvent(new Event("change", { bubbles: true }));
    },
    { filename: uploaded.filename, sizeBytes: uploaded.sizeBytes },
  );
  await expect(page.getByRole("alert")).toHaveCount(0);
  await upload.click();
  await expect(row(uploaded.filename)).toBeVisible();
  await expect(files.getByRole("status")).toHaveText("1 / 3");
  await expect(files.getByRole("button", { name: "Previous files" })).toBeDisabled();
  await expect(files.getByRole("row")).toHaveCount(26);
  await expect(page.getByRole("listitem", { name: "Upload accepted", exact: true })).toContainText(
    uploaded.filename,
  );
  server.operations[1]!.status = "FAILED";
  server.operations[1]!.errorCode = "SOURCE_INDEX_FAILED";
  await expect(page.getByRole("listitem", { name: "Reindex failed", exact: true })).toContainText(
    "File-26.txt",
  );
  await files.getByRole("button", { name: "Next files" }).click();
  await expect(row("File-26.txt").getByRole("button", { name: "Reindex" })).toBeEnabled();
  await expect(row(uploaded.filename)).toHaveCount(0);
  await files.getByRole("combobox", { name: "Files per page" }).selectOption("10");
  await expect(files.getByRole("status")).toHaveText("1 / 6");
  await expect(row(uploaded.filename)).toBeVisible();
  await expect(row("File-10.txt")).toHaveCount(0);
  await files.getByRole("button", { name: "Next files" }).click();
  await expect(files.getByRole("status")).toHaveText("2 / 6");
  await expect(row("File-10.txt")).toBeVisible();
  await expect(row(uploaded.filename)).toHaveCount(0);
  await files.getByRole("combobox", { name: "Files per page" }).selectOption("50");
  await expect(files.getByRole("status")).toHaveText("1 / 2");
  await expect(row(uploaded.filename)).toBeVisible();
  await expect(row("File-26.txt")).toBeVisible();
  await expect(files.getByRole("button", { name: "Next files" })).toBeEnabled();
  await files.getByRole("button", { name: "Next files" }).click();
  await expect(files.getByRole("status")).toHaveText("2 / 2");
  await expect(row("File-50.txt")).toBeVisible();
  await expect(row("File-51.txt")).toBeVisible();
  await expect(row(uploaded.filename)).toHaveCount(0);
  await expect(files.getByRole("button", { name: "Next files" })).toBeDisabled();
});

test("a failed later Files page recovers and shrinking totals return to a valid page", async ({
  page,
}) => {
  const server = await sourcePage(
    page,
    "FILE",
    Array.from({ length: 26 }, (_, index) => `File-${index + 1}.txt`),
  );
  const files = page.getByRole("region", { name: "Files", exact: true });
  await expect(files.getByRole("row")).toHaveCount(26);
  server.failItemPage = true;
  await files.getByRole("button", { name: "Next files" }).click();
  await expect(files.getByRole("alert")).toBeVisible();
  await expect(files.getByRole("heading", { name: "No files yet" })).toHaveCount(0);
  await expect(files.getByRole("button", { name: "Previous files" })).toBeEnabled();
  server.failItemPage = false;
  await files.getByRole("button", { name: "Refresh files" }).click();
  const lastRow = files.getByRole("row").filter({ hasText: "File-26.txt" });
  await expect(lastRow).toBeVisible();
  await expect(files.getByRole("button", { name: "Next files" })).toBeDisabled();
  server.items.pop();
  await files.getByRole("button", { name: "Refresh files" }).click();
  await expect(files.getByRole("status")).toHaveText("1 / 1");
  await expect(files.getByRole("button", { name: "Previous files" })).toBeDisabled();
  await expect(files.getByRole("row").filter({ hasText: "File-1.txt" })).toBeVisible();
  await expect(files.getByRole("button", { name: "Next files" })).toBeDisabled();
});

test("refreshed FILE history reloads the first page after retained attempts shrink", async ({
  page,
}) => {
  await sourcePage(page);
  let attempts: SourceIndexAttempt[] = Array.from({ length: 6 }, (_, index) => ({
    id: `25f8cb72-2628-4d75-bcf1-${index.toString().padStart(12, "0")}`,
    filename: `Attempt-${index + 1}.txt`,
    status: "SUCCEEDED",
    createdAt: "2026-09-09T09:00:00Z",
    startedAt: "2026-09-09T09:01:00Z",
    completedAt: "2026-09-09T09:01:30Z",
    errorCode: null,
  }));
  await page.route("**/api/sources/*/index-attempts**", (route) => {
    const url = new URL(route.request().url());
    const offset = Number(url.searchParams.get("cursor") ?? 0);
    const size = Number(url.searchParams.get("size") ?? 5);
    return route.fulfill({
      json: {
        items: attempts.slice(offset, offset + size),
        nextCursor: offset + size < attempts.length ? String(offset + size) : null,
        totalItems: attempts.length,
      },
    });
  });
  await page.locator("summary").filter({ hasText: "File indexing attempts" }).click();
  const history = page.getByRole("region", { name: "File indexing attempts", exact: true });
  await expect(history.getByRole("status")).toHaveText("1 / 2");
  await history.getByRole("button", { name: "Next indexing attempts" }).click();
  await expect(history.getByRole("cell", { name: "Attempt-6.txt", exact: true })).toBeVisible();
  attempts = attempts.slice(0, 1);
  await history.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(history.getByRole("status")).toHaveText("1 / 1");
  await expect(history.getByRole("row")).toHaveCount(2);
  await expect(history.getByRole("cell", { name: "Attempt-1.txt", exact: true })).toBeVisible();
  await expect(history.getByRole("button", { name: "Next indexing attempts" })).toBeDisabled();
});

test("reindex reports each requested operation rather than aggregate source state", async ({
  page,
}, testInfo) => {
  const server = await sourcePage(page);
  const first = page
    .getByRole("row")
    .filter({ hasText: "First.txt" })
    .getByRole("button", { name: "Reindex" });
  const second = page
    .getByRole("row")
    .filter({ hasText: "Second.txt" })
    .getByRole("button", { name: "Reindex" });
  await first.click();
  const requested = page.getByRole("listitem", { name: "Reindex requested", exact: true });
  await expect(requested).toContainText("First.txt");
  await expect(first).toBeDisabled();
  await expect(page.getByRole("listitem", { name: "Reindex complete", exact: true })).toHaveCount(
    0,
  );
  await second.click();
  await expect(requested).toHaveCount(2);
  server.operations[0]!.status = "FAILED";
  server.operations[0]!.errorCode = "SOURCE_INDEX_FAILED";
  server.operations[1]!.status = "SUPERSEDED";
  const failed = page.getByRole("listitem", { name: "Reindex failed", exact: true });
  await expect(failed).toContainText("First.txt");
  await expect(
    page.getByRole("listitem", { name: "Reindex superseded", exact: true }),
  ).toContainText("Second.txt");
  const superseded = page.getByRole("listitem", { name: "Reindex superseded", exact: true });
  await superseded.getByRole("button", { name: "Dismiss Reindex superseded" }).focus();
  await page.keyboard.press("Enter");
  await expect(superseded).toHaveCount(0);
  await expect(page.getByRole("listitem", { name: "Reindex complete", exact: true })).toHaveCount(
    0,
  );
  await expect(first).toBeEnabled();
  await first.click();
  await expect.poll(() => server.operations.length).toBe(3);
  server.operations[2]!.status = "SUCCEEDED";
  const completed = page.getByRole("listitem", { name: "Reindex complete", exact: true });
  await expect(completed).toContainText("First.txt");
  await page.screenshot({
    path: testInfo.outputPath("reindex-terminal-feedback.png"),
    fullPage: true,
  });
  await completed.hover();
  await completed.getByRole("button", { name: "Dismiss Reindex complete" }).focus();
  await page.evaluate(() => window.dispatchEvent(new Event("blur")));
  await expect(failed).toHaveCount(0, { timeout: 8_000 });
  await expect(completed).toHaveCount(0, { timeout: 8_000 });
  await expect(requested).toHaveCount(0);
});

test("reindex distinguishes rejected requests from unavailable processing status", async ({
  page,
}) => {
  const server = await sourcePage(page);
  const reindex = page
    .getByRole("row")
    .filter({ hasText: "First.txt" })
    .getByRole("button", { name: "Reindex" });
  server.rejectRequest = true;
  await reindex.click();
  await expect(
    page.getByRole("listitem", { name: "Reindex could not start", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("listitem", { name: "Reindex requested", exact: true })).toHaveCount(
    0,
  );
  server.rejectRequest = false;
  server.unavailableStatus = true;
  await reindex.click();
  await expect(
    page.getByRole("listitem", { name: "Reindex status unavailable", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("listitem", { name: "Reindex failed", exact: true })).toHaveCount(0);
  await expect(page.getByRole("listitem", { name: "Reindex complete", exact: true })).toHaveCount(
    0,
  );
  await expect(reindex).toBeEnabled();
});

test("synchronization completion does not claim that indexing has finished", async ({
  page,
}, testInfo) => {
  const server = await sourcePage(page, "GOOGLE_DRIVE");
  const synchronize = page.getByRole("button", { name: "Synchronize now", exact: true });
  await synchronize.click();
  await expect(
    page.getByRole("listitem", { name: "Synchronization requested", exact: true }),
  ).toBeVisible();
  await expect(synchronize).toBeDisabled();
  const completed = page.getByRole("listitem", { name: "Synchronization complete", exact: true });
  await expect(completed).toHaveCount(0);
  server.indexing = true;
  server.operations[0]!.status = "SUCCEEDED";
  await expect(completed).toBeVisible();
  await expect(completed).toContainText(/indexing may still be running/i);
  await page.screenshot({
    path: testInfo.outputPath("synchronization-completed-indexing-pending.png"),
    fullPage: true,
  });
  await page.mouse.move(0, 0);
  await expect(completed).toHaveCount(0, { timeout: 8_000 });
  server.indexing = false;
  await page.getByRole("button", { name: "Refresh status", exact: true }).click();
  await synchronize.click();
  await expect.poll(() => server.operations.length).toBe(2);
  server.operations[1]!.status = "FAILED";
  server.operations[1]!.errorCode = "SOURCE_SYNC_FAILED";
  await expect(
    page.getByRole("listitem", { name: "Synchronization failed", exact: true }),
  ).toBeVisible();
  await expect(completed).toHaveCount(0);
});

test("removal keeps concurrent item outcomes separate and prevents duplicate submissions", async ({
  page,
}) => {
  const server = await sourcePage(page);
  const firstRow = page.getByRole("row").filter({ hasText: "First.txt" });
  const secondRow = page.getByRole("row").filter({ hasText: "Second.txt" });
  const release = server.holdNextOperation();
  await firstRow.getByRole("button", { name: "Remove", exact: true }).click();
  const confirmation = page.getByRole("alertdialog");
  await expect(confirmation.getByRole("button", { name: "Cancel" })).toBeFocused();
  await confirmation.getByRole("button", { name: "Remove file", exact: true }).dblclick();
  await expect(confirmation.getByRole("button", { name: "Removing file" })).toBeDisabled();
  expect(server.operations).toHaveLength(1);
  release();
  await expect(confirmation).toHaveCount(0);
  const requested = page.getByRole("listitem", { name: "Removal requested", exact: true });
  await expect(requested).toContainText("First.txt");
  await expect(firstRow.getByRole("button", { name: "Remove", exact: true })).toBeDisabled();
  await expect(firstRow.getByRole("button", { name: "Reindex" })).toBeDisabled();
  await secondRow.getByRole("button", { name: "Remove", exact: true }).click();
  await confirmation.getByRole("button", { name: "Remove file", exact: true }).click();
  await expect(confirmation).toHaveCount(0);
  await expect(requested).toHaveCount(2);
  await expect(page.getByRole("listitem", { name: "File removed", exact: true })).toHaveCount(0);
  server.operations[0]!.status = "FAILED";
  server.operations[0]!.errorCode = "SOURCE_CLEANUP_INTERNAL";
  server.operations[1]!.status = "SUPERSEDED";
  await expect(page.getByRole("listitem", { name: "Removal failed", exact: true })).toContainText(
    "First.txt",
  );
  await expect(
    page.getByRole("listitem", { name: "Removal superseded", exact: true }),
  ).toContainText("Second.txt");
  await expect(firstRow.getByRole("button", { name: "Remove", exact: true })).toBeEnabled();
  await firstRow.getByRole("button", { name: "Remove", exact: true }).click();
  await confirmation.getByRole("button", { name: "Remove file", exact: true }).click();
  await expect.poll(() => server.operations.length).toBe(3);
  server.operations[2]!.status = "SUCCEEDED";
  await expect(page.getByRole("listitem", { name: "File removed", exact: true })).toContainText(
    "First.txt",
  );
  await expect(firstRow).toHaveCount(0);
  await expect(secondRow).toBeVisible();
});

test("removal distinguishes rejection from unobservable cleanup without false completion", async ({
  page,
}) => {
  const server = await sourcePage(page);
  const row = page.getByRole("row").filter({ hasText: "First.txt" });
  server.rejectRequest = true;
  await row.getByRole("button", { name: "Remove", exact: true }).click();
  const dialog = page.getByRole("alertdialog");
  await dialog.getByRole("button", { name: "Remove file", exact: true }).click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  await expect(page.getByRole("listitem", { name: "Removal requested", exact: true })).toHaveCount(
    0,
  );
  server.rejectRequest = false;
  server.unavailableStatus = true;
  await dialog.getByRole("button", { name: "Remove file", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(
    page.getByRole("listitem", { name: "Removal status unavailable", exact: true }),
  ).toContainText("First.txt");
  await expect(page.getByRole("listitem", { name: "Removal failed", exact: true })).toHaveCount(0);
  await expect(page.getByRole("listitem", { name: "File removed", exact: true })).toHaveCount(0);
  await expect(row.getByRole("button", { name: "Remove", exact: true })).toBeEnabled();
});

test("only successful deletion navigates and its terminal notice expires on the destination", async ({
  page,
}) => {
  const server = await sourcePage(page);
  await page.getByRole("button", { name: "Delete source", exact: true }).click();
  const dialog = page.getByRole("alertdialog");
  await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
  await dialog.getByRole("button", { name: "Delete source", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(
    page.getByRole("listitem", { name: "Source deletion requested", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Delete source", exact: true })).toBeDisabled();
  await expect(
    page.getByRole("row").filter({ hasText: "First.txt" }).getByRole("button", { name: "Reindex" }),
  ).toBeDisabled();
  await expect(page).toHaveURL(/\/admin\/sources\//);
  server.operations[0]!.status = "FAILED";
  server.operations[0]!.errorCode = "SOURCE_CLEANUP_INTERNAL";
  await expect(
    page.getByRole("listitem", { name: "Source deletion failed", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("listitem", { name: "Source deleted", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Delete source", exact: true }).click();
  await dialog.getByRole("button", { name: "Delete source", exact: true }).click();
  await expect.poll(() => server.operations.length).toBe(2);
  server.operations[1]!.status = "SUPERSEDED";
  await expect(dialog).toHaveCount(0);
  await expect(
    page.getByRole("listitem", { name: "Source deletion superseded", exact: true }),
  ).toBeVisible();
  await expect(page).toHaveURL(/\/admin\/sources\//);
  await page.getByRole("button", { name: "Delete source", exact: true }).click();
  await dialog.getByRole("button", { name: "Delete source", exact: true }).click();
  await expect.poll(() => server.operations.length).toBe(3);
  server.operations[2]!.status = "SUCCEEDED";
  await expect(page).toHaveURL(/\/admin$/);
  const completed = page.getByRole("listitem", { name: "Source deleted", exact: true });
  await expect(completed).toContainText("Action feedback");
  await expect(
    page.getByRole("listitem", { name: "Source deletion requested", exact: true }),
  ).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Action feedback", exact: true })).toHaveCount(0);
  await completed.hover();
  await completed.getByRole("button", { name: "Dismiss Source deleted" }).focus();
  await page.evaluate(() => window.dispatchEvent(new Event("blur")));
  await expect(completed).toHaveCount(0, { timeout: 8_000 });
});

test("deletion with unavailable operation status keeps the Source open without declaring failure or success", async ({
  page,
}) => {
  const server = await sourcePage(page);
  server.unavailableStatus = true;
  await page.getByRole("button", { name: "Delete source", exact: true }).click();
  const dialog = page.getByRole("alertdialog");
  await dialog.getByRole("button", { name: "Delete source", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(
    page.getByRole("listitem", { name: "Deletion status unavailable", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("listitem", { name: "Source deleted", exact: true })).toHaveCount(0);
  await expect(
    page.getByRole("listitem", { name: "Source deletion failed", exact: true }),
  ).toHaveCount(0);
  await expect(page).toHaveURL(/\/admin\/sources\//);
});

test("accepted deletion never traps navigation and its observer stops when leaving the Source", async ({
  page,
}) => {
  const server = await sourcePage(page);
  await page.clock.install();
  await page.getByRole("button", { name: "Delete source", exact: true }).click();
  await page
    .getByRole("alertdialog")
    .getByRole("button", { name: "Delete source", exact: true })
    .click();
  await expect(page.getByRole("alertdialog")).toHaveCount(0);
  await expect(
    page.getByRole("listitem", { name: "Source deletion requested", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Delete source", exact: true })).toBeDisabled();
  await page.clock.runFor(1_600);
  await expect.poll(() => server.operationReads.length).toBe(1);
  await page.getByRole("main").getByRole("link", { name: "Sources", exact: true }).click();
  await expect(page).toHaveURL(/\/admin$/);
  await page.getByRole("link", { name: "Other source", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Other source" })).toBeVisible();
  server.operations[0]!.status = "SUCCEEDED";
  await page.clock.runFor(6_000);
  expect(server.operationReads).toHaveLength(1);
  await expect(page.getByRole("heading", { name: "Other source" })).toBeVisible();
  await expect(page.getByRole("listitem", { name: "Source deleted", exact: true })).toHaveCount(0);
});

test("leaving a Source cancels all item observers and clears notices before another Source opens", async ({
  page,
}) => {
  const server = await sourcePage(page);
  await page.clock.install();
  await page
    .getByRole("row")
    .filter({ hasText: "First.txt" })
    .getByRole("button", { name: "Remove", exact: true })
    .click();
  await page
    .getByRole("alertdialog")
    .getByRole("button", { name: "Remove file", exact: true })
    .click();
  await page
    .getByRole("row")
    .filter({ hasText: "Second.txt" })
    .getByRole("button", { name: "Reindex" })
    .click();
  await expect(
    page.getByRole("listitem", { name: "Removal requested", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("listitem", { name: "Reindex requested", exact: true }),
  ).toBeVisible();
  await page.clock.runFor(1_600);
  await expect.poll(() => server.operationReads.length).toBe(2);
  await page.getByRole("main").getByRole("link", { name: "Sources", exact: true }).click();
  await expect(page).toHaveURL(/\/admin$/);
  await expect(page.getByRole("listitem", { name: /requested/ })).toHaveCount(0);
  await page.getByRole("link", { name: "Other source", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Other source" })).toBeVisible();
  server.operations.forEach((operation) => {
    operation.status = "SUCCEEDED";
  });
  await page.clock.runFor(6_000);
  expect(server.operationReads).toHaveLength(2);
  await expect(page.getByRole("listitem", { name: "File removed", exact: true })).toHaveCount(0);
  await expect(page.getByRole("listitem", { name: "Reindex complete", exact: true })).toHaveCount(
    0,
  );
});

test("upload failures retain retry state and finalization acceptance never claims indexing completion", async ({
  page,
}) => {
  const server = await sourcePage(
    page,
    "FILE",
    Array.from({ length: 26 }, (_, index) => `Stored-${index + 1}.txt`),
  );
  const files = page.getByRole("region", { name: "Files", exact: true });
  await files.getByRole("button", { name: "Next files" }).click();
  await expect(files.getByRole("status")).toHaveText("2 / 2");
  let objectFailure = true;
  let finalizeFailure = true;
  let puts = 0;
  let finalizes = 0;
  const uploadId = "ac15afe3-88b3-4627-a737-51d8c4c1b290";
  await page.route("**/api/sources/*/uploads", (route) =>
    route.fulfill({
      status: 201,
      json: {
        uploadId,
        method: "PUT",
        uploadUrl: new URL("/test-object", page.url()).href,
        requiredHeaders: {},
        expiresAt: "2026-10-01T00:00:00Z",
      },
    }),
  );
  await page.route("**/test-object", (route) => {
    puts += 1;
    return route.fulfill({ status: objectFailure ? 403 : 204 });
  });
  await page.route("**/api/sources/*/uploads/*/finalize", (route) => {
    finalizes += 1;
    if (!finalizeFailure) {
      server.items.unshift({
        ...server.items[0]!,
        id: uploadId,
        filename: "New.txt",
        status: "PENDING",
        searchStatus: "WAITING",
      });
    }
    return route.fulfill(
      finalizeFailure
        ? { status: 503, json: { code: "OBJECT_UPLOAD_STORAGE_UNAVAILABLE" } }
        : {
            status: 202,
            json: {
              item: {
                id: uploadId,
                filename: "New.txt",
                sha256: "a".repeat(64),
                sizeBytes: 7,
                status: "PENDING",
                searchStatus: "WAITING",
                uploadedAt: "2026-09-01T00:00:00Z",
                lastIndexedAt: null,
                latestAttempt: {
                  id: "upload-operation",
                  filename: "New.txt",
                  status: "PENDING",
                  createdAt: "2026-09-01T00:00:00Z",
                  startedAt: null,
                  completedAt: null,
                  errorCode: null,
                },
                errorCode: null,
              },
              operation: {
                id: "upload-operation",
                type: "INDEX_ITEM",
                status: "PENDING",
                createdAt: "2026-09-01T00:00:00Z",
                completedAt: null,
                errorCode: null,
              },
            },
          },
    );
  });
  const input = page.locator('input[type="file"]');
  await input.setInputFiles({
    name: "New.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("content"),
  });
  await page.getByRole("button", { name: "Upload file", exact: true }).click();
  await expect(page.getByRole("listitem", { name: "Upload failed", exact: true })).toBeVisible();
  expect(finalizes).toBe(0);
  await expect(page.getByRole("listitem", { name: "Upload accepted", exact: true })).toHaveCount(0);
  objectFailure = false;
  await page.getByRole("button", { name: "Upload file", exact: true }).click();
  await expect(
    page.getByRole("listitem", { name: "Finalization failed", exact: true }),
  ).toBeVisible();
  await expect(input).toBeDisabled();
  await expect(page.getByRole("listitem", { name: "Upload accepted", exact: true })).toHaveCount(0);
  finalizeFailure = false;
  server.indexing = true;
  await page.getByRole("button", { name: "Retry finalization", exact: true }).click();
  await expect(page.getByRole("listitem", { name: "Upload accepted", exact: true })).toContainText(
    "New.txt",
  );
  await expect(files.getByRole("status")).toHaveText("1 / 2");
  await expect(files.getByRole("row").filter({ hasText: "New.txt" })).toBeVisible();
  await expect(files.getByRole("button", { name: "Previous files" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Retry finalization", exact: true })).toHaveCount(
    0,
  );
  await expect(input).toBeEnabled();
  expect(puts).toBe(2);
  expect(finalizes).toBe(2);
  expect(server.operationReads).toHaveLength(0);
});

test("cancelling an in-flight finalization retry keeps recovery and ignores the late response", async ({
  page,
}) => {
  await sourcePage(page);
  let finalizes = 0;
  let release!: () => void;
  const responseGate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/sources/*/uploads", (route) =>
    route.fulfill({
      status: 201,
      json: {
        uploadId: "ac15afe3-88b3-4627-a737-51d8c4c1b290",
        method: "PUT",
        uploadUrl: new URL("/test-object", page.url()).href,
        requiredHeaders: {},
        expiresAt: "2026-10-01T00:00:00Z",
      },
    }),
  );
  await page.route("**/test-object", (route) => route.fulfill({ status: 204 }));
  await page.route("**/api/sources/*/uploads/*/finalize", async (route) => {
    finalizes += 1;
    if (finalizes === 1) {
      await route.fulfill({ status: 503 });
      return;
    }
    await responseGate;
    await route.fulfill({ status: 202, json: {} });
  });
  const input = page.locator('input[type="file"]');
  await input.setInputFiles({
    name: "Retry.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("retry"),
  });
  await page.getByRole("button", { name: "Upload file", exact: true }).click();
  const retry = page.getByRole("button", { name: "Retry finalization", exact: true });
  await expect(retry).toBeEnabled();
  await retry.click();
  await expect.poll(() => finalizes).toBe(2);
  await expect(retry).toBeDisabled();
  await page.locator("form").getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(
    page.getByRole("listitem", { name: "Finalization cancelled", exact: true }),
  ).toBeVisible();
  await expect(retry).toBeEnabled();
  release();
  await expect(input).toBeDisabled();
  await expect(page.getByRole("listitem", { name: "Upload accepted", exact: true })).toHaveCount(0);
  await page.locator("form").getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(retry).toHaveCount(0);
  await expect(input).toBeEnabled();
});
