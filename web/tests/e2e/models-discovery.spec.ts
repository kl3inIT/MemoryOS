import { readFileSync } from "node:fs";
import { expect, test, type Page } from "@playwright/test";
import type {
  ChatReportedModels,
  CurrentIdentity,
  Model,
  ProviderView,
} from "../../src/lib/hey-api/types.gen";

// Reported models as the API returns them for an OpenRouter provider, built from its live /api/v1/models list.
const openRouter: ChatReportedModels = JSON.parse(
  readFileSync(new URL("../fixtures/openrouter-reported-models.json", import.meta.url), "utf8"),
);
// A gateway that only names models: the installed catalog fills known names, the rest open the editor.
const gateway: ChatReportedModels = {
  models: [
    {
      modelName: "gpt-5-mini",
      contextWindow: 272000,
      maxOutputTokens: 128000,
      capabilities: { toolCalling: true, vision: true, reasoning: true },
      pricing: { inputPerMillion: 0.25, outputPerMillion: 2 },
      source: "catalog",
      complete: true,
    },
    {
      modelName: "gpt-5.6-luna",
      contextWindow: 922000,
      maxOutputTokens: 128000,
      capabilities: { toolCalling: true, vision: true, reasoning: true },
      pricing: { inputPerMillion: 0.2, outputPerMillion: 1.2 },
      source: "catalog",
      complete: true,
    },
    {
      modelName: "qwen3-32b-awq",
      contextWindow: 32768,
      maxOutputTokens: null,
      capabilities: { toolCalling: false, vision: false, reasoning: false },
      pricing: null,
      source: "provider",
      complete: false,
    },
    {
      modelName: "tasco-internal-7b",
      contextWindow: null,
      maxOutputTokens: null,
      capabilities: null,
      pricing: null,
      source: "none",
      complete: false,
    },
  ],
};

const manager: CurrentIdentity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  uiLanguage: "vi",
  tenant: { displayName: "Tasco", role: "MEMBER" },
  capabilities: ["MODELS_MANAGE"],
  scopedCapabilities: [],
};
const provider = (id: string, name: string, baseUrl: string): ProviderView => ({
  id,
  name,
  adapterType: "openai",
  baseUrl,
  enabled: true,
  isPublic: true,
  groupIds: [],
  personaIds: [],
  credentialConfigured: true,
  revision: 1,
});
const providers = [
  provider("3a1f5b1e-0c7c-4b36-9d0b-5f0a1f3e7a01", "OpenRouter", "https://openrouter.ai/api/v1"),
  provider("3a1f5b1e-0c7c-4b36-9d0b-5f0a1f3e7a02", "9Router", "http://9router:20128/v1"),
];
const configured: Model = {
  id: "5c0d9a7e-3b12-4d8e-9f21-0a6b7c8d9e01",
  tenantId: "0f6f2b1a-1c2d-4e5f-8a9b-0c1d2e3f4a5b",
  providerId: providers[0]!.id,
  modelName: "qwen/qwen3.8-27b",
  displayName: "Qwen 3.8 27B",
  visible: true,
  revision: 2,
  settings: {
    contextWindow: 1000000,
    maxOutputTokens: 131072,
    capabilities: { streaming: true, toolCalling: true, vision: true, reasoning: true },
    options: { maxCompletionTokens: false },
    pricing: { inputPerMillion: 0.214, outputPerMillion: 2.55 },
    tokenizerProfile: "openai-o200k-v1",
  },
};

// Set to a directory to save screenshots for review.
const shots = process.env.MEMORYOS_PREVIEW_SHOTS;
async function shot(page: Page, name: string) {
  if (shots) await page.screenshot({ path: `${shots}/${name}.png` });
}

async function open(page: Page, width: number, scheme: "light" | "dark") {
  await page.setViewportSize({ width, height: 900 });
  await page.emulateMedia({ colorScheme: scheme });
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: manager }));
  await page.route("**/api/chat/providers", (route) => route.fulfill({ json: providers }));
  await page.route("**/api/chat/providers/*/models", (route) =>
    route.fulfill({
      json: route.request().url().includes(providers[0]!.id) ? [configured] : [],
    }),
  );
  await page.route("**/api/chat/providers/*/reported-models", (route) =>
    route.fulfill({
      json: route.request().url().includes(providers[0]!.id) ? openRouter : gateway,
    }),
  );
  await page.route("**/api/chat/provider-adapters", (route) =>
    route.fulfill({
      json: [
        {
          type: "openai",
          credentialRequirement: "REQUIRED",
          tokenizerProfiles: [{ id: "openai-o200k-v1", displayName: "OpenAI O200K" }],
          nativeWebSearch: true,
          knownModels: [],
        },
      ],
    }),
  );
  await page.route("**/api/chat/model-default", (route) =>
    route.fulfill({ json: { modelConfigurationId: configured.id, revision: 1 } }),
  );
  await page.route("**/api/chat/model-personas?*", (route) =>
    route.fulfill({ json: { items: [], nextCursor: null } }),
  );
  await page.route("**/api/chat/sessions?*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/chat/projects?*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/chat/personas?*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/chat/models", (route) => route.fulfill({ json: [] }));
  await page.goto("/admin/models");
  await expect(page.getByRole("heading", { name: /Mô hình|Models/ }).first()).toBeVisible({
    timeout: 60_000,
  });
}

for (const [label, width, scheme] of [
  ["desktop", 1440, "light"],
  ["desktop-dark", 1440, "dark"],
  ["mobile", 390, "light"],
] as const) {
  test(`reported models show published specs, search and add without typing on ${label}`, async ({
    page,
  }) => {
    await open(page, width, scheme);
    await shot(page, `${label}-models-page`);
    await page
      .getByRole("button", { name: /OpenRouter/, expanded: false })
      .first()
      .click();
    await page.getByRole("button", { name: "Lấy danh sách model từ provider" }).first().click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByText("openai/gpt-5-mini", { exact: true })).toBeVisible();
    await expect(
      dialog.getByText(`${openRouter.models.length}/${openRouter.models.length}`),
    ).toBeVisible();
    await shot(page, `${label}-discovery-openrouter`);
    await dialog.getByRole("searchbox").or(dialog.getByRole("textbox")).first().fill("claude");
    await expect(dialog.getByText("anthropic/claude-sonnet-4.5").first()).toBeVisible();
    await dialog.getByRole("checkbox").first().check();
    await shot(page, `${label}-discovery-search`);
    await page.keyboard.press("Escape");

    await page
      .getByRole("button", { name: /9Router/, expanded: false })
      .first()
      .click();
    await page.getByRole("button", { name: "Lấy danh sách model từ provider" }).nth(1).click();
    await expect(page.getByRole("dialog").getByText("gpt-5.6-luna", { exact: true })).toBeVisible();
    await shot(page, `${label}-discovery-gateway`);
    await page
      .getByRole("dialog")
      .getByRole("row", { name: /qwen3-32b-awq/ })
      .getByRole("button")
      .click();
    await expect
      .poll(() =>
        page
          .getByRole("dialog")
          .locator("input")
          .evaluateAll((inputs) =>
            inputs.some((input) => (input as HTMLInputElement).value === "32768"),
          ),
      )
      .toBe(true);
    await shot(page, `${label}-editor-prefilled`);
  });
}
