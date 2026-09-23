import { expect, test, type Page } from "@playwright/test";
import type {
  CurrentIdentity,
  MeetingDetail,
  MeetingSummary,
} from "../../src/lib/hey-api/types.gen";

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

const now = Date.now();
const earlier: MeetingSummary[] = [
  {
    id: "5a8f1c52-4b8e-4a3c-9d36-0f1d8f2d8a11",
    title: "Họp dự án Vinaconex 9 Tower 3",
    kind: "ONLINE",
    status: "ENDED",
    owned: true,
    participants: 3,
    durationMs: 2_268_000,
    createdAt: new Date(now - 3 * 86_400_000).toISOString(),
    endedAt: new Date(now - 3 * 86_400_000 + 2_268_000).toISOString(),
  },
  {
    id: "7c2e9b10-1d2f-4e6a-8b5c-3a9d0e4f7b22",
    title: "Review OKR quý 3 · Khối Kinh doanh",
    kind: "IN_PERSON",
    status: "ENDED",
    owned: true,
    participants: 7,
    durationMs: 4_320_000,
    createdAt: new Date(now - 12 * 86_400_000).toISOString(),
    endedAt: new Date(now - 12 * 86_400_000 + 4_320_000).toISOString(),
  },
];

const MEETING_ID = "0f6b3c1e-9a7d-4d5e-8c2b-6e1f4a9b3d77";

