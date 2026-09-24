import { expect, test, type Page } from "@playwright/test";
import type {
  CurrentIdentity,
  EmbeddingModelPresetResponse,
  EmbeddingProviderResponse,
  SearchGenerationResponse,
  SearchSettingsResponse,
} from "../../src/lib/hey-api/types.gen";

const manager: CurrentIdentity = {
  actorId: "4f7d2c1e-9a3b-4e5f-8c6d-7b8a9c0d1e2f",
  authorizationVersion: 1,
  uiLanguage: "vi",
  tenant: { displayName: "Tasco", role: "MEMBER" },
  capabilities: ["MODELS_MANAGE"],
  scopedCapabilities: [],
};
const qwenPrefix = "Instruct: Given a question, retrieve passages that answer it\nQuery: ";
const openAi: EmbeddingProviderResponse = {
  id: "5d1a9f38-8e0c-4d63-b7f0-2f7b6f8f4e01",
  name: "OpenAI",
  endpoint: "https://api.openai.com/v1",
  dataBoundary: "EXTERNAL",
  hasApiKey: true,
  revision: 1,
  inUse: true,
};
const serving: EmbeddingProviderResponse = {
  id: "c2d9a7b1-4f3e-4b6a-8e21-9d0f7a6b5c44",
  name: "serving-embedding",
  endpoint: "http://172.24.244.79:18090/v1",
  dataBoundary: "INTERNAL",
  hasApiKey: true,
  revision: 3,
  inUse: false,
};
const presets: EmbeddingModelPresetResponse[] = [
  {
    model: "Qwen/Qwen3-Embedding-0.6B",
    label: "Qwen3-Embedding 0.6B",
    dimensions: 1024,
    queryPrefix: qwenPrefix,
    documentPrefix: "",
    maxInputTokens: 32_768,
  },
  {
    model: "Qwen/Qwen3-Embedding-4B",
    label: "Qwen3-Embedding 4B",
    dimensions: 2560,
    queryPrefix: qwenPrefix,
    documentPrefix: "",
    maxInputTokens: 32_768,
  },
  {
    model: "BAAI/bge-m3",
    label: "BGE-M3",
    dimensions: 1024,
    queryPrefix: "",
    documentPrefix: "",
    maxInputTokens: 8_192,
  },
  {
    model: "text-embedding-3-large",
    label: "OpenAI text-embedding-3-large",
    dimensions: 3072,
    queryPrefix: "",
    documentPrefix: "",
    maxInputTokens: 8_191,
  },
];
const present: SearchGenerationResponse = {
  id: "0b8c62c4-6e8a-4d07-9a55-1f7b0c3f2a10",
  status: "PRESENT",
  providerId: openAi.id,
  providerName: openAi.name,
  dataBoundary: "EXTERNAL",
  model: "text-embedding-3-large",
  dimensions: 3072,
  queryPrefix: "",
  documentPrefix: "",
  minimumSemanticScore: 0.7,
  chunkConvention: "v3",
  automatic: false,
  documentCount: 11_575,
  createdAt: "2026-08-01T08:00:00Z",
  activatedAt: "2026-08-01T08:05:00Z",
  retainedUntil: null,
  cleanupBlocked: false,
};
const qwenFuture: SearchGenerationResponse = {
  ...present,
  id: "a3f1c0de-2b1e-4c8f-9d77-6a0e5b2c1f33",
  status: "FUTURE",
  providerId: serving.id,
  providerName: serving.name,
  dataBoundary: "INTERNAL",
  model: "Qwen/Qwen3-Embedding-0.6B",
  dimensions: 1024,
  queryPrefix: qwenPrefix,
  documentCount: 8_214,
  createdAt: "2026-09-23T09:40:00Z",
  activatedAt: null,
};
const bgePast: SearchGenerationResponse = {
  ...present,
  id: "f0e1d2c3-b4a5-4697-8877-665544332211",
  status: "PAST",
  providerId: serving.id,
  providerName: serving.name,
  dataBoundary: "INTERNAL",
  model: "BAAI/bge-m3",
  dimensions: 1024,
  documentCount: 11_402,
  activatedAt: "2026-09-10T02:00:00Z",
  retainedUntil: "2026-09-29T02:00:00Z",
};
/** A retired index whose deletion failed three times: shown with a warning until cleanup succeeds. */
const blockedPast: SearchGenerationResponse = {
  ...present,
  id: "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
  status: "PAST",
  model: "text-embedding-3-small",
  dimensions: 1536,
  documentCount: 10_880,
  activatedAt: "2026-08-20T02:00:00Z",
  retainedUntil: "2026-09-01T02:00:00Z",
  cleanupBlocked: true,
};
const inProgress = {
  ready: 8_214,
  total: 11_575,
  failed: 3,
  pending: 3_358,
  estimatedSecondsRemaining: 740,
  switchable: false,
};

