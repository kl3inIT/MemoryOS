import { expect, test } from "@playwright/test";
import type {
  CurrentIdentity,
  VoiceConnectionResponse,
  VoiceProviderResponse,
} from "../../src/lib/hey-api/types.gen";

const manager: CurrentIdentity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Voice manager", role: "MEMBER" },
  capabilities: ["MODELS_MANAGE"],
  scopedCapabilities: [],
};
const providers: VoiceProviderResponse[] = [
  {
    provider: "OPENAI",
    requiresKey: true,
    requiresEndpoint: false,
    defaultEndpoint: "https://api.openai.com/v1",
    sttModels: ["whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe"],
    ttsModels: ["tts-1", "tts-1-hd"],
    voices: ["alloy", "echo", "fable", "onyx", "nova", "shimmer"],
  },
  {
    provider: "OPENAI_COMPATIBLE",
    requiresKey: false,
    requiresEndpoint: true,
    defaultEndpoint: "",
    sttModels: [],
    ttsModels: [],
    voices: [],
  },
];

for (const width of [1440, 390]) {
  test(`model manager connects the default speech-to-text provider at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    let connections: VoiceConnectionResponse[] = [];
    let saved: unknown;
    await page.route("**/api/identity/me", (route) => route.fulfill({ json: manager }));
    await page.route("**/api/chat/sessions?*", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/chat/projects?*", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/chat/voice", (route) =>
      route.fulfill({
        json: { sttAvailable: connections.some((item) => item.sttActive), ttsAvailable: false },
      }),
    );
    await page.route("**/api/chat/voice/providers", (route) => route.fulfill({ json: providers }));
    await page.route("**/api/chat/voice/connections", (route) =>
      route.fulfill({ json: connections }),
    );
    await page.route("**/api/chat/voice/connections/OPENAI", async (route) => {
      expect(route.request().method()).toBe("PUT");
      expect(route.request().headers()["x-memoryos-csrf"]).toBe("1");
      saved = route.request().postDataJSON();
      connections = [
        {
          provider: "OPENAI",
          endpoint: "",
          sttModel: "gpt-4o-transcribe",
          ttsModel: "",
          ttsVoice: "",
          credentialConfigured: true,
          sttActive: true,
          ttsActive: false,
          revision: 1,
        },
      ];
      await route.fulfill({ json: connections[0] });
    });

    await page.goto("/admin/voice");
    // The e2e dev server compiles the admin route on first use.
    await expect(page.getByRole("heading", { name: "Voice", exact: true, level: 1 })).toBeVisible({
      timeout: 30_000,
    });
    if (width < 768) await page.getByRole("button", { name: "Open navigation" }).click();
    await expect(page.getByRole("link", { name: "Voice", exact: true })).toHaveAttribute(
      "aria-current",
      "page",
    );
    if (width < 768) await page.keyboard.press("Escape");

    const speechToText = page.getByRole("region", { name: "Speech to text" });
    await expect(
      speechToText.getByText(
        "No default provider is selected, so the microphone in Chat and Search is off.",
      ),
    ).toBeVisible();
    await speechToText
      .getByRole("listitem", { name: "OpenAI", exact: true })
      .getByRole("button", { name: "Connect", exact: true })
      .click();
    const dialog = page.getByRole("dialog", { name: "Connect OpenAI" });
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("Transcription model").selectOption("gpt-4o-transcribe");
    await dialog.getByLabel("API key", { exact: true }).fill("synthetic-transient-browser-secret");
    expect(
      await dialog.evaluate((element) => {
        const bounds = element.getBoundingClientRect();
        return bounds.left >= 0 && bounds.right <= window.innerWidth;
      }),
    ).toBe(true);
    await page.screenshot({ path: `../output/playwright/voice-admin-dialog-${width}.png` });
    await dialog.getByRole("button", { name: "Connect", exact: true }).click();

    await expect(dialog).toHaveCount(0);
    expect(saved).toMatchObject({
      endpoint: "",
      sttModel: "gpt-4o-transcribe",
      credentialAction: "REPLACE",
      activate: "STT",
      revision: 0,
    });
    await expect(
      speechToText
        .getByRole("listitem", { name: "OpenAI", exact: true })
        .getByText("Active", { exact: true }),
    ).toBeVisible();
    await expect(
      page
        .getByRole("region", { name: "Text to speech" })
        .getByRole("listitem", { name: "OpenAI", exact: true })
        .getByText("Needs setup", { exact: true }),
    ).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.screenshot({
      path: `../output/playwright/voice-admin-${width}.png`,
      fullPage: true,
    });
  });
}

test("a member without model management never requests voice configuration", async ({ page }) => {
  let protectedRequests = 0;
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({ json: { ...manager, capabilities: ["CHAT_WRITE"] } }),
  );
  await page.route(/\/api\/chat\/voice\/(providers|connections)/, async (route) => {
    protectedRequests += 1;
    await route.fulfill({ status: 403 });
  });
  await page.goto("/admin/voice");
  await expect(
    page.getByRole("heading", { name: "You don’t have access to this area." }),
  ).toBeVisible({ timeout: 30_000 });
  expect(protectedRequests).toBe(0);
});