async function mockMeetings(page: Page) {
  let meeting: MeetingDetail | undefined;
  const audio = { bytes: 0, ended: false, offset: "" };
  const exported: { heading?: Record<string, unknown> } = {};
  const uploaded: { request?: Record<string, unknown>; bytes?: number } = {};
  const shared: { request?: { members: string[]; groups: string[] } } = {};
  await page.route("**/api/identity/me", (route) => route.fulfill({ json: member }));
  await page.route("**/api/chat/sessions?*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/chat/projects?*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/meetings", async (route) => {
    if (route.request().method() === "POST") {
      expect(route.request().headers()["x-memoryos-csrf"]).toBe("1");
      const body = route.request().postDataJSON() as {
        title: string;
        participants: string[];
        terms: string[];
      };
      meeting = {
        id: MEETING_ID,
        title: body.title,
        kind: "IN_PERSON",
        language: "vi",
        participants: body.participants,
        terms: body.terms,
        notes: "",
        status: "RECORDING",
        provider: null,
        diarized: false,
        createdAt: new Date().toISOString(),
        endedAt: null,
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
      };
      await route.fulfill({ status: 201, json: meeting });
      return;
    }
    const current: MeetingSummary[] = meeting
      ? [
          {
            id: meeting.id,
            title: meeting.title,
            kind: meeting.kind,
            status: meeting.status,
            owned: true,
            participants: meeting.participants.length,
            durationMs: 0,
            createdAt: meeting.createdAt,
            endedAt: meeting.endedAt,
          },
        ]
      : [];
    await route.fulfill({ json: [...current, ...earlier] });
  });
  await page.route(`**/api/meetings/${MEETING_ID}`, (route) => route.fulfill({ json: meeting }));
  await page.route(`**/api/meetings/${MEETING_ID}/utterances/*/star`, async (route) => {
    const line = new URL(route.request().url()).pathname.split("/").at(-2)!;
    const starred = route.request().method() === "PUT" ? [line] : [];
    meeting = { ...meeting!, starred };
    await route.fulfill({ json: meeting });
  });
  await page.route(`**/api/meetings/${MEETING_ID}/bookmarks`, async (route) => {
    const body = route.request().postDataJSON() as { atMs: number };
    meeting = {
      ...meeting!,
      bookmarks: [
        ...meeting!.bookmarks,
        { id: "b1", atMs: body.atMs, label: `Đánh dấu ${meeting!.bookmarks.length + 1}` },
      ],
    };
    await route.fulfill({ json: meeting });
  });
  await page.route(`**/api/meetings/${MEETING_ID}/corrections`, (route) =>
    route.fulfill({
      json:
        route.request().method() === "POST"
          ? { runId: "r2", corrections: [PROPOSAL] }
          : [
              {
                id: "c1",
                utteranceId: "u2",
                runId: "r1",
                start: 59,
                end: 70,
                before: "Vinaconex 9",
                after: "Vinaconex 09",
                reason: "Mã dự án đọc rõ ở câu sau là 09.",
                confidence: 0.82,
                contextFit: 0.74,
                meaningSafe: 0.96,
                matchedGlossary: true,
                status: "PENDING",
              },
              {
                id: "c2",
                utteranceId: "u1",
                runId: "r1",
                start: 26,
                end: 31,
                before: "quý 4",
                after: "quý IV",
                reason: "Văn bản của công ty viết số La Mã.",
                confidence: 0.71,
                contextFit: 0.8,
                meaningSafe: 0.99,
                matchedGlossary: false,
                status: "ACCEPTED",
              },
            ],
    }),
  );
  await page.route(`**/api/meetings/${MEETING_ID}/tickets`, (route) =>
    route.fulfill({
      json: { ticket: "synthetic-ticket", expiresAt: new Date(Date.now() + 60_000).toISOString() },
    }),
  );
  const written: { body?: unknown } = {};
  await page.route(`**/api/meetings/${MEETING_ID}/utterances/u2/corrections`, async (route) => {
    const body = route.request().postDataJSON() as { start: number; end: number; text: string };
    written.body = body;
    meeting = {
      ...meeting!,
      utterances: meeting!.utterances.map((utterance) =>
        utterance.id === "u2"
          ? {
              ...utterance,
              text:
                utterance.text.slice(0, body.start) + body.text + utterance.text.slice(body.end),
              spans: [],
              editSource: "HUMAN" as const,
            }
          : utterance,
      ),
    };
    await route.fulfill({ json: meeting });
  });
  await page.route(`**/api/meetings/${MEETING_ID}/speakers/MIC/1/suggestion`, async (route) => {
    meeting = {
      ...meeting!,
      speakers: meeting!.speakers.map((speaker) =>
        speaker.label === "1" ? { ...speaker, suggestion: null } : speaker,
      ),
    };
    await route.fulfill({ json: meeting });
  });
  await page.route(`**/api/meetings/${MEETING_ID}/speakers/MIC/1`, async (route) => {
    const { name } = route.request().postDataJSON() as { name: string };
    meeting = {
      ...meeting!,
      speakers: meeting!.speakers.map((speaker) =>
        speaker.label === "1" ? { ...speaker, name, suggestion: null } : speaker,
      ),
    };
    await route.fulfill({ json: meeting });
  });
  await page.route(`**/api/meetings/${MEETING_ID}/speakers/MIC/2`, async (route) => {
    const { name } = route.request().postDataJSON() as { name: string };
    meeting = {
      ...meeting!,
      speakers: meeting!.speakers.map((speaker) =>
        speaker.label === "2" ? { ...speaker, name } : speaker,
      ),
    };
    await route.fulfill({ json: meeting });
  });
  await page.route(`**/api/meetings/${MEETING_ID}/end`, async (route) => {
    meeting = {
      ...meeting!,
      status: "ENDED",
      endedAt: new Date().toISOString(),
      // The API reads the name a voice gave itself out of the transcript and offers it to the owner.
      speakers: meeting!.speakers.map((speaker) =>
        speaker.label === "1"
          ? { ...speaker, suggestion: { name: "Thanh", utteranceId: "u1", confidence: 0.97 } }
          : speaker,
      ),
      // The API queues the minutes when a meeting ends; this fixture answers with them already written.
      minutes: {
        status: "READY",
        failure: null,
        summary: "Cuộc họp chốt ngân sách quý 4 trước thứ Năm và giao bổ sung số liệu KPI.",
        kind: "Giao ban tuần",
        generatedAt: new Date().toISOString(),
        decisions: [
          {
            id: "d1",
            text: "Chốt ngân sách quý 4 trước thứ Năm",
            owner: null,
            due: null,
            quote: "Tuần này bên mình phải chốt ngân sách quý 4 trước thứ Năm, không lùi nữa.",
            sourceUtteranceId: "u1",
            done: false,
            edited: false,
          },
        ],
        actions: [
          {
            id: "a1",
            text: "Kiểm tra lại các bảng cân đối",
            owner: "Anh Minh",
            due: "thứ Tư",
            quote: "Vậy anh Minh kiểm tra lại các bảng cân đối, xong trước thứ Tư nhé.",
            sourceUtteranceId: "u3",
            done: false,
            edited: false,
          },
        ],
        edited: false,
        topics: [topic("t1", "Ngân sách quý 4", "u1"), topic("t2", "Số liệu KPI tháng 9", "u2")],
      },
    };
    await route.fulfill({ json: meeting });
  });
  await page.route(`**/api/meetings/${MEETING_ID}/minutes/*`, async (route) => {
    const { done } = route.request().postDataJSON() as { done: boolean };
    meeting = {
      ...meeting!,
      minutes: {
        ...meeting!.minutes,
        actions: meeting!.minutes.actions.map((item) => ({ ...item, done })),
      },
    };
    await route.fulfill({ json: meeting });
  });
  await page.route("**/api/chat/persona-share-options*", (route) =>
    route.fulfill({
      json: {
        people: [
          {
            actorId: "b1f0c4a2-3e5d-4a7b-9c81-2d6f8a0e4b73",
            name: "Chị Lan",
            email: "lan@tasco.vn",
          },
          {
            actorId: "d4b8e2a6-7c19-4f35-b0d8-6e2a4c81f593",
            name: "Anh Minh",
            email: "minh@tasco.vn",
          },
        ],
        groups: [{ id: "c9d3e7f1-5a2b-4c6d-8e90-1f3a5b7c9d02", name: "Khối Tài chính" }],
      },
    }),
  );
  await page.route(`**/api/meetings/${MEETING_ID}/shares`, async (route) => {
    const body = route.request().postDataJSON() as { members: string[]; groups: string[] };
    shared.request = body;
    meeting = {
      ...meeting!,
      readers: [
        ...body.members.map((id) => ({ kind: "MEMBER" as const, id, name: "Chị Lan" })),
        ...body.groups.map((id) => ({ kind: "GROUP" as const, id, name: "Khối Tài chính" })),
      ],
    };
    await route.fulfill({ json: meeting });
  });
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
        {
          provider: "OPENAI",
          model: "whisper-1",
          diarizes: false,
          maxBytes: 26_214_400,
          selected: false,
        },
      ],
    }),
  );
  await page.route(`**/api/meetings/${MEETING_ID}/recording`, async (route) => {
    uploaded.request = route.request().postDataJSON() as Record<string, unknown>;
    meeting = {
      ...meeting!,
      status: "TRANSCRIBING",
      audio: { ...meeting!.audio, status: "WAITING" },
    };
    await route.fulfill({
      json: {
        meeting,
        method: "PUT",
        uploadUrl: "https://storage.invalid/recording",
        requiredHeaders: { "Content-Type": "audio/mpeg" },
        expiresAt: new Date(Date.now() + 300_000).toISOString(),
      },
    });
  });
  await page.route("https://storage.invalid/recording", async (route) => {
    uploaded.bytes = (route.request().postDataBuffer()?.length ?? 0) || 1;
    await route.fulfill({ status: 200, body: "" });
  });
  await page.route(`**/api/meetings/${MEETING_ID}/recording/finalize`, async (route) => {
    meeting = {
      ...meeting!,
      status: "TRANSCRIBING",
      audio: {
        ...meeting!.audio,
        status: "PENDING",
        filename: "giao-ban.mp3",
        sizeBytes: 12,
        provider: "SONIOX",
      },
    };
    await route.fulfill({ json: meeting });
  });
  await page.route(`**/api/meetings/${MEETING_ID}/minutes/export?*`, async (route) => {
    exported.heading = route.request().postDataJSON() as Record<string, unknown>;
    await route.fulfill({
      // The real endpoint answers with a Word document; the browser only has to save it.
      contentType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      headers: { "content-disposition": 'attachment; filename="bien-ban.docx"' },
      body: Buffer.from("PK"),
    });
  });
  await page.routeWebSocket(/\/api\/meeting-stream/, (socket) => {
    audio.offset = new URL(socket.url()).searchParams.get("offset") ?? "";
    socket.send(JSON.stringify({ type: "ready" }));
    let sentWords = false;
    socket.onMessage((message) => {
      if (typeof message !== "string") {
        audio.bytes += message.length;
        if (!sentWords && audio.bytes > 48_000) {
          sentWords = true;
          const utterances = [
            {
              id: "u1",
              speaker: "1",
              startMs: 2_000,
              endMs: 6_400,
              text: "Tuần này bên mình phải chốt ngân sách quý 4 trước thứ Năm, không lùi nữa.",
            },
            {
              id: "u2",
              speaker: "2",
              startMs: 7_100,
              endMs: 12_300,
              text: "Bên nhân sự đã gửi bảng KPI tháng 9, còn thiếu số liệu của Vinaconex 9 và Tower 3.",
              // Soniox was unsure of the company name; the transcript marks exactly those characters.
              spans: [{ start: 59, end: 70, confidence: 0.41 }],
            },
            {
              id: "u3",
              speaker: "1",
              startMs: 13_000,
              endMs: 16_800,
              text: "Vậy anh Minh kiểm tra lại các bảng cân đối, xong trước thứ Tư nhé.",
            },
          ];
          for (const utterance of utterances) {
            const speaker = { track: "MIC" as const, label: utterance.speaker, name: null };
            if (!meeting!.speakers.some((item) => item.label === utterance.speaker))
              meeting = { ...meeting!, speakers: [...meeting!.speakers, speaker] };
            const stored = {
              spans: [] as { start: number; end: number; confidence: number }[],
              ...utterance,
              track: "MIC" as const,
              confidence: 0.92,
            };
            meeting = { ...meeting!, utterances: [...meeting!.utterances, stored] };
            socket.send(JSON.stringify({ type: "utterance", utterance: stored }));
          }
          socket.send(
            JSON.stringify({
              type: "preview",
              track: "MIC",
              speaker: "3",
              text: "Em sẽ gửi báo giá gói MemoryOS",
            }),
          );
        }
        return;
      }
      if (JSON.parse(message).type === "end") {
        audio.ended = true;
        socket.send(JSON.stringify({ type: "finished" }));
        socket.close();
      }
    });
  });
  /** Whether the server says a correction pass holds the meeting, as a reopened page would find it. */
  const correcting = (value: boolean) => {
    meeting = { ...meeting!, correcting: value };
  };
  return { audio, exported, uploaded, shared, correcting, written };
}

