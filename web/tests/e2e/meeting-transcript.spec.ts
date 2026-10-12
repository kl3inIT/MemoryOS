import type { Page } from "@playwright/test";
import { expect, test } from "./deployment-policy";
import type { CurrentIdentity, MeetingDetail } from "../../src/lib/hey-api/types.gen";

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
  uiLanguage: "vi",
  tenant: { displayName: "Tasco", role: "MEMBER" },
  capabilities: ["SYSTEM_BASIC", "SEARCH_READ", "CHAT_READ", "CHAT_WRITE"],
  scopedCapabilities: [],
};

const MEETING_ID = "3b1d7e90-6c2a-4f18-9a4d-5e8c2b7f1a63";
/** One voice running on without a pause: longer than any screen is wide. */
const RAMBLE =
  "Vâng, thì chắc là để em giới thiệu qua một chút xíu ạ, cũng như là mọi người ở trong đây có thể là chưa biết hết, thì ngày hôm nay mục tiêu của mình là chia sẻ về hệ thống voice demo, sau đó là cấu hình agent và campaign, rồi mới đến phần demo cuộc gọi outbound cho mọi người xem.";
const LINES = 120;
const TOPICS = ["Mục tiêu và thành phần tham dự", "Cấu hình agent và campaign", "Demo cuộc gọi"];

function line(index: number) {
  return {
    id: `u${index}`,
    track: "MIC" as const,
    speaker: String((index % 3) + 1),
    startMs: index * 30_000,
    endMs: index * 30_000 + 20_000,
    text: index % 5 === 0 ? RAMBLE : `Câu số ${index} của cuộc họp.`,
    confidence: 0.92,
    spans: [],
  };
}

function meetingOf(overrides: Partial<MeetingDetail>): MeetingDetail {
  return {
    id: MEETING_ID,
    title: "Giới thiệu nền tảng voice AI",
    kind: "IN_PERSON",
    language: "vi",
    participants: [],
    terms: [],
    notes: "",
    status: "ENDED",
    provider: "SONIOX",
    diarized: true,
    createdAt: new Date().toISOString(),
    endedAt: new Date().toISOString(),
    revision: 0,
    speakers: [],
    utterances: [],
    minutes: {
      status: "NONE",
      failure: null,
      summary: "",
      kind: "",
      generatedAt: null,
      decisions: [],
      actions: [],
      edited: false,
      topics: [],
    },
    audio: { status: "NONE", failure: null, filename: null, sizeBytes: 0, provider: null },
    owned: true,
    readers: [],
    starred: [],
    bookmarks: [],
    ...overrides,
  } as MeetingDetail;
}

async function mockShell(page: Page) {
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: member }));
  await page.route("**/api/chat/sessions?*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/chat/projects?*", (route) => route.fulfill({ json: [] }));
}

