import { expect, test, type Page } from "@playwright/test";
import type { CurrentIdentity } from "../../src/lib/hey-api/types.gen";

// Chromium's fake capture device plays a tone, so the real AudioWorklet produces voiced PCM16 frames.
test.use({
  permissions: ["microphone"],
  launchOptions: {
    args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"],
  },
});

const member: CurrentIdentity = {
  actorId: "e62a621f-41d2-4853-aa76-b600dafd8e34",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Test tenant", role: "MEMBER" },
  capabilities: ["SYSTEM_BASIC", "SEARCH_READ", "CHAT_READ", "CHAT_WRITE"],
  scopedCapabilities: [],
};

type Stream = { url: string; audioBytes: number; ended: boolean };

/** The browser side of the voice contract; the WebSocket stands in for the MemoryOS transcription stream. */
async function mockVoice(page: Page, autoSend: boolean) {
  const stream: Stream = { url: "", audioBytes: 0, ended: false };
  const tickets: string[] = [];
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: member }));
  await page.route("**/api/chat/voice", (route) =>
    route.fulfill({ json: { sttAvailable: true, ttsAvailable: false } }),
  );
  await page.route("**/api/chat/voice/settings", (route) =>
    route.fulfill({ json: { autoSend, autoPlayback: false, playbackSpeed: 1 } }),
  );
  await page.route("**/api/chat/voice/tickets", async (route) => {
    tickets.push(route.request().headers()["x-memoryos-csrf"] ?? "");
    await route.fulfill({
      json: { ticket: "synthetic-ticket", expiresAt: new Date(Date.now() + 60_000).toISOString() },
    });
  });
  await page.routeWebSocket(/\/api\/chat\/voice\/transcribe\/stream/, (socket) => {
    stream.url = socket.url();
    let interim = false;
    socket.onMessage((message) => {
      if (typeof message !== "string") {
        stream.audioBytes += message.length;
        if (!interim && stream.audioBytes >= 24_000) {
          interim = true;
          socket.send(
            JSON.stringify({
              type: "transcript",
              text: "hello",
              isFinal: false,
              utteranceEnd: false,
              revision: 1,
            }),
          );
        }
        return;
      }
      if (JSON.parse(message).type !== "end") return;
      stream.ended = true;
      socket.send(
        JSON.stringify({
          type: "transcript",
          text: "hello from the microphone",
          isFinal: true,
          utteranceEnd: false,
          revision: 2,
        }),
      );
      socket.close();
    });
  });
  return { stream, tickets };
}

test("dictation streams microphone audio, previews interim text and holds Send until the final transcript", async ({
  page,
}) => {
  const { stream, tickets } = await mockVoice(page, false);
  await page.goto("/");
  const input = page.getByRole("textbox", { name: "Question", exact: true });
  // The e2e dev server compiles the chat route on first use.
  await expect(input).toBeVisible({ timeout: 30_000 });

  await page.getByRole("button", { name: "Dictate", exact: true }).click();
  const recording = page.getByRole("group", { name: "Recording" });
  await expect(recording).toBeVisible();
  await expect(input).toHaveAttribute("placeholder", "Listening…");
  await expect(input).toHaveValue("hello", { timeout: 10_000 });
  await expect(page.getByRole("button", { name: "Send question" })).toBeDisabled();
  expect(tickets).toEqual(["1"]);
  expect(new URL(stream.url).searchParams.get("ticket")).toBe("synthetic-ticket");
  expect(new URL(stream.url).searchParams.get("language")).toBe("en");
  await page.screenshot({ path: "../output/playwright/voice-dictation-recording.png" });

  await recording.getByRole("button", { name: "Mute microphone" }).click();
  await expect(recording.getByRole("button", { name: "Unmute microphone" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await recording.getByRole("button", { name: "Unmute microphone" }).click();
  await recording.getByRole("button", { name: "Stop recording" }).click();

  await expect(input).toHaveValue("hello from the microphone");
  await expect(recording).toHaveCount(0);
  expect(stream.ended).toBe(true);
  expect(stream.audioBytes % 2).toBe(0);
  await expect(page.getByRole("button", { name: "Send question" })).toBeEnabled();
});

test("Auto-Send sends the final transcript when recording stops", async ({ page }) => {
  await mockVoice(page, true);
  await page.goto("/");
  const input = page.getByRole("textbox", { name: "Question", exact: true });
  await page.getByRole("button", { name: "Dictate", exact: true }).click();
  const recording = page.getByRole("group", { name: "Recording" });
  await expect(input).toHaveValue("hello", { timeout: 10_000 });
  await recording.getByRole("button", { name: "Stop recording" }).click();
  await expect(
    page.getByTestId("chat-viewport").getByText("hello from the microphone", { exact: true }),
  ).toBeVisible();
  await expect(input).toHaveValue("");
});

test("the microphone is absent while the Tenant has no speech-to-text provider", async ({
  page,
}) => {
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: member }));
  await page.route("**/api/chat/voice", (route) =>
    route.fulfill({ json: { sttAvailable: false, ttsAvailable: false } }),
  );
  await page.goto("/");
  await expect(page.getByRole("textbox", { name: "Question", exact: true })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByRole("button", { name: "Dictate", exact: true })).toHaveCount(0);
});
