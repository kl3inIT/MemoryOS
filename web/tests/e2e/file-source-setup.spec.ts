import { expect, test } from "@playwright/test";

for (const failure of ["none", "create", "upload", "finalize"] as const) {
  test(`FILE single-step setup: ${failure}`, async ({ page }, testInfo) => {
    test.setTimeout(60_000);
    const uploadedFilename = failure === "none" ? "knowledge.pdf" : "knowledge.txt";
    const source = {
      id: "15f8cb72-2628-4d75-bcf1-8f6cda95a120",
      name: "knowledge",
      type: "FILE",
      access: "PUBLIC",
      status: "ACTIVE",
      documentCount: 0,
      pendingWork: true,
      lastSucceededAt: null,
      errorCode: null,
      actions: ["upload", "reindex", "remove_items", "delete", "manage_groups"],
    };
    let creates = 0;
    let puts = 0;
    let finalizes = 0;
    await page.route("**/api/identity/me", (route) =>
      route.fulfill({
        json: {
          actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
          authorizationVersion: 1,
          uiLanguage: "en",
          tenant: { displayName: "Tasco", role: "OWNER" },
          capabilities: [
            "SYSTEM_ADMIN",
            "SYSTEM_BASIC",
            "SEARCH_READ",
            "CHAT_READ",
            "CHAT_WRITE",
            "IMAGE_GENERATE",
            "LLM_GATEWAY_USE",
            "USERS_MANAGE",
            "GROUPS_READ",
            "GROUPS_MANAGE",
            "SOURCES_READ",
            "SOURCES_MANAGE",
            "SOURCES_DELETE",
            "MODELS_MANAGE",
          ],
          scopedCapabilities: [],
        },
      }),
    );
    await page.route("**/api/sources**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (route.request().method() === "GET") {
        if (path === "/api/sources/group-options") {
          await route.fulfill({
            json: {
              items: [
                {
                  id: "8d11ec56-34c6-44fe-9ad0-f147f37f571c",
                  name: "Knowledge team",
                  systemKey: null,
                },
              ],
              page: 0,
              size: 25,
              totalItems: 1,
              totalPages: 1,
            },
          });
          return;
        }
        if (path.endsWith("/groups")) {
          await route.fulfill({
            json: {
              items: [
                {
                  id: "8d11ec56-34c6-44fe-9ad0-f147f37f571c",
                  name: "Knowledge team",
                  systemKey: null,
                },
              ],
            },
          });
          return;
        }
        await route.fulfill({
          json:
            path === "/api/sources"
              ? [source]
              : path.endsWith("/items")
                ? {
                    items: [
                      {
                        id: "item-1",
                        filename: uploadedFilename,
                        status: "PENDING",
                        sizeBytes: failure === "none" ? 100 * 1024 * 1024 : 5,
                        searchStatus: "WAITING",
                        lastIndexedAt: null,
                        latestAttempt: null,
                        errorCode: null,
                      },
                    ],
                    nextCursor: null,
                    totalItems: 1,
                  }
                : path.endsWith("/index-attempts")
                  ? { items: [], nextCursor: null, totalItems: 0 }
                  : source,
        });
      } else if (path === "/api/sources/file") {
        creates++;
        if (failure === "create" && creates === 1) {
          await route.fulfill({ status: 503, json: { title: "Unavailable", status: 503 } });
        } else {
          await route.fulfill({ status: 201, json: source });
        }
      } else if (path.endsWith("/uploads")) {
        await route.fulfill({
          status: 201,
          json: {
            uploadId: "upload-1",
            method: "PUT",
            uploadUrl: "https://objects.example.test/file",
            requiredHeaders: {},
            expiresAt: "2026-10-01T00:00:00Z",
          },
        });
      } else if (path.endsWith("/finalize")) {
        finalizes++;
        await route.fulfill(
          failure === "finalize" && finalizes === 1
            ? { status: 503, json: { title: "Unavailable", status: 503 } }
            : {
                status: 202,
                json: { item: { id: "item-1" }, operation: { id: "op-1", status: "NOT_STARTED" } },
              },
        );
      } else {
        await route.fulfill({ status: 405 });
      }
    });
    await page.route("https://objects.example.test/**", async (route) => {
      const headers = {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "PUT",
        "access-control-allow-headers": "*",
      };
      if (route.request().method() === "OPTIONS") {
        await route.fulfill({ status: 204, headers });
        return;
      }
      puts++;
      await route.fulfill({ status: failure === "upload" && puts === 1 ? 503 : 200, headers });
    });
    await page.goto("/admin/sources/new/file");
    const submit = page.getByRole("button", { name: "Upload and create" });
    const input = page.getByLabel("Choose PDF, DOCX, PPTX, XLSX, CSV, TXT, or Markdown file");
    await expect(submit).toBeDisabled();
    await input.setInputFiles({
      name: "empty.txt",
      mimeType: "text/plain",
      buffer: Buffer.alloc(0),
    });
    await expect(page.getByRole("alert")).toBeVisible();
    await expect(submit).toBeDisabled();
    await input.setInputFiles({
      name: "script.exe",
      mimeType: "application/octet-stream",
      buffer: Buffer.from("test"),
    });
    await expect(page.getByRole("alert")).toBeVisible();
    await input.evaluate((element: HTMLInputElement) => {
      const data = new DataTransfer();
      data.items.add(
        new File([new Uint8Array(100 * 1024 * 1024 + 1)], "large.pdf", {
          type: "application/pdf",
        }),
      );
      element.files = data.files;
      element.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await expect(page.getByRole("alert")).toBeVisible();
    await expect(submit).toBeDisabled();
    expect(creates).toBe(0);
    await input.setInputFiles({
      name: "knowledge.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("hello"),
    });
    await expect(page.getByLabel("Source name")).toHaveValue("knowledge");
    await page.getByRole("button", { name: "Remove selected file" }).click();
    await expect(submit).toBeDisabled();
    const transfer = await page.evaluateHandle(() => {
      const data = new DataTransfer();
      data.items.add(new File(["hello"], "knowledge.txt", { type: "text/plain" }));
      return data;
    });
    await page
      .getByText("Drag and drop your file here")
      .dispatchEvent("drop", { dataTransfer: transfer });
    await expect(page.getByText("knowledge.txt", { exact: true })).toBeVisible();
    await expect(submit).toBeEnabled();
    if (failure === "none") {
      await page.locator("summary").filter({ hasText: "Access groups" }).click();
      const groupSearch = page.getByRole("search");
      await groupSearch.getByRole("searchbox").fill("Knowledge team");
      await groupSearch.getByRole("searchbox").press("Enter");
      await groupSearch.getByRole("button", { name: "Search", exact: true }).click();
      await expect(page.getByRole("checkbox", { name: "Knowledge team" })).toBeVisible();
      expect(creates).toBe(0);
      await page.locator("summary").filter({ hasText: "Access groups" }).click();
      await page.screenshot({
        path: testInfo.outputPath("file-setup-desktop.png"),
        fullPage: true,
      });
      await page.setViewportSize({ width: 390, height: 844 });
      await expect
        .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
        .toBe(true);
      await page.screenshot({ path: testInfo.outputPath("file-setup-mobile.png"), fullPage: true });
      await page.setViewportSize({ width: 1280, height: 720 });
      await page.getByRole("button", { name: "Tenant owner" }).click();
      await page.getByRole("button", { name: "Use dark theme" }).click();
      await expect(page.locator("html")).toHaveClass(/dark/);
      await page.keyboard.press("Escape");
      await expect(page.getByRole("button", { name: "Use light theme" })).toBeHidden();
      await page.screenshot({
        path: testInfo.outputPath("file-setup-dark.png"),
        fullPage: true,
        animations: "disabled",
      });
      await input.evaluate((element: HTMLInputElement, filename) => {
        const data = new DataTransfer();
        data.items.add(
          new File([new Uint8Array(100 * 1024 * 1024).fill(65)], filename, {
            type: "application/pdf",
          }),
        );
        element.files = data.files;
        element.dispatchEvent(new Event("change", { bubbles: true }));
      }, uploadedFilename);
      await expect(page.getByRole("alert")).toHaveCount(0);
      await expect(submit).toBeEnabled();
    }
    const acceptedUpload = page.waitForResponse(
      (response) => response.url().endsWith("/finalize") && response.status() === 202,
      { timeout: 30_000 },
    );
    await submit.evaluate((button: HTMLButtonElement) => {
      button.click();
      button.click();
    });
    if (failure !== "none") {
      await expect(page.getByRole("alert")).toBeVisible();
      await expect(page).toHaveURL(/\/new\/file$/);
      await expect(
        page.getByRole("listitem", {
          name:
            failure === "create"
              ? "Source creation failed"
              : "Source created; upload needs attention",
          exact: true,
        }),
      ).toContainText(source.name);
      await expect(
        page.getByRole("listitem", { name: "Source created; upload accepted", exact: true }),
      ).toHaveCount(0);
      await page
        .getByRole("button", {
          name:
            failure === "create"
              ? "Upload and create"
              : failure === "upload"
                ? "Retry upload"
                : "Retry finalization",
          exact: true,
        })
        .click();
    }
    await acceptedUpload;
    await expect(page).toHaveURL(new RegExp(`/admin/sources/${source.id}$`));
    await expect(page.getByText(uploadedFilename, { exact: true })).toBeVisible();
    const acceptedNotice = page.getByRole("listitem", {
      name: "Source created; upload accepted",
      exact: true,
    });
    await expect(acceptedNotice).toContainText(source.name);
    await expect(acceptedNotice).toContainText("accepted for indexing; indexing is not complete");
    await expect(
      page.getByRole("listitem", { name: /indexing complete|upload complete/i }),
    ).toHaveCount(0);
    await acceptedNotice
      .getByRole("button", { name: "Dismiss Source created; upload accepted" })
      .focus();
    await expect(acceptedNotice).toHaveCount(0, { timeout: 8_000 });
    expect(creates).toBe(failure === "create" ? 2 : 1);
    expect(puts).toBe(failure === "upload" ? 2 : 1);
    expect(finalizes).toBe(failure === "finalize" ? 2 : 1);
  });
}

