import { expect, test } from "@playwright/test";
import type {
  CurrentIdentity,
  ModelFlow,
  SystemOneConnection,
  SystemOneType,
} from "../../src/lib/hey-api/types.gen";

const manager: CurrentIdentity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  uiLanguage: "vi",
  tenant: { displayName: "System One manager", role: "MEMBER" },
  capabilities: ["MODELS_MANAGE"],
  scopedCapabilities: [],
};
const types: SystemOneType[] = [
  {
    provider: "TYPESAFE",
    requiresKey: true,
    endpoint: "FIXED",
    defaultModel: "jev-latest",
    inputPrice: 0.042,
  },
  {
    provider: "CLOUDFLARE",
    requiresKey: true,
    endpoint: "ACCOUNT",
    defaultModel: "clef-flash",
    inputPrice: 0.09,
  },
  { provider: "NINEROUTER", requiresKey: true, endpoint: "URL", defaultModel: "" },
  { provider: "LAYA", requiresKey: false, endpoint: "URL", defaultModel: "auto", inputPrice: 0 },
  { provider: "SYSTEMONE_COMPATIBLE", requiresKey: false, endpoint: "URL", defaultModel: "" },
];
const provider = {
  id: "00000000-0000-0000-0000-000000000001",
  name: "OpenCode Go",
  adapterType: "openai",
  baseUrl: "https://opencode.ai/zen/go/v1",
  enabled: true,
  isPublic: true,
  groupIds: [],
  personaIds: [],
  credentialConfigured: true,
  revision: 3,
  dataBoundary: "EXTERNAL",
};
const model = {
  id: "00000000-0000-0000-0000-000000000003",
  tenantId: "00000000-0000-0000-0000-000000000004",
  providerId: provider.id,
  modelName: "deepseek-v4-flash",
  displayName: "DeepSeek V4 Flash",
  visible: true,
  revision: 7,
  settings: {
    contextWindow: 128000,
    maxOutputTokens: 8192,
    tokenizerProfile: "openai-o200k-v1",
    capabilities: { streaming: true, toolCalling: true, vision: false, reasoning: false },
    options: {},
    pricing: null,
  },
};
const gateway: SystemOneConnection = {
  id: "7a1f0c52-0d3e-4c57-9a55-2f3c1b6d8e01",
  provider: "NINEROUTER",
  name: "9Router Jev",
  endpoint: "http://9router.internal:20128/v1",
  model: "openrouter/typesafe/jev-1.13",
  credentialConfigured: true,
  dataBoundary: "EXTERNAL",
  inputPrice: 0.042,
  revision: 2,
};
const cloudflare: SystemOneConnection = {
  id: "7a1f0c52-0d3e-4c57-9a55-2f3c1b6d8e02",
  provider: "CLOUDFLARE",
  name: "Cloudflare",
  endpoint: "3f9a1c0b7d2e4a56b8c9d0e1f2a3b4c5",
  model: "clef-flash",
  credentialConfigured: true,
  dataBoundary: "EXTERNAL",
  inputPrice: 0.09,
  revision: 1,
};
const serving: SystemOneConnection = {
  id: "7a1f0c52-0d3e-4c57-9a55-2f3c1b6d8e03",
  provider: "SYSTEMONE_COMPATIBLE",
  name: "Serving",
  endpoint: "http://serving.internal:18100/v1",
  model: "clef-flash",
  credentialConfigured: false,
  dataBoundary: "INTERNAL",
  inputPrice: 0,
  revision: 1,
};

/** Set MEMORYOS_SCREENSHOTS to a directory to keep the screens this spec walks through. */
const shots = process.env.MEMORYOS_SCREENSHOTS;

for (const width of [1440, 390]) {
  test(`model manager runs the question check on a System One connection at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    let flow: ModelFlow = {
      flow: "CHAT_GUARDRAIL",
      modelConfigurationId: model.id,
      available: true,
      reasoningEffort: "OFF",
      revision: 4,
    };
    let assigned: unknown;
    await page.route("**/api/identity/me", (route) => route.fulfill({ json: manager }));
    await page.route("**/api/chat/sessions?*", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/chat/projects?*", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/chat/system-one/types", (route) => route.fulfill({ json: types }));
    await page.route("**/api/chat/system-one/connections", (route) =>
      route.fulfill({ json: [gateway, cloudflare, serving] }),
    );
    await page.route("**/api/chat/model-flows", (route) => route.fulfill({ json: [flow] }));
    await page.route("**/api/chat/providers", (route) => route.fulfill({ json: [provider] }));
    await page.route("**/api/chat/provider-adapters", (route) =>
      route.fulfill({
        json: [
          {
            type: "openai",
            credentialRequirement: "REQUIRED",
            tokenizerProfiles: [{ id: "openai-o200k-v1", displayName: "OpenAI" }],
            nativeWebSearch: true,
            knownModels: [],
          },
        ],
      }),
    );
    await page.route(`**/api/chat/providers/${provider.id}/models`, (route) =>
      route.fulfill({ json: [model] }),
    );
    await page.route("**/api/chat/system-one/tasks/CHAT_GUARDRAIL", async (route) => {
      expect(route.request().method()).toBe("PUT");
      expect(route.request().headers()["x-memoryos-csrf"]).toBe("1");
      assigned = route.request().postDataJSON();
      flow = {
        ...flow,
        modelConfigurationId: null,
        systemOneConnectionId: gateway.id,
        revision: flow.revision + 1,
      };
      await route.fulfill({ json: flow });
    });

    await page.goto("/admin/system-one");
    await expect(page.getByRole("heading", { name: "Phân loại (System One)" })).toBeVisible();
    const connections = page.getByRole("region", { name: "Kết nối khả dụng" });
    await expect(connections.getByRole("listitem")).toHaveCount(3);
    await expect(
      page.getByRole("region", { name: "Thêm kết nối" }).getByRole("listitem"),
    ).toHaveCount(5);
    const trigger = page.getByRole("combobox", { name: "Kiểm tra câu hỏi chạy bằng" });
    await expect(trigger).toContainText("DeepSeek V4 Flash");
    if (shots) await page.screenshot({ path: `${shots}/system-one-${width}.png`, fullPage: true });

    await trigger.click();
    await expect(page.getByRole("option", { name: /9Router Jev/ })).toBeVisible();
    // The popover fades in; a screenshot waits for it to finish.
    if (shots) await page.waitForTimeout(400);
    if (shots) await page.screenshot({ path: `${shots}/system-one-${width}-menu.png` });
    await page.getByRole("option", { name: /9Router Jev/ }).click();
    await expect(trigger).toContainText("9Router Jev");
    expect(assigned).toEqual({ connectionId: gateway.id, revision: 4 });
    await expect(
      connections.getByRole("listitem", { name: "9Router Jev" }).getByText("Đang dùng"),
    ).toBeVisible();
    if (shots)
      await page.screenshot({ path: `${shots}/system-one-${width}-chosen.png`, fullPage: true });

    await page.getByRole("button", { name: "Kết nối Cloudflare", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Kết nối Cloudflare" });
    await expect(dialog.getByLabel("Account ID")).toBeVisible();
    // A Cloudflare connection exists, so the next one starts with a free name.
    await expect(dialog.getByLabel("Tên")).toHaveValue("Cloudflare 2");
    await expect(dialog.getByLabel("Giá input (USD / 1M token)")).toHaveValue("0.09");
    if (shots) await page.screenshot({ path: `${shots}/system-one-${width}-dialog.png` });
  });
}