type Mock = {
  settings: SearchSettingsResponse;
  providers: EmbeddingProviderResponse[];
  requests: { method: string; path: string; body: unknown }[];
  settingsReads: number;
  /** Called on each settings read after the first, so the page's poll can advance the rebuild. */
  onPoll?: (mock: Mock) => void;
};

async function install(page: Page, initial: Partial<SearchSettingsResponse> = {}) {
  const mock: Mock = {
    settings: { present, future: null, past: [], rebuild: null, ...initial },
    providers: [openAi, serving],
    requests: [],
    settingsReads: 0,
  };
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: manager }));
  await page.route("**/api/chat/sessions?*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/chat/projects?*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/search/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    if (method !== "GET") {
      expect(request.headers()["x-memoryos-csrf"]).toBe("1");
      mock.requests.push({ method, path, body: request.postDataJSON() });
    }
    const key = `${method} ${path}`;
    if (key === "GET /api/search/settings") {
      mock.settingsReads += 1;
      if (mock.settingsReads > 1) mock.onPoll?.(mock);
      return route.fulfill({ json: mock.settings });
    }
    if (key === "GET /api/search/embedding-providers")
      return route.fulfill({ json: mock.providers });
    if (key === "GET /api/search/embedding-models") return route.fulfill({ json: presets });
    if (key === "POST /api/search/settings/future") {
      mock.settings = { ...mock.settings, future: qwenFuture, rebuild: inProgress };
      return route.fulfill({ status: 201, json: qwenFuture });
    }
    if (key === "DELETE /api/search/settings/future") {
      mock.settings = { ...mock.settings, future: null, rebuild: null };
      return route.fulfill({ status: 204 });
    }
    if (key === "POST /api/search/settings/future/switch") {
      const next = mock.settings.future!;
      mock.settings = {
        present: { ...next, status: "PRESENT", activatedAt: "2026-09-23T10:02:00Z" },
        future: null,
        past: [{ ...mock.settings.present, status: "PAST", retainedUntil: "2026-09-30T10:02:00Z" }],
        rebuild: null,
      };
      return route.fulfill({ json: mock.settings });
    }
    if (key === `POST /api/search/settings/past/${bgePast.id}/restore`) {
      mock.settings = {
        present: { ...bgePast, status: "PRESENT", retainedUntil: null },
        future: null,
        past: [{ ...mock.settings.present, status: "PAST", retainedUntil: "2026-09-30T10:02:00Z" }],
        rebuild: null,
      };
      return route.fulfill({ json: mock.settings });
    }
    if (key === "POST /api/search/embedding-providers/test")
      return route.fulfill({
        json: {
          ok: true,
          model: "Qwen/Qwen3-Embedding-0.6B",
          dimensions: 1024,
          latencyMs: 38,
          error: null,
        },
      });
    if (key === `DELETE /api/search/embedding-providers/${openAi.id}`)
      return route.fulfill({
        status: 409,
        contentType: "application/problem+json",
        json: {
          status: 409,
          code: "SEARCH_EMBEDDING_PROVIDER_IN_USE",
          detail: "Generation text-embedding-3-large (PRESENT) uses this provider.",
        },
      });
    return route.fulfill({ status: 404, json: { status: 404 } });
  });
  return mock;
}

