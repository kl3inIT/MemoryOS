import { expect, test } from "@playwright/test";
import type { CurrentIdentity } from "../../src/lib/hey-api/types.gen";

const member: CurrentIdentity = {
  actorId: "0f6f0a52-5b7c-4d53-a5f0-1a8f37a8b0c4",
  authorizationVersion: 1,
  uiLanguage: "en",
  tenant: { displayName: "Test tenant", role: "MEMBER" },
  capabilities: ["SYSTEM_BASIC", "SEARCH_READ", "CHAT_READ", "CHAT_WRITE"],
  scopedCapabilities: [],
};

/** About one second of silent MPEG-1 Layer III frames (128 kbit/s, 44.1 kHz, mono), enough for real MediaSource playback. */
function silentMp3(frames = 40) {
  const frame = Buffer.alloc(417);
  frame.set([0xff, 0xfb, 0x90, 0xc0]);
  return Buffer.concat(Array.from({ length: frames }, () => frame));
}

test("an answer is read aloud as speech text at the member's speed and can be stopped", async ({
  page,
}) => {
  const requests: { body: unknown; csrf?: string }[] = [];
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: member }));
  await page.route("**/api/chat/voice", (route) =>
    route.fulfill({ json: { sttAvailable: false, ttsAvailable: true } }),
  );
  await page.route("**/api/chat/voice/settings", (route) =>
    route.fulfill({ json: { autoSend: false, autoPlayback: false, playbackSpeed: 1.5 } }),
  );
  await page.route("**/api/chat/voice/synthesize", async (route) => {
    requests.push({
      body: route.request().postDataJSON(),
      csrf: route.request().headers()["x-memoryos-csrf"],
    });
    await route.fulfill({ body: silentMp3(), contentType: "audio/mpeg" });
  });

  await page.goto("/");
  const input = page.getByRole("textbox", { name: "Question", exact: true });
  // The e2e dev server compiles the chat route on first use.
  await expect(input).toBeVisible({ timeout: 30_000 });
  await input.fill("Show an example");
  await page.keyboard.press("Enter");

  const readAloud = page.getByRole("button", { name: "Read aloud", exact: true });
  await expect(readAloud).toBeEnabled({ timeout: 30_000 });
  await readAloud.click();
  await expect.poll(() => requests.length).toBe(1);
  expect(requests[0]).toEqual({
    body: { text: "Hello 👋\nHere is an example:\nReference", speed: 1.5 },
    csrf: "1",
  });
  // Playback ends by itself; the button returns to reading.
  await expect(readAloud).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("alert")).toHaveCount(0);

  await readAloud.click();
  const stop = page.getByRole("button", { name: "Stop reading", exact: true });
  await expect(stop).toBeVisible();
  await stop.click();
  await expect(readAloud).toBeVisible();
  expect(requests).toHaveLength(2);
  expect(errors).toEqual([]);
});
