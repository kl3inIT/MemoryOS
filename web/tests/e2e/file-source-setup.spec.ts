import { expect, test } from "@playwright/test";

for (const failure of ["none", "create", "upload", "finalize"] as const) {
  test(`FILE single-step setup: ${failure}`, async ({ page }, testInfo) => {
    const source = {
      id: "15f8cb72-2628-4d75-bcf1-8f6cda95a120",
      name: "knowledge",
      type: "FILE",
      access: "PUBLIC",
      status: "ACTIVE",
      documentCount: 1,
      pendingWork: false,
    };
    let creates = 0;
    let puts = 0;
    let finalizes = 0;
    await page.route("**/api/identity/me", (route) =>
      route.fulfill({
        json: {
          actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
          tenant: { displayName: "Tasco", role: "OWNER" },
          capabilities: ["SOURCES_MANAGE"],
        },
      }),
    );
    await page.route("**/api/sources**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (route.request().method() === "GET") {
        await route.fulfill({
          json:
            path === "/api/sources"
              ? [source]
              : {
                  source,
                  items: [
                    { id: "item-1", filename: "knowledge.txt", status: "INDEXED", sizeBytes: 5 },
                  ],
                },
        });
      } else if (path === "/api/sources/file") {
        creates++;
        if (failure === "create" && creates === 1) {
          await route.fulfill({ status: 503, json: { title: "Unavailable", status: 503 } });
        } else {
          expect(route.request().postDataJSON()).toEqual({ name: "knowledge" });
          await route.fulfill({ status: 201, json: { source, items: [] } });
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
    const input = page.getByLabel("Choose PDF, DOCX, TXT, or Markdown file");
    await expect(submit).toBeDisabled();
    await input.setInputFiles({
      name: "empty.txt",
      mimeType: "text/plain",
      buffer: Buffer.alloc(0),
    });
    await expect(page.getByRole("alert")).toContainText("between 1 byte and 10 MiB");
    await expect(submit).toBeDisabled();
    await input.setInputFiles({
      name: "script.exe",
      mimeType: "application/octet-stream",
      buffer: Buffer.from("test"),
    });
    await expect(page.getByRole("alert")).toContainText("Choose a PDF");
    await input.setInputFiles({
      name: "large.txt",
      mimeType: "text/plain",
      buffer: Buffer.alloc(10 * 1024 * 1024 + 1),
    });
    await expect(page.getByRole("alert")).toContainText("between 1 byte and 10 MiB");
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
      const bounds = await submit.boundingBox();
      expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(720);
      await page.screenshot({ path: testInfo.outputPath("file-setup-dark.png"), fullPage: true });
    }
    await submit.evaluate((button: HTMLButtonElement) => {
      button.click();
      button.click();
    });
    if (failure !== "none") {
      await expect(page.getByRole("alert")).toBeVisible();
      await expect(page).toHaveURL(/\/new\/file$/);
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
    await expect(page).toHaveURL(new RegExp(`/admin/sources/${source.id}$`));
    await expect(page.getByText("knowledge.txt", { exact: true })).toBeVisible();
    expect(creates).toBe(failure === "create" ? 2 : 1);
    expect(puts).toBe(failure === "upload" ? 2 : 1);
    expect(finalizes).toBe(failure === "finalize" ? 2 : 1);
  });
}