async function open(page: Page, width: number) {
  await page.setViewportSize({ width, height: 900 });
  await page.goto("/admin/search-settings");
  // The e2e dev server compiles the admin route on first use.
  await expect(
    page.getByRole("heading", { name: "Cấu hình tìm kiếm", exact: true, level: 1 }),
  ).toBeVisible({ timeout: 30_000 });
}

async function noHorizontalScroll(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
}

/** Screens for the self-review, written only when asked: `MEMORYOS_SCREENSHOTS=1`. */
async function capture(page: Page, name: string, width: number) {
  if (!process.env.MEMORYOS_SCREENSHOTS) return;
  for (const scheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    // Controls animate their colours for 150 ms; a capture before that shows half a theme.
    await page.waitForTimeout(400);
    await page.screenshot({
      path: `../.tmp/mem-135-screens/${name}-${width}-${scheme}.png`,
      fullPage: true,
    });
  }
  await page.emulateMedia({ colorScheme: "light" });
}

for (const width of [1440, 390]) {
  test.describe(`search settings at ${width}px`, () => {
    test("a model change creates a future, its progress is polled and it is switched", async ({
      page,
    }) => {
      const mock = await install(page);
      await open(page, width);
      if (width < 768) await page.getByRole("button", { name: "Mở điều hướng" }).click();
      await expect(
        page.getByRole("link", { name: "Cấu hình tìm kiếm", exact: true }),
      ).toHaveAttribute("aria-current", "page");
      if (width < 768) await page.keyboard.press("Escape");

      const current = page.getByRole("region", { name: "Đang dùng" });
      await expect(current.getByText("text-embedding-3-large")).toBeVisible();
      await expect(current.getByText("3.072", { exact: true })).toBeVisible();
      await expect(current.getByText("11.575", { exact: true })).toBeVisible();
      await expect(current.getByText("Bên ngoài")).toBeVisible();
      await noHorizontalScroll(page);
      await capture(page, "idle", width);

      await current.getByRole("button", { name: "Đổi model" }).click();
      const dialog = page.getByRole("dialog", { name: "Đổi model embedding" });
      await expect(dialog.getByRole("button", { name: "Dựng lại index" })).toBeDisabled();
      await dialog.getByLabel("Provider embedding").selectOption({ label: "serving-embedding" });
      await dialog.getByLabel("Model đã biết").selectOption({ label: "Qwen3-Embedding 0.6B" });
      await expect(dialog.getByLabel("Tên model")).toHaveValue("Qwen/Qwen3-Embedding-0.6B");
      await expect(dialog.getByLabel("Số chiều")).toHaveValue("1024");
      await expect(dialog.getByLabel("Tiền tố câu hỏi")).toHaveValue(qwenPrefix);
      await expect(dialog.getByLabel("Ngưỡng ngữ nghĩa")).toHaveValue("0.7");
      await dialog.getByLabel("Ngưỡng ngữ nghĩa").fill("0.65");
      await capture(page, "change-model", width);
      await dialog.getByRole("button", { name: "Dựng lại index" }).click();
      const confirm = page.getByRole("alertdialog", {
        name: "Dựng lại index với Qwen/Qwen3-Embedding-0.6B?",
      });
      await confirm.getByRole("button", { name: "Bắt đầu dựng lại" }).click();
      await expect(dialog).toHaveCount(0);
      expect(mock.requests).toContainEqual({
        method: "POST",
        path: "/api/search/settings/future",
        body: {
          providerId: serving.id,
          model: "Qwen/Qwen3-Embedding-0.6B",
          dimensions: 1024,
          queryPrefix: qwenPrefix,
          documentPrefix: "",
          minimumSemanticScore: 0.65,
        },
      });

      const rebuild = page.getByRole("region", { name: "Đang dựng lại" });
      await expect(rebuild.getByText("8.214 / 11.575 tài liệu")).toBeVisible();
      await expect(rebuild.getByText("70%")).toBeVisible();
      await expect(rebuild.getByText("3 lỗi")).toBeVisible();
      await expect(rebuild.getByText("Khoảng 12 phút")).toBeVisible();
      await expect(rebuild.getByText("Nội bộ")).toBeVisible();
      await expect(current.getByRole("button", { name: "Đổi model" })).toBeDisabled();
      const switchButton = rebuild.getByRole("button", { name: "Chuyển index" });
      await expect(switchButton).toBeDisabled();
      await noHorizontalScroll(page);
      await capture(page, "rebuilding", width);

      // The worker finishes; only the page's poll can learn it.
      mock.onPoll = (state) => {
        state.settings = {
          ...state.settings,
          rebuild: {
            ready: 11_572,
            total: 11_575,
            failed: 3,
            pending: 0,
            estimatedSecondsRemaining: 0,
            switchable: true,
          },
        };
      };
      await expect(switchButton).toBeEnabled({ timeout: 15_000 });
      await switchButton.click();
      await page
        .getByRole("alertdialog", { name: "Chuyển sang Qwen/Qwen3-Embedding-0.6B?" })
        .getByRole("button", { name: "Chuyển index" })
        .click();
      await expect(rebuild).toHaveCount(0);
      await expect(current.getByText("Qwen/Qwen3-Embedding-0.6B")).toBeVisible();
      await expect(
        page.getByRole("region", { name: "Index cũ" }).getByText("text-embedding-3-large"),
      ).toBeVisible();
      expect(mock.requests.map((entry) => `${entry.method} ${entry.path}`)).toContain(
        "POST /api/search/settings/future/switch",
      );
      // Polling stops once nothing is rebuilt.
      const reads = mock.settingsReads;
      await page.waitForTimeout(6_000);
      expect(mock.settingsReads).toBe(reads);
    });

    test("a rebuild is cancelled after confirmation", async ({ page }) => {
      const mock = await install(page, { future: qwenFuture, rebuild: inProgress });
      await open(page, width);
      const rebuild = page.getByRole("region", { name: "Đang dựng lại" });
      await rebuild.getByRole("button", { name: "Hủy", exact: true }).click();
      await page
        .getByRole("alertdialog", { name: "Hủy dựng lại?" })
        .getByRole("button", { name: "Hủy dựng lại" })
        .click();
      await expect(rebuild).toHaveCount(0);
      expect(mock.requests).toContainEqual({
        method: "DELETE",
        path: "/api/search/settings/future",
        body: null,
      });
      await expect(
        page.getByRole("region", { name: "Đang dùng" }).getByRole("button", { name: "Đổi model" }),
      ).toBeEnabled();
    });

    test("an automatic rebuild has no switch button", async ({ page }) => {
      await install(page, {
        future: {
          ...present,
          id: qwenFuture.id,
          status: "FUTURE",
          activatedAt: null,
          automatic: true,
        },
        rebuild: { ...inProgress, estimatedSecondsRemaining: null },
      });
      await open(page, width);
      const rebuild = page.getByRole("region", { name: "Đang dựng lại" });
      await expect(rebuild.getByText("Tự động")).toBeVisible();
      await expect(rebuild.getByText("Đang ước lượng")).toBeVisible();
      await expect(rebuild.getByRole("button", { name: "Chuyển index" })).toHaveCount(0);
      await expect(rebuild.getByRole("button", { name: "Hủy", exact: true })).toBeEnabled();
      await capture(page, "automatic", width);
    });

    test("a previous index is restored after confirmation", async ({ page }) => {
      const mock = await install(page, { past: [bgePast, blockedPast] });
      await open(page, width);
      const past = page.getByRole("region", { name: "Index cũ" });
      await expect(past.getByText("BAAI/bge-m3")).toBeVisible();
      await expect(past.getByText("Không xoá được index")).toBeVisible();
      await expect(
        past.getByRole("button", { name: "Hoàn tác về text-embedding-3-small" }),
      ).toBeDisabled();
      await noHorizontalScroll(page);
      await capture(page, "previous-indexes", width);
      await past.getByRole("button", { name: "Hoàn tác về BAAI/bge-m3" }).click();
      await page
        .getByRole("alertdialog", { name: "Hoàn tác về BAAI/bge-m3?" })
        .getByRole("button", { name: "Hoàn tác" })
        .click();
      await expect(
        page.getByRole("region", { name: "Đang dùng" }).getByText("BAAI/bge-m3"),
      ).toBeVisible();
      expect(mock.requests).toContainEqual({
        method: "POST",
        path: `/api/search/settings/past/${bgePast.id}/restore`,
        body: null,
      });
    });

    test("a provider is tested, and deleting one in use shows the conflict", async ({ page }) => {
      const mock = await install(page);
      await open(page, width);
      const providers = page.getByRole("region", { name: "Provider embedding" });
      await providers.getByRole("button", { name: "Sửa provider serving-embedding" }).click();
      const editor = page.getByRole("dialog", { name: "Sửa provider serving-embedding" });
      await editor.getByLabel("Tên model").fill("Qwen/Qwen3-Embedding-0.6B");
      await editor.getByLabel("Số chiều").fill("1024");
      await editor.getByRole("button", { name: "Kiểm tra", exact: true }).click();
      await expect(
        editor.getByText("Kết nối được · Qwen/Qwen3-Embedding-0.6B · 1024 chiều · 38 ms"),
      ).toBeVisible();
      expect(mock.requests).toContainEqual({
        method: "POST",
        path: "/api/search/embedding-providers/test",
        body: {
          providerId: serving.id,
          endpoint: null,
          apiKey: null,
          model: "Qwen/Qwen3-Embedding-0.6B",
          dimensions: 1024,
        },
      });
      expect(
        await editor.evaluate((element) => {
          const bounds = element.getBoundingClientRect();
          return bounds.left >= 0 && bounds.right <= window.innerWidth;
        }),
      ).toBe(true);
      await capture(page, "provider-editor", width);
      await editor.getByLabel("Endpoint").fill("http://10.9.9.9:8080/v1");
      await expect(editor.getByRole("combobox")).toHaveValue("REPLACE");
      await expect(editor.getByRole("option", { name: "Giữ khóa đang lưu" })).toHaveJSProperty(
        "disabled",
        true,
      );
      await expect(editor.getByRole("button", { name: "Lưu provider" })).toBeDisabled();
      await editor.getByRole("button", { name: "Đóng", exact: true }).click();

      await providers.getByRole("button", { name: "Xoá provider OpenAI" }).click();
      const confirm = page.getByRole("alertdialog", { name: "Xoá provider OpenAI?" });
      await confirm.getByRole("button", { name: "Xoá provider" }).click();
      await expect(
        confirm.getByText(
          "Provider đang được một index dùng. Generation text-embedding-3-large (PRESENT) uses this provider.",
        ),
      ).toBeVisible();
      await capture(page, "delete-conflict", width);
    });
  });
}

test("the operating Tenant check refuses another Tenant's model manager", async ({ page }) => {
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: manager }));
  await page.route("**/api/chat/sessions?*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/chat/projects?*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/search/**", (route) =>
    route.fulfill({ status: 403, contentType: "application/problem+json", json: { status: 403 } }),
  );
  await open(page, 1440);
  await expect(page.getByRole("alert").getByText("Không có quyền")).toBeVisible();
  await capture(page, "forbidden", 1440);
});

test("a member without model management never requests search settings", async ({ page }) => {
  let protectedRequests = 0;
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({ json: { ...manager, capabilities: ["CHAT_WRITE"] } }),
  );
  await page.route("**/api/search/**", async (route) => {
    protectedRequests += 1;
    await route.fulfill({ status: 403 });
  });
  await page.goto("/admin/search-settings");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("heading", { name: "Cấu hình tìm kiếm", level: 1 })).toHaveCount(0);
  expect(protectedRequests).toBe(0);
});
