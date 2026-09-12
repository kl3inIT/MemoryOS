import { createHash, randomUUID } from "node:crypto";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import type {
  CurrentIdentity,
  SourceSummary,
  SourceItemPage,
  SourceOperation,
  SourceUploadAuthorization,
} from "../../src/lib/hey-api/types.gen.ts";

function required(name: string): string {
  const value = process.env[`MEMORYOS_SMOKE_${name}`];
  if (!value) throw new Error(`Missing MEMORYOS_SMOKE_${name}`);
  return value;
}

const app = new URL(required("ORIGIN"));
const issuer = new URL(required("ISSUER"));
const storage = new URL(required("STORAGE_ORIGIN"));
const actorId = required("ACTOR_ID");
for (const url of [app, issuer, storage]) {
  if (url.protocol !== "https:" || url.username || url.password) {
    throw new Error("Staging smoke requires HTTPS endpoints without URL credentials");
  }
}
if (
  app.href !== `${app.origin}/` ||
  storage.href !== `${storage.origin}/` ||
  issuer.search ||
  issuer.hash
) {
  throw new Error("App and storage configuration must be origins");
}

async function api<T>(request: APIRequestContext, path: string, data?: object): Promise<T> {
  const response = await request.fetch(new URL(path, app).href, {
    method: data === undefined ? "GET" : "POST",
    data,
    headers: { "X-MemoryOS-CSRF": "1", Origin: app.origin },
    maxRedirects: 0,
    timeout: 30_000,
  });
  try {
    if (!response.ok()) throw new Error(`HTTP ${response.status()}`);
    return (await response.json()) as T;
  } finally {
    await response.dispose();
  }
}

async function login(page: Page, request: APIRequestContext, phase: (value: string) => void) {
  const username = required("USERNAME");
  const password = required("PASSWORD");
  phase("OIDC redirect");
  await page.goto(new URL("/oauth2/authorization/memoryos", app).href);
  expect(new URL(page.url()).origin).toBe(issuer.origin);
  phase("login form");
  await page.locator('#kc-form-login input[name="username"]').fill(username);
  await page.locator('#kc-form-login input[name="password"]').fill(password);
  phase("OIDC callback");
  await page.locator('#kc-form-login [type="submit"]').click();
  await page.waitForURL((url) => url.origin === app.origin);
  phase("authenticated identity API");
  const identity = await api<CurrentIdentity>(request, "/api/identity/me");
  phase("configured actor identity");
  expect(identity.actorId === actorId).toBe(true);
  phase("active Tenant membership");
  expect(identity.tenant !== null).toBe(true);
  phase("Source management and deletion permissions");
  expect(identity.capabilities.includes("SOURCES_MANAGE")).toBe(true);
  expect(identity.capabilities.includes("SOURCES_DELETE")).toBe(true);
}

test("staging identity and permissions @preflight", async ({ page, context }) => {
  let phase = "credential configuration";
  try {
    await login(page, context.request, (value) => {
      phase = value;
    });
  } catch {
    // Deliberately omit raw browser errors, login values and callback URLs.
    throw new Error(`Staging preflight failed during ${phase}; runtime was not changed`);
  }
});

test("real login, upload, indexing, Search, reader and denied anonymous access", async ({
  page,
  context,
  request,
}) => {
  let source: string | undefined;
  let phase = "login";
  let failure: string | undefined;
  try {
    await login(page, context.request, (value) => {
      phase = value;
    });

    phase = "upload";
    const marker = `MEMORYOS-SMOKE-${randomUUID()}`;
    const content = Buffer.from(
      `# Staging acceptance\n\nReference ${marker}. Acceptance number: 314159.\n`,
    );
    const detail = await api<SourceSummary>(context.request, "/api/sources/file", { name: marker });
    source = detail.id;
    console.log(`Smoke-owned source for cleanup: ${source}`);
    const denied = await request.get(new URL(`/api/sources/${source}`, app).href, {
      maxRedirects: 0,
    });
    expect(denied.status()).toBe(401);
    await denied.dispose();
    const upload = await api<SourceUploadAuthorization>(
      context.request,
      `/api/sources/${source}/uploads`,
      {
        filename: `${marker}.md`,
        mediaType: "text/markdown",
        sizeBytes: content.length,
        sha256: createHash("sha256").update(content).digest("hex"),
      },
    );
    expect(upload.method === "PUT" && new URL(upload.uploadUrl).origin === storage.origin).toBe(
      true,
    );
    const put = await request.put(upload.uploadUrl, {
      data: content,
      headers: upload.requiredHeaders,
      maxRedirects: 0,
      timeout: 45_000,
    });
    expect(put.ok()).toBe(true);
    await put.dispose();
    await api(context.request, `/api/sources/${source}/uploads/${upload.uploadId}/finalize`, {});

    phase = "indexing";
    await expect
      .poll(
        async () => {
          const value = await api<SourceItemPage>(context.request, `/api/sources/${source}/items`);
          return value.items.length === 1 && value.items[0]?.searchStatus === "READY";
        },
        { timeout: 180_000, intervals: [2_000] },
      )
      .toBe(true);

    phase = "Search and reader";
    await page.goto(new URL("/search", app).href);
    await page.getByRole("textbox", { name: "Search documents" }).fill(marker);
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await page.getByRole("button", { name: `${marker}.md`, exact: true }).click();
    const reader = page.getByRole("dialog", { name: `${marker}.md` });
    await expect(reader).toContainText(marker);
    await expect(reader).toContainText("314159");
  } catch {
    // Do not retain Playwright call logs containing login input or presigned URLs.
    failure = `Staging smoke failed during ${phase}; source ${source ?? "not created"}`;
  } finally {
    if (source) {
      try {
        const deletion = await api<SourceOperation>(
          context.request,
          `/api/sources/${source}/delete`,
          {},
        );
        await expect
          .poll(
            async () => {
              const operation = await api<SourceOperation>(
                context.request,
                `/api/source-operations/${deletion.id}`,
              );
              return operation.status;
            },
            { timeout: 180_000, intervals: [2_000] },
          )
          .toBe("SUCCEEDED");
      } catch {
        failure = `${failure ?? "Smoke completed"}; cleanup failed for source ${source}`;
      }
    }
  }
  if (failure) throw new Error(failure);
});
