// Temporary screenshot harness for self-review; not committed.
import { test, type Page } from "@playwright/test";

const out = "D:/MemoryOS/output/mem145-shots";
const identity = { actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1", authorizationVersion: 1, uiLanguage: "vi",
  tenant: { displayName: "Tasco", role: "MEMBER" }, capabilities: ["CHAT_READ", "CHAT_WRITE"], scopedCapabilities: [] };
const preferences = { workRole: "Kế toán trưởng, phòng Tài chính", personalPreferences: "Trả lời ngắn gọn, gạch đầu dòng. Số tiền ghi theo định dạng Việt Nam (1.250.000 đ). Luôn nêu tài liệu nguồn.",
  defaultModelId: "luna", autoScroll: true, displayName: "Trần Thu Hà", email: "ha.tt@tasco.vn" };
const model = (id: string, providerName: string, modelName: string, displayName: string, pricing: unknown, isDefault = false) => ({
  id, providerId: providerName, providerName, modelName, displayName,
  capabilities: { streaming: true, toolCalling: true, vision: false, reasoning: true }, contextWindow: 272000, maxOutputTokens: 128000, pricing, isDefault });
const models = [
  model("luna", "OpenAI", "gpt-5.6-luna", "GPT-5.6 Luna", { inputPerMillion: 1.25, outputPerMillion: 10, cachedInputPerMillion: 0.125 }, true),
  model("mini", "OpenAI", "gpt-5-mini", "gpt-5-mini", { inputPerMillion: 0.25, outputPerMillion: 2, cachedInputPerMillion: 0.025 }),
  model("deepseek", "9Router", "ocg/deepseek-v4-flash", "ocg/deepseek-v4-flash", { inputPerMillion: 0.3, outputPerMillion: 1.2, cachedInputPerMillion: null }),
  model("qwen", "vLLM nội bộ", "qwen3-32b", "qwen3-32b", null),
];
const row = (label: string, calls: number, cost: number, input: number, output: number, unknown = 0) =>
  ({ key: label, label, detail: "OpenAI", calls, unknownCostCalls: unknown, inputTokens: input, outputTokens: output, cost });
const mine = { summary: { cost: 3.12, externalCost: 2.9, calls: 412, unknownCostCalls: 3, inputTokens: 1_700_000, outputTokens: 250_000,
  cacheReadTokens: 310_000, imageCount: 3, audioSeconds: 0, activePeople: 1 },
  daily: Array.from({ length: 19 }, (_, i) => [
    { day: `2026-09-${String(i + 1).padStart(2, "0")}`, series: "gpt-5.6-luna", cost: 0.08 + (i % 5) * 0.03, calls: 12 + i, inputTokens: 6e4, outputTokens: 9e3, cacheReadTokens: 1e4 },
    { day: `2026-09-${String(i + 1).padStart(2, "0")}`, series: "qwen3-32b", cost: 0.01 + (i % 3) * 0.004, calls: 4, inputTokens: 2e4, outputTokens: 3e3, cacheReadTokens: 0 },
  ]).flat(),
  models: [row("gpt-5.6-luna", 300, 2.9, 1_300_000, 200_000), row("qwen3-32b", 109, 0.22, 400_000, 50_000), row("gpt-image-1", 3, 0, 0, 0, 3)],
  flows: [], providers: [] };
const earlier = { ...mine, summary: { ...mine.summary, cost: 2.7 } };
const mcp = (id: string, name: string, description: string, connectionState: string, authType = "OAUTH") => ({ id, slug: id, name, description,
  url: `https://${id}.example/mcp`, authType, authPerformer: "PER_USER", status: "CONNECTED", connectionState,
  oauthClients: [{ id: `${id}-c`, label: "Tasco" }], enabledToolCount: 6, credentialUpdatedAt: null, revision: 1 });

async function mocks(page: Page) {
  await page.route("**/api/identity/me", (r) => r.fulfill({ json: identity }));
  await page.route("**/api/chat/sessions?*", (r) => r.fulfill({ json: [] }));
  await page.route("**/api/chat/projects?*", (r) => r.fulfill({ json: [] }));
  await page.route("**/api/chat/preferences", (r) => r.fulfill({ json: preferences }));
  await page.route("**/api/chat/models*", (r) => r.fulfill({ json: models }));
  await page.route("**/api/ai-costs/mine?*", (r) =>
    r.fulfill({ json: new URL(r.request().url()).searchParams.get("from")!.startsWith("2026-08") ? earlier : mine }));
  await page.route("**/api/chat/voice/availability", (r) => r.fulfill({ json: { sttAvailable: true, ttsAvailable: true } }));
  await page.route("**/api/chat/voice/settings", (r) => r.fulfill({ json: { autoSend: true, autoPlayback: false, playbackSpeed: 1 } }));
  await page.route("**/api/mcp/connections", (r) => r.fulfill({ json: [
    mcp("drive", "Google Drive", "Đọc và tìm tệp trong Drive của bạn", "CONNECTED"),
    mcp("jira", "Jira Tasco", "Tra cứu và tạo issue", "NOT_CONNECTED"),
    mcp("ledger", "API sổ cái kế toán", "Số liệu sổ cái theo kỳ", "NOT_CONNECTED", "API_TOKEN")] }));
}

for (const [width, scheme] of [[1280, "light"], [1280, "dark"], [390, "light"]] as const) {
  test(`settings ${width} ${scheme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await page.setViewportSize({ width, height: width < 768 ? 1700 : 1100 });
    await mocks(page);
    for (const [tab, heading] of [["chat", "Cuộc trò chuyện"], ["usage", "Theo mô hình"], ["general", "Hồ sơ"], ["connections", "Google Drive"]] as const) {
      await page.goto(`/settings/${tab}`);
      await page.getByText(heading, { exact: true }).first().waitFor({ timeout: 30000 });
      await page.waitForTimeout(500);
      await page.screenshot({ path: `${out}/${tab}-${width}-${scheme}.png`, fullPage: true });
    }
  });
}
