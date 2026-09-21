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
        },
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
  await page.route(`**/api/meetings/${MEETING_ID}/tickets`, (route) =>
    route.fulfill({
      json: { ticket: "synthetic-ticket", expiresAt: new Date(Date.now() + 60_000).toISOString() },
    }),
  );
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
          },
        ],
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
  await page.route(`**/api/meetings/${MEETING_ID}/minutes/export`, async (route) => {
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
            const stored = { ...utterance, track: "MIC" as const, confidence: 0.92 };
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
  return { audio, exported };
}

for (const width of [1440, 390]) {
  test(`a member records an in-person meeting, names a speaker and ends it at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    const { audio, exported } = await mockMeetings(page);

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
    await page.getByRole("button", { name: "Chị Lan" }).click();
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
    const download = page.waitForEvent("download");
    await bienBan.getByRole("button", { name: "Tải về" }).click();
    expect((await download).suggestedFilename()).toBe("bien-ban-giao-ban-tuan-khoi-tai-chinh.docx");
    expect(exported.heading).toMatchObject({
      organization: "CÔNG TY CỔ PHẦN TASCO",
      place: "Phòng họp A, Hà Nội",
      chair: "Nguyễn Văn An",
      attendees: ["Anh Thanh", "Chị Lan", "Anh Minh"],
    });
    await expect(bienBan).toHaveCount(0);

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