test("scoped File creation requires managed groups and never publishes files", async ({ page }) => {
  const group = {
    id: "6d11ec56-34c6-44fe-9ad0-f147f37f571c",
    name: "Managed team",
    systemKey: null,
  };
  const source = {
    id: "15f8cb72-2628-4d75-bcf1-8f6cda95a120",
    name: "Private knowledge",
    type: "FILE",
    access: "RESTRICTED",
    status: "ACTIVE",
    pendingWork: false,
    documentCount: 0,
    lastSucceededAt: null,
    errorCode: null,
    actions: ["upload", "rename", "manage_groups"],
  };
  let created = false;
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
        authorizationVersion: 1,
        uiLanguage: "en",
        tenant: { displayName: "Team", role: "MEMBER" },
        capabilities: [],
        scopedCapabilities: ["SOURCES_READ", "SOURCES_MANAGE"],
      },
    }),
  );
  await page.route("**/api/sources**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/sources/group-options") {
      await route.fulfill({
        json: { items: [group], page: 0, size: 25, totalItems: 1, totalPages: 1 },
      });
    } else if (path === "/api/sources/file") {
      expect(request.postDataJSON()).toMatchObject({ access: "RESTRICTED", groupIds: [group.id] });
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      created = true;
      await route.fulfill({ status: 201, json: source });
    } else if (path.endsWith("/uploads")) {
      await route.fulfill({
        status: 201,
        json: {
          uploadId: "upload-1",
          method: "PUT",
          uploadUrl: "https://objects.example.test/private",
          requiredHeaders: {},
          expiresAt: "2026-10-01T00:00:00Z",
        },
      });
    } else if (path.endsWith("/finalize")) {
      await route.fulfill({
        status: 202,
        json: { item: { id: "item-1" }, operation: { id: "op-1", status: "NOT_STARTED" } },
      });
    } else if (path.endsWith("/groups")) {
      await route.fulfill({ json: { items: [group] } });
    } else if (path.endsWith("/items") || path.endsWith("/index-attempts")) {
      await route.fulfill({ json: { items: [], nextCursor: null, totalItems: 0 } });
    } else {
      await route.fulfill({ json: path === "/api/sources" ? (created ? [source] : []) : source });
    }
  });
  await page.route("https://objects.example.test/**", (route) =>
    route.fulfill({
      status: route.request().method() === "OPTIONS" ? 204 : 200,
      headers: {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "PUT",
        "access-control-allow-headers": "*",
      },
    }),
  );
  await page.goto("/admin");
  await page.getByRole("link", { name: "Add source" }).click();
  await page.goto("/admin/sources/new/file");
  await page.getByLabel("Choose PDF, DOCX, PPTX, XLSX, CSV, TXT, or Markdown file").setInputFiles({
    name: "Private knowledge.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("private"),
  });
  await expect(page.getByRole("combobox", { name: "Visibility" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Upload and create" })).toBeDisabled();
  expect(created).toBe(false);
  await page.getByRole("checkbox", { name: /Managed team/ }).check();
  await page.getByRole("button", { name: "Upload and create" }).click();
  await expect(page).toHaveURL(new RegExp(`/admin/sources/${source.id}$`));
  await expect(page.getByRole("heading", { name: source.name, exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Change visibility" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Delete source" })).toHaveCount(0);
});
