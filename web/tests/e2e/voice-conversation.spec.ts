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
  actorId: "9a1d3c5e-2b4f-4a6d-8e0c-7f1b3d5a9c2e",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Test tenant", role: "MEMBER" },
  capabilities: ["SYSTEM_BASIC", "SEARCH_READ", "CHAT_READ", "CHAT_WRITE"],
  scopedCapabilities: [],
};

/** About one second of silent MPEG-1 Layer III frames, enough for real MediaSource playback. */
function silentMp3(frames = 40) {
  const frame = Buffer.alloc(417);
  frame.set([0xff, 0xfb, 0x90, 0xc0]);
  return Buffer.concat(Array.from({ length: frames }, () => frame));
}

/** Browser side of both voice sockets: dictation returns a fixed question, read-aloud returns silent MP3. */
async function mockVoice(page: Page) {
  const tickets: string[] = [];
  const spoken: string[] = [];
  const configs: unknown[] = [];
  let recordings = 0;
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: member }));
  await page.route("**/api/chat/voice", (route) =>
    route.fulfill({ json: { sttAvailable: true, ttsAvailable: true } }),
  );
  await page.route("**/api/chat/voice/settings", (route) =>
    route.fulfill({ json: { autoSend: true, autoPlayback: true, playbackSpeed: 1.25 } }),
  );
  await page.route("**/api/chat/voice/tickets", async (route) => {
    const purpose = (route.request().postDataJSON() as { purpose?: string } | null)?.purpose;
    tickets.push(purpose ?? "TRANSCRIBE");
    await route.fulfill({
      json: {
        ticket: `ticket-${tickets.length}`,
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      },
    });
  });
  await page.routeWebSocket(/\/api\/chat\/voice\/transcribe\/stream/, (socket) => {
    recordings += 1;
    let audioBytes = 0;
    let interim = false;
    socket.onMessage((message) => {
      if (typeof message !== "string") {
        audioBytes += message.length;
        if (!interim && audioBytes >= 24_000) {
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
  await page.routeWebSocket(/\/api\/chat\/voice\/synthesize\/stream/, (socket) => {
    socket.onMessage((message) => {
      if (typeof message !== "string") return;
      const body = JSON.parse(message) as { type: string; text?: string };
      if (body.type === "config") configs.push(body);
      else if (body.type === "synthesize") spoken.push(body.text ?? "");
      else if (body.type === "end") {
        const audio = silentMp3();
        for (let offset = 0; offset < audio.length; offset += 4_096)
          socket.send(audio.subarray(offset, offset + 4_096));
        socket.send(JSON.stringify({ type: "audio_done" }));
        socket.close();
      }
    });
  });
  return { tickets, spoken, configs, recordings: () => recordings };
}

test("hands-free: a spoken question is sent, its answer is read aloud as it streams and the microphone listens again", async ({
  page,
}) => {
  const voice = await mockVoice(page);
  await page.goto("/");
  const input = page.getByRole("textbox", { name: "Question", exact: true });
  // The e2e dev server compiles the chat route on first use.
  await expect(input).toBeVisible({ timeout: 30_000 });

  await page.getByRole("button", { name: "Dictate", exact: true }).click();
  const recording = page.getByRole("group", { name: "Recording" });
  await expect(input).toHaveValue("hello", { timeout: 10_000 });
  await recording.getByRole("button", { name: "Stop recording" }).click();
  await expect(
    page.getByTestId("chat-viewport").getByText("hello from the microphone", { exact: true }),
  ).toBeVisible();

  const reading = page.getByRole("group", { name: "Auto-playback" });
  await expect(reading).toBeVisible({ timeout: 30_000 });
  await expect(input).toHaveAttribute("placeholder", "MemoryOS is reading…");
  await expect(page.getByRole("button", { name: "Dictate", exact: true })).toBeDisabled();
  await page.screenshot({ path: "../output/playwright/voice-conversation-reading.png" });

  await expect(reading).toHaveCount(0, { timeout: 20_000 });
  const read = voice.spoken.join(" ").replace(/\s+/g, " ");
  expect(read).toContain("Hello 👋");
  expect(read).toContain("Reference");
  expect(read).not.toContain("System.out");
  expect(voice.configs).toEqual([{ type: "config", speed: 1.25 }]);

  // The reading played to its end after a manual dictation, so the microphone opens again by itself.
  await expect(recording).toBeVisible({ timeout: 10_000 });
  // The recording strip appears while the microphone, ticket and socket are still being prepared.
  await expect.poll(() => voice.recordings()).toBe(2);
  expect(voice.tickets).toEqual(["TRANSCRIBE", "SYNTHESIZE", "TRANSCRIBE"]);
});

test("stopping an automatic reading by hand does not open the microphone", async ({ page }) => {
  const voice = await mockVoice(page);
  await page.goto("/");
  const input = page.getByRole("textbox", { name: "Question", exact: true });
  await expect(input).toBeVisible({ timeout: 30_000 });
  await page.getByRole("button", { name: "Dictate", exact: true }).click();
  await expect(input).toHaveValue("hello", { timeout: 10_000 });
  await page
    .getByRole("group", { name: "Recording" })
    .getByRole("button", { name: "Stop recording" })
    .click();

  const reading = page.getByRole("group", { name: "Auto-playback" });
  await expect(reading).toBeVisible({ timeout: 30_000 });
  await reading.getByRole("button", { name: "Stop reading", exact: true }).click();
  await expect(reading).toHaveCount(0);
  await page.waitForTimeout(1_000);
  await expect(page.getByRole("group", { name: "Recording" })).toHaveCount(0);
  expect(voice.recordings()).toBe(1);
});