for (const width of [1440, 390]) {
  test(`a long meeting is read inside the page, by subject, with a way back at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await mockShell(page);
    const utterances = Array.from({ length: LINES }, (_, index) => line(index));
    const meeting = meetingOf({
      utterances,
      // Marked while line 100 was being said.
      bookmarks: [{ id: "b1", atMs: 100 * 30_000 + 5_000, label: "Đánh dấu 1" }],
      speakers: ["1", "2", "3"].map((label) => ({
        track: "MIC" as const,
        label,
        name: null,
        // Each voice introduced itself in a sentence far longer than the page is wide.
        suggestion: { name: `Người ${label}`, utteranceId: "u0", confidence: 0.9 },
      })),
      minutes: {
        status: "READY",
        failure: null,
        summary: "Buổi giới thiệu nền tảng voice AI.",
        kind: "Giới thiệu",
        generatedAt: new Date().toISOString(),
        decisions: [],
        actions: [],
        edited: false,
        topics: TOPICS.map((text, index) => ({
          id: `t${index}`,
          text,
          owner: null,
          due: null,
          quote: null,
          sourceUtteranceId: `u${index * 40}`,
          done: false,
          edited: false,
        })),
      },
    });
    await page.route("**/api/meetings", (route) => route.fulfill({ json: [] }));
    await page.route(`**/api/meetings/${MEETING_ID}`, (route) => route.fulfill({ json: meeting }));
    await page.route(`**/api/meetings/${MEETING_ID}/corrections`, (route) =>
      route.fulfill({ json: [] }),
    );

    await page.goto(`/meetings/${MEETING_ID}`);
    await page.getByRole("tab", { name: "Transcript" }).click({ timeout: 30_000 });
    const transcript = page.getByRole("region", { name: "Transcript" });
    await expect(transcript.getByText("Câu số 1 của cuộc họp.")).toBeVisible();

    // A sentence of any length wraps or is cut short; nothing on the page is wider than the window.
    const widest = await page.evaluate(() =>
      Math.max(
        ...[...document.querySelectorAll("main *")].map(
          (element) => element.getBoundingClientRect().right,
        ),
      ),
    );
    expect(widest, "nothing reaches past the right edge of the window").toBeLessThanOrEqual(width);
    await page.screenshot({ path: `../output/playwright/meeting-transcript-${width}.png` });

    // Nothing that acts on the meeting waits below the transcript: sharing stands in the header, and editing and
    // deleting open from the menu beside it.
    await expect(page.getByRole("button", { name: "Chia sẻ", exact: true })).toBeVisible();
    await page.getByRole("button", { name: /^Thao tác khác cho/ }).click();
    await expect(page.getByRole("menuitem", { name: "Sửa thông tin" })).toBeVisible();
    await expect(page.getByRole("menuitem", { name: "Xóa cuộc họp" })).toBeVisible();
    await page.keyboard.press("Escape");

    // The meeting's panel always stands beside a wide page; over a narrow one it opens from the shell header,
    // whose button stays on screen however far the transcript has been scrolled.
    const panel = page.getByRole("button", { name: "Chi tiết cuộc họp", exact: true });
    const timeline = page.getByRole("navigation", { name: "Dòng thời gian" });
    if (width >= 1280) {
      await expect(timeline).toBeVisible();
      await expect(panel).toHaveCount(0);
    }
    const open = async () => {
      if (width < 1280) await panel.click();
    };
    // The subject being read is the marked one, and choosing another moves both the transcript and the mark.
    await open();
    await expect(timeline.getByRole("button", { name: new RegExp(TOPICS[0]!) })).toHaveAttribute(
      "aria-current",
      "true",
    );
    await timeline.getByRole("button", { name: new RegExp(TOPICS[1]!) }).click();
    await expect(page.locator("#u40")).toBeInViewport();
    await open();
    await expect(timeline.getByRole("button", { name: new RegExp(TOPICS[1]!) })).toHaveAttribute(
      "aria-current",
      "true",
    );
    await page.screenshot({ path: `../output/playwright/meeting-timeline-${width}.png` });
    // A moment marked during the meeting is a place in the same list, and opens the line being said then.
    await timeline.getByRole("button", { name: /Đánh dấu 1/ }).click();
    await expect(page.locator("#u100")).toBeInViewport();

    // The page leads back to the list it was opened from.
    await page
      .getByRole("navigation", { name: "Đường dẫn phân cấp" })
      .getByRole("link", { name: "Cuộc họp" })
      .click();
    await expect(page).toHaveURL(/\/meetings$/);
  });
}

test("a meeting being recorded keeps its newest sentence on screen", async ({ page }) => {
  test.setTimeout(60_000);
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockShell(page);
  let meeting: MeetingDetail | undefined;
  await page.route("**/api/meetings", async (route) => {
    if (route.request().method() !== "POST") return route.fulfill({ json: [] });
    meeting = meetingOf({ status: "RECORDING", provider: null, endedAt: null });
    await route.fulfill({ status: 201, json: meeting });
  });
  await page.route(`**/api/meetings/${MEETING_ID}`, (route) => route.fulfill({ json: meeting }));
  await page.route("**/api/identity/principals*", (route) =>
    route.fulfill({ json: { people: [], groups: [] } }),
  );
  await page.route("**/api/meetings/transcribers", (route) =>
    route.fulfill({
      json: [
        {
          provider: "SONIOX",
          model: "stt-rt-v5",
          diarizes: true,
          maxBytes: 524_288_000,
          selected: true,
        },
      ],
    }),
  );
  await page.route(`**/api/meetings/${MEETING_ID}/tickets`, (route) =>
    route.fulfill({
      json: { ticket: "synthetic-ticket", expiresAt: new Date(Date.now() + 60_000).toISOString() },
    }),
  );
  const LIVE_LINES = 30;
  const LATER_LINES = 5;
  let limit = LIVE_LINES;
  await page.routeWebSocket(/\/api\/meeting-stream/, (socket) => {
    socket.send(JSON.stringify({ type: "ready" }));
    let heard = 0;
    let said = 0;
    socket.onMessage((message) => {
      if (typeof message === "string") return;
      heard += message.length;
      // A sentence lands for every quarter second of audio, the next one already being said below it.
      while (said < limit && heard > (said + 1) * 8_000) {
        const utterance = line(said++);
        meeting = { ...meeting!, utterances: [...meeting!.utterances, utterance] };
        socket.send(JSON.stringify({ type: "utterance", utterance }));
        socket.send(
          JSON.stringify({
            type: "preview",
            track: "MIC",
            speaker: "1",
            text: `Đang nói câu ${said}`,
          }),
        );
      }
    });
  });

  await page.goto("/meetings");
  // An empty list offers the same button twice: in its header and in its empty state.
  await page.getByRole("button", { name: "Ghi cuộc họp mới" }).first().click({ timeout: 30_000 });
  const dialog = page.getByRole("dialog", { name: "Ghi cuộc họp mới" });
  await dialog.getByRole("radio", { name: "Họp trực tiếp" }).click();
  await dialog.getByLabel("Tôi đã thông báo cho mọi người rằng buổi họp được ghi lại.").check();
  await dialog.getByRole("button", { name: "Bắt đầu ghi" }).click();
  await expect(page.getByRole("timer")).toBeVisible();

  // Nobody scrolls: the sentence being said stays in view as the transcript outgrows the screen.
  const speaking = page.getByText(`Đang nói câu ${LIVE_LINES}…`);
  await expect(speaking).toBeVisible({ timeout: 30_000 });
  await expect(speaking).toBeInViewport();
  await expect(page.locator(`#u${LIVE_LINES - 1}`)).toBeInViewport();
  await page.screenshot({ path: "../output/playwright/meeting-live-follow-1440.png" });

  // The page is the only thing that scrolls: the transcript is not a window inside it.
  const transcript = page.getByRole("region", { name: "Transcript" });
  expect(await transcript.evaluate((element) => element.scrollHeight <= element.clientHeight)).toBe(
    true,
  );

  // Scrolling back to reread is left alone: what is said meanwhile does not pull the reader away.
  await transcript.hover();
  await page.mouse.wheel(0, -10_000);
  await expect(page.locator("#u0")).toBeInViewport();
  limit += LATER_LINES;
  await expect(page.getByText(`Đang nói câu ${limit}…`)).toBeAttached({ timeout: 15_000 });
  await expect(page.locator("#u0")).toBeInViewport();

  // One press leads back to what is being said, and the following picks up again.
  const newest = page.getByRole("button", { name: "Mới nhất" });
  await newest.click();
  await expect(page.getByText(`Đang nói câu ${limit}…`)).toBeInViewport();
  await expect(newest).toBeHidden();
  limit += LATER_LINES;
  await expect(page.getByText(`Đang nói câu ${limit}…`)).toBeInViewport({ timeout: 15_000 });

  // So does scrolling back to the end by hand.
  await page.mouse.wheel(0, -10_000);
  await expect(newest).toBeVisible();
  await page.mouse.wheel(0, 100_000);
  await expect(newest).toBeHidden();

  // A moment marked from the recording bar becomes a place in the timeline at once.
  await page.route(`**/api/meetings/${MEETING_ID}/bookmarks`, (route) =>
    route.fulfill({ json: [{ id: "b1", atMs: 60_000, label: "Đánh dấu 1" }] }),
  );
  await page.getByRole("button", { name: "Đánh dấu", exact: true }).click();
  await expect(
    page.getByRole("navigation", { name: "Dòng thời gian" }).getByRole("button", {
      name: /Đánh dấu 1/,
    }),
  ).toBeVisible();
});