test("a member uploads a recording and watches it being transcribed", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const { uploaded } = await mockMeetings(page);

  await page.goto("/meetings");
  await expect(page.getByRole("heading", { name: "Cuộc họp", level: 1 })).toBeVisible({
    timeout: 30_000,
  });
  await page.getByRole("button", { name: "Tải file ghi âm" }).click();
  const dialog = page.getByRole("dialog", { name: "Tải file ghi âm" });
  await dialog.getByLabel("File ghi âm").setInputFiles({
    name: "giao-ban.mp3",
    mimeType: "audio/mpeg",
    buffer: Buffer.from("fake-mp3-bytes"),
  });
  await dialog.getByLabel("Tên cuộc họp").fill("Giao ban tuần · Khối Tài chính");
  await dialog.getByLabel("Thành phần").fill("Anh Thanh, Chị Lan");
  // The provider's limits are stated before the file is sent, not after it fails.
  await expect(dialog.getByText("Tách được người nói")).toBeVisible();
  await expect(dialog.getByText("Tối đa 500 MB")).toBeVisible();
  // A recording made elsewhere still needs the statement that everyone in it knew.
  await expect(dialog.getByRole("button", { name: "Tải lên và nhận dạng" })).toBeDisabled();
  await dialog.getByLabel("Những người trong bản ghi đã biết buổi họp được ghi lại.").click();
  await page.screenshot({ path: "../output/playwright/meetings-upload-1440.png" });

  await dialog.getByRole("button", { name: "Tải lên và nhận dạng" }).click();
  await expect(
    page.getByRole("heading", { name: "Giao ban tuần · Khối Tài chính", level: 1 }),
  ).toBeVisible();
  await expect(page.getByText("Đang nhận dạng bản ghi giao-ban.mp3…")).toBeVisible();
  await page.screenshot({
    path: "../output/playwright/meetings-transcribing-1440.png",
    fullPage: true,
  });

  expect(uploaded.request).toMatchObject({
    filename: "giao-ban.mp3",
    mediaType: "audio/mpeg",
    sizeBytes: 14,
    provider: "SONIOX",
  });
  expect(String(uploaded.request?.sha256)).toMatch(/^[0-9a-f]{64}$/);
  expect(uploaded.bytes).toBeGreaterThan(0);
  // A recording is not recording: the meeting offers no stop control while the provider reads it.
  await expect(page.getByRole("button", { name: "Dừng", exact: true })).toHaveCount(0);
});

for (const width of [1440, 390]) {
  test(`a member records an in-person meeting, names a speaker and ends it at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    const { audio, exported, shared, correcting, written } = await mockMeetings(page);

    await page.goto("/meetings");
    await expect(page.getByRole("heading", { name: "Cuộc họp", level: 1 })).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText("Họp dự án Vinaconex 9 Tower 3")).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.screenshot({
      path: `../output/playwright/meetings-list-${width}.png`,
      fullPage: true,
    });

    await page.getByRole("button", { name: "Ghi cuộc họp mới" }).click();
    const dialog = page.getByRole("dialog", { name: "Ghi cuộc họp mới" });
    await dialog.getByLabel("Tên cuộc họp").fill("Giao ban tuần · Khối Tài chính");
    await dialog.getByLabel("Thành phần").fill("Anh Thanh, Chị Lan, Anh Minh");
    await dialog.getByLabel("Thuật ngữ riêng").fill("Tasco, Vinaconex 9, OKR, KPI");
    await dialog.getByPlaceholder("Thêm người hoặc Group").fill("Lan");
    await dialog.getByRole("option", { name: /Chị Lan/ }).click();
    await expect(
      dialog.getByRole("list", { name: "Đã chia sẻ với" }).getByText("Chị Lan"),
    ).toBeVisible();
    await dialog.getByText("Họp trực tiếp", { exact: true }).click();
    await expect(dialog.getByRole("button", { name: "Bắt đầu ghi" })).toBeDisabled();
    await dialog.getByLabel("Tôi đã thông báo cho mọi người rằng buổi họp được ghi lại.").check();
    expect(
      await dialog.evaluate((element) => {
        const bounds = element.getBoundingClientRect();
        return bounds.left >= 0 && bounds.right <= window.innerWidth;
      }),
    ).toBe(true);
    await page.screenshot({ path: `../output/playwright/meetings-new-${width}.png` });
    await dialog.getByRole("button", { name: "Bắt đầu ghi" }).click();

    await expect(
      page.getByRole("heading", { name: "Giao ban tuần · Khối Tài chính", level: 1 }),
    ).toBeVisible();
    await expect(page.getByRole("timer")).toBeVisible();
    await expect(
      page.getByText("Bên nhân sự đã gửi bảng KPI tháng 9", { exact: false }),
    ).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("Em sẽ gửi báo giá gói MemoryOS…")).toBeVisible();
    expect(audio.offset).toBe("0");
    await page.screenshot({
      path: `../output/playwright/meetings-live-${width}.png`,
      fullPage: true,
    });

    await page.getByRole("button", { name: "Đặt tên cho Người nói 2" }).click();
    // The meeting is already shared with Chị Lan, so her chip carries a button of her name too.
    await page.getByRole("button", { name: "Chị Lan", exact: true }).click();
    await expect(page.getByRole("button", { name: "Đặt tên cho Chị Lan" })).toBeVisible();

    await page.getByRole("button", { name: "Dừng", exact: true }).click();
    const confirm = page.getByRole("alertdialog");
    await confirm.getByRole("button", { name: "Dừng và kết thúc" }).click();
    await expect(page.getByRole("button", { name: "Xoá cuộc họp" })).toBeVisible();
    // The minutes open on their own tab once they are written.
    await expect(
      page.getByText("Cuộc họp chốt ngân sách quý 4 trước thứ Năm", { exact: false }),
    ).toBeVisible();
    await page.screenshot({
      path: `../output/playwright/meetings-minutes-${width}.png`,
      fullPage: true,
    });
    await page.getByRole("tab", { name: /Việc cần làm/ }).click();
    const action = page.getByRole("tabpanel").getByRole("listitem").first();
    await expect(action.getByText("Kiểm tra lại các bảng cân đối", { exact: true })).toBeVisible();
    await expect(action.getByText("Anh Minh", { exact: true })).toBeVisible();
    await expect(action.getByText("thứ Tư", { exact: true })).toBeVisible();
    await expect(
      action.getByText("Vậy anh Minh kiểm tra lại các bảng cân đối", { exact: false }),
    ).toBeVisible();
    // The tick is stored before it shows, so the assertion waits rather than check() asserting at once.
    await page.getByRole("checkbox", { name: /Đánh dấu xong/ }).click();
    await expect(page.getByRole("checkbox", { name: /Đánh dấu xong/ })).toBeChecked();
    await page.getByRole("tab", { name: /Quyết định/ }).click();
    await expect(
      page.getByRole("tabpanel").getByText("Chốt ngân sách quý 4 trước thứ Năm", { exact: true }),
    ).toBeVisible();
    await page.getByRole("tab", { name: /Việc cần làm/ }).click();
    await page.screenshot({
      path: `../output/playwright/meetings-actions-${width}.png`,
      fullPage: true,
    });
    await page.getByRole("tab", { name: "Tóm tắt" }).click();
    await expect(page.getByRole("button", { name: "Mở trong Chat" })).toBeVisible();
    await page.getByPlaceholder("Thêm người hoặc Group").fill("Khối");
    await page.getByRole("option", { name: /Khối Tài chính/ }).click();
    await expect(
      page.getByRole("list", { name: "Đã chia sẻ với" }).getByText("Khối Tài chính"),
    ).toBeVisible();
    expect(shared.request?.groups).toEqual(["c9d3e7f1-5a2b-4c6d-8e90-1f3a5b7c9d02"]);
    await page.screenshot({
      path: `../output/playwright/meetings-sharing-${width}.png`,
      fullPage: true,
    });
    await page.getByRole("button", { name: "Xuất biên bản" }).click();
    const bienBan = page.getByRole("dialog", { name: "Xuất biên bản" });
    // The heading the transcript cannot know is the owner's; the meeting fills the rest.
    await expect(bienBan.getByLabel("Về việc")).toHaveValue("Giao ban tuần · Khối Tài chính");
    await expect(bienBan.getByLabel("Bắt đầu")).toHaveValue(
      /^\d{2} giờ \d{2} ngày \d+ tháng \d+ năm \d{4}$/,
    );
    await bienBan.getByLabel("Cơ quan, tổ chức").fill("CÔNG TY CỔ PHẦN TASCO");
    await bienBan.getByLabel("Địa điểm").fill("Phòng họp A, Hà Nội");
    await bienBan.getByLabel("Chủ trì", { exact: true }).fill("Nguyễn Văn An");
    await page.screenshot({ path: `../output/playwright/meetings-export-${width}.png` });
    await expect(bienBan.getByLabel("Phông chữ")).toHaveValue("Times New Roman");
    await bienBan.getByLabel("Phông chữ").selectOption("Arial");
    const download = page.waitForEvent("download");
    await bienBan.getByRole("button", { name: "Tải Word" }).click();
    expect((await download).suggestedFilename()).toBe("bien-ban-giao-ban-tuan-khoi-tai-chinh.docx");
    expect(exported.heading).toMatchObject({
      organization: "CÔNG TY CỔ PHẦN TASCO",
      place: "Phòng họp A, Hà Nội",
      chair: "Nguyễn Văn An",
      attendees: ["Anh Thanh", "Chị Lan", "Anh Minh"],
      font: "Arial",
    });
    await expect(bienBan).toHaveCount(0);

    // What the model would change, and what it already changed, both live above the transcript.
    await page.getByRole("tab", { name: "Transcript" }).click();
    // The timeline is a table of contents: each subject jumps to the line it began on.
    const timeline = page.getByRole("navigation", { name: "Dòng thời gian" });
    await expect(timeline.getByRole("button", { name: /Ngân sách quý 4/ })).toBeVisible();
    await timeline.getByRole("button", { name: /Số liệu KPI tháng 9/ }).click();
    await expect(page.locator("#u2")).toBeInViewport();

    // Searching the transcript, and keeping one line for later.
    const find = page.getByRole("textbox", { name: "Tìm trong transcript" });
    await find.fill("KPI");
    await expect(page.getByText("1/1")).toBeVisible();
    await find.fill("");

    await page
      .getByRole("listitem")
      .filter({ hasText: "Bên nhân sự đã gửi bảng KPI" })
      .getByRole("button", { name: "Đánh dấu câu này" })
      .click();
    await page.getByRole("button", { name: "Câu đã đánh dấu (1)" }).click();
    await expect(
      page.locator('ol[aria-live="polite"]').getByText("Tuần này bên mình phải chốt"),
    ).toHaveCount(0);
    await page.getByRole("button", { name: "Câu đã đánh dấu (1)" }).click();

    // The name a voice gave itself is offered once, with the line it said it in, and renames only when pressed.
    await expect(page.getByText("Người nói 1 tự giới thiệu là Thanh")).toBeVisible();
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: `../output/playwright/meetings-speaker-names-${width}.png`,
      fullPage: true,
    });
    await page.getByRole("button", { name: "Đặt tên Thanh" }).click();
    await expect(page.getByText("Người nói 1 tự giới thiệu là Thanh")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Đặt tên cho Thanh" }).first()).toBeVisible();

    await expect(page.getByText("Mã dự án đọc rõ ở câu sau là 09.")).toBeVisible();
    // The button says what it does to what: one stretch was marked unclear on this transcript.
    await page.getByRole("button", { name: "Hiệu chỉnh 1 đoạn khó nghe" }).click();
    await expect(
      page.getByRole("status").filter({ hasText: "Tìm được 1 chỗ cần sửa." }),
    ).toBeVisible();
    // A pass started before the page was reopened is still running on the server: the button stays shut.
    correcting(true);
    await page.reload();
    await page.getByRole("tab", { name: "Transcript" }).click();
    await expect(page.getByRole("button", { name: "Đang hiệu chỉnh…" })).toBeDisabled();
    await page.screenshot({ path: `../output/playwright/meetings-correcting-${width}.png` });
    expect(
      await page.evaluate(() => window.scrollX),
      "choosing a tab never scrolls the whole page sideways",
    ).toBe(0);
    correcting(false);
    await expect(page.getByRole("button", { name: "Hiệu chỉnh 1 đoạn khó nghe" })).toBeEnabled({
      timeout: 10_000,
    });
    await expect(page.getByRole("button", { name: "Nhận", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Hoàn tác", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Hoàn tác cả lượt" })).toBeVisible();
    await page.screenshot({
      path: `../output/playwright/meetings-corrections-${width}.png`,
      fullPage: true,
    });

    // The owner opens a word the provider was unsure of and writes what was said.
    await page.getByRole("button", { name: "Sửa “Vinaconex 9”" }).click();
    const word = page.getByRole("textbox", { name: "Từ đúng" });
    await expect(word).toHaveValue("Vinaconex 9");
    await page.screenshot({ path: `../output/playwright/meetings-word-${width}.png` });
    await word.fill("Vinaconex 09");
    await word.press("Enter");
    expect(written.body).toEqual({ start: 59, end: 70, text: "Vinaconex 09" });
    await expect(
      page
        .locator('ol[aria-live="polite"]')
        .getByText("còn thiếu số liệu của Vinaconex 09 và Tower 3."),
    ).toBeVisible();

    expect(audio.ended).toBe(true);
    await expect(page.getByRole("timer")).toHaveCount(0);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.screenshot({
      path: `../output/playwright/meetings-ended-${width}.png`,
      fullPage: true,
    });
  });
}

function topic(id: string, text: string, sourceUtteranceId: string) {
  return {
    id,
    text,
    owner: null,
    due: null,
    quote: null,
    sourceUtteranceId,
    done: false,
    edited: false,
  };
}

/** A proposal one pass would answer, for the pass the flow starts itself. */
const PROPOSAL = {
  id: "c3",
  utteranceId: "u2",
  runId: "r2",
  start: 59,
  end: 70,
  before: "Vinaconex 9",
  after: "Vinaconex 09",
  reason: "Mã dự án đọc rõ ở câu sau là 09.",
  confidence: 0.82,
  contextFit: 0.74,
  meaningSafe: 0.96,
  matchedGlossary: true,
  status: "PENDING",
};
