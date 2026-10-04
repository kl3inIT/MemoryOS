import { expect, test, type Page } from "@playwright/test";
import type {
  ChatLibraryEntry,
  ChatLibraryFile,
  ChatLibrarySource,
} from "../../src/lib/hey-api/types.gen.ts";
import { expectNoSeriousA11yViolations } from "./axe";

const me = "e62a621f-41d2-4853-aa76-b600dafd8e34";
const source = "70000000-0000-4000-8000-000000000050";

function identity(capabilities: string[]) {
  return {
    actorId: me,
    authorizationVersion: 1,
    uiLanguage: "vi",
    tenant: { displayName: "Tasco", role: "MEMBER" },
    capabilities: ["SYSTEM_BASIC", "CHAT_READ", "CHAT_WRITE", ...capabilities],
    scopedCapabilities: [],
  };
}

function entry(overrides: Partial<ChatLibraryEntry>): ChatLibraryEntry {
  return {
    kind: "UPLOAD",
    id: "70000000-0000-4000-8000-000000000001",
    name: "bao-cao-quy-3.xlsx",
    mediaType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    sizeBytes: 20_480,
    category: "SPREADSHEET",
    at: "2026-09-20T08:00:00Z",
    owned: true,
    starred: false,
    openedAt: "2026-09-24T08:00:00Z",
    ownerName: null,
    reason: { kind: "OWNER", names: [] },
    sessionId: null,
    sessionTitle: null,
    messageId: null,
    meeting: null,
    agents: [],
    document: null,
    ...overrides,
  };
}

const upload = entry({});
const meeting = entry({
  kind: "MEETING",
  id: "70000000-0000-4000-8000-000000000002",
  name: "Giao ban tuần 38",
  mediaType: null,
  sizeBytes: null,
  owned: false,
  ownerName: "Trần Thu Hà",
  reason: { kind: "GROUP_SHARE", names: ["Kế toán"] },
  meeting: { status: "ENDED", minutesReady: true, hasTranscript: true, durationMs: 42 * 60_000 },
});
const agentFile = entry({
  kind: "AGENT_FILE",
  id: "70000000-0000-4000-8000-000000000003",
  name: "noi-quy-lao-dong.pdf",
  mediaType: "application/pdf",
  category: "DOCUMENT",
  owned: false,
  ownerName: "Nguyễn Văn An",
  reason: { kind: "AGENT", names: ["Trợ lý nhân sự"] },
  agents: [{ id: "70000000-0000-4000-8000-000000000030", name: "Trợ lý nhân sự" }],
});
const document = (index: number, generation: string | null = String(index)) =>
  entry({
    kind: "DOCUMENT",
    id: `70000000-0000-4000-8000-00000000010${index}`,
    name: `quy-trinh-${index}.pdf`,
    mediaType: "application/pdf",
    category: "DOCUMENT",
    owned: false,
    reason: { kind: "PUBLIC_SOURCE", names: [] },
    document: {
      generation,
      title: `Quy trình ${index}`,
      sourceId: source,
      sourceName: "Nhân sự",
      sourceType: "FILE",
      providerUrl: null,
    },
  });

/** A Source the member reads from: the whole of Nhân sự, and Kế toán through their Group while it is paused. */
const readable: ChatLibrarySource[] = [
  {
    id: source,
    name: "Nhân sự",
    type: "FILE",
    access: "PUBLIC",
    status: "ACTIVE",
    readableDocuments: 3,
    lastSucceededAt: "2026-09-20T08:00:00Z",
    groups: [],
    managerName: null,
  },
  {
    id: "70000000-0000-4000-8000-000000000051",
    name: "Phòng Kế toán",
    type: "SHAREPOINT",
    access: "PRIVATE",
    status: "PAUSED",
    readableDocuments: 12,
    lastSucceededAt: "2026-09-26T07:05:00Z",
    groups: ["Kế toán"],
    managerName: "Trần Thu Hà",
  },
];

/** The library's routes, with the requests the page sent to the ones a test asserts on. */
async function mockLibrary(page: Page, capabilities: string[] = [], files: ChatLibraryFile[] = []) {
  const sent = { stars: [] as string[], documentCursors: [] as (string | null)[] };
  const entryPage = (items: ChatLibraryEntry[]) => ({
    items,
    totalCount: items.length,
    hasMore: false,
  });
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({ json: identity(capabilities) }),
  );
  await page.route(
    (url) => url.pathname.startsWith("/api/chat/library"),
    (route) => {
      const request = route.request();
      const url = new URL(request.url());
      const path = url.pathname.replace("/api/chat/library", "");
      if (/^\/entries\/[A-Z_]+\/[0-9a-f-]+\/star$/.test(path)) {
        sent.stars.push(`${request.method()} ${path}`);
        return route.fulfill({ status: 204 });
      }
      if (path.endsWith("/opened")) return route.fulfill({ status: 204 });
      switch (path) {
        case "":
          return route.fulfill({
            json: {
              items: files,
              totalCount: files.length,
              totalBytes: files.reduce((sum, file) => sum + file.sizeBytes, 0),
              hasMore: false,
            },
          });
        case "/usage":
          return route.fulfill({
            json: { usedBytes: 0, fileCount: 0, limitBytes: null, byCategory: [] },
          });
        case "/trash":
          return route.fulfill({ json: { days: 30 } });
        case "/recent":
          return route.fulfill({ json: [upload, meeting, agentFile, document(1)] });
        case "/shared":
          return route.fulfill({ json: entryPage([meeting, agentFile]) });
        case "/starred":
          return route.fulfill({ json: entryPage([]) });
        case "/sources":
          return route.fulfill({ json: readable });
        case "/documents/sources":
          return route.fulfill({ json: [{ id: source, name: "Nhân sự", type: "FILE" }] });
        case "/documents": {
          const cursor = url.searchParams.get("cursor");
          sent.documentCursors.push(cursor);
          return route.fulfill({
            json: cursor
              ? { items: [document(3, null)], nextCursor: null }
              : { items: [document(1), document(2)], nextCursor: "next-2" },
          });
        }
        default:
          return route.fulfill({ status: 404, json: {} });
      }
    },
  );
  return sent;
}

const tabs = (page: Page) => page.getByRole("tablist", { name: "Phần của thư viện" });
const row = (page: Page, name: string) => page.getByRole("listitem").filter({ hasText: name });

async function openLibrary(page: Page) {
  await page.goto("/library");
  // The e2e dev server compiles the library route on first use.
  await expect(tabs(page)).toBeVisible({ timeout: 30_000 });
}

test("offers the organisation's Sources and documents only to a person who may search them", async ({
  page,
}) => {
  await mockLibrary(page);
  await openLibrary(page);
  for (const section of ["Gần đây", "Của tôi", "Được chia sẻ", "Có gắn sao"])
    await expect(tabs(page).getByRole("tab", { name: section })).toBeVisible();
  // The person's own files, what is arriving and the trash are one tab with a choice of view inside it.
  for (const view of ["Đang xử lý", "Thùng rác"])
    await expect(
      page.getByRole("radiogroup", { name: "Của tôi" }).getByRole("radio", { name: view }),
    ).toBeVisible();
  await expect(tabs(page).getByRole("tab", { name: "Tổ chức" })).toHaveCount(0);

  const reader = await page.context().newPage();
  await mockLibrary(reader, ["SEARCH_READ"]);
  await openLibrary(reader);
  await tabs(reader).getByRole("tab", { name: "Tổ chức" }).click();
  const organisation = reader.getByRole("radiogroup", { name: "Tổ chức" });
  await expect(organisation.getByRole("radio", { name: "Nguồn dữ liệu" })).toBeChecked();
  await expect(organisation.getByRole("radio", { name: "Tài liệu" })).toBeVisible();
});

test("lists the Sources a member reads from and opens one's documents", async ({ page }) => {
  await mockLibrary(page, ["SEARCH_READ"]);
  await page.goto("/library?view=sources");
  const accounting = page.getByRole("row").filter({ hasText: "Phòng Kế toán" });
  await expect(accounting).toBeVisible({ timeout: 30_000 });

  // The Sources page's list without its management: status, access and what this member may read.
  await expect(accounting).toContainText("Đã tạm dừng");
  await expect(accounting).toContainText("Một số nhóm cụ thể");
  await expect(accounting).toContainText("Kế toán");
  await expect(accounting).toContainText("Người phụ trách: Trần Thu Hà");
  await expect(accounting.getByRole("link")).toHaveCount(0);
  await expect(page.getByRole("link", { name: /^Quản lý/ })).toHaveCount(0);
  await expectNoSeriousA11yViolations(page);

  await page.getByRole("link", { name: "Nhân sự" }).click();
  await expect(page).toHaveURL(/[?&]view=documents(&|$)/);
  await expect(page).toHaveURL(new RegExp(`[?&]sourceId=${source}`));
  await expect(row(page, "quy-trinh-1.pdf")).toBeVisible();
});

test("says why each shared row is visible, and stars one", async ({ page }) => {
  const sent = await mockLibrary(page);
  await openLibrary(page);
  await tabs(page).getByRole("tab", { name: "Được chia sẻ" }).click();

  await expect(row(page, "Giao ban tuần 38")).toContainText("Qua nhóm Kế toán");
  await expect(row(page, "Giao ban tuần 38")).toContainText("Đã kết thúc");
  await expect(row(page, "noi-quy-lao-dong.pdf")).toContainText("Qua trợ lý Trợ lý nhân sự");
  await expectNoSeriousA11yViolations(page);
  // The view lives in the library's address, so a reload opens it again.
  await expect(page).toHaveURL(/[?&]view=shared(&|$)/);
  await page.reload();
  await expect(row(page, "noi-quy-lao-dong.pdf")).toContainText("Qua trợ lý Trợ lý nhân sự");

  await row(page, "noi-quy-lao-dong.pdf").hover();
  await page.getByRole("button", { name: "Gắn sao noi-quy-lao-dong.pdf" }).click();
  await expect.poll(() => sent.stars).toEqual([`PUT /entries/AGENT_FILE/${agentFile.id}/star`]);
});

test("lists every kind the person opened last in Gần đây", async ({ page }) => {
  await mockLibrary(page, ["SEARCH_READ"]);
  await openLibrary(page);
  await tabs(page).getByRole("tab", { name: "Gần đây" }).click();

  await expect(row(page, "bao-cao-quy-3.xlsx")).toContainText("Đã tải lên");
  await expect(row(page, "Giao ban tuần 38")).toContainText("Cuộc họp");
  await expect(row(page, "noi-quy-lao-dong.pdf")).toContainText("Tệp của trợ lý");
  await expect(row(page, "quy-trinh-1.pdf")).toContainText("Nhân sự · Công khai trong tổ chức");
});

test("pages the organisation's documents with Tải thêm", async ({ page }) => {
  const sent = await mockLibrary(page, ["SEARCH_READ"]);
  await openLibrary(page);
  await tabs(page).getByRole("tab", { name: "Tổ chức" }).click();
  await page
    .getByRole("radiogroup", { name: "Tổ chức" })
    .getByRole("radio", { name: "Tài liệu" })
    .click();

  await expect(row(page, "quy-trinh-2.pdf")).toBeVisible();
  await expect(row(page, "quy-trinh-3.pdf")).toHaveCount(0);
  await page.getByRole("button", { name: "Tải thêm" }).click();

  // The next page continues the first, and a document Search does not serve yet cannot be opened.
  await expect(row(page, "quy-trinh-1.pdf")).toBeVisible();
  await expect(row(page, "quy-trinh-3.pdf")).toContainText("Đang lập chỉ mục");
  await expectNoSeriousA11yViolations(page);
  await expect(page.getByRole("button", { name: "Tải thêm" })).toHaveCount(0);
  expect(sent.documentCursors).toEqual([null, "next-2"]);
});

test("a phone row keeps a long name to one line that shows its start and its end", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const filename = "bao-cao-tai-chinh-quy-3-2026.xlsx";
  await mockLibrary(
    page,
    [],
    [
      {
        source: "UPLOAD",
        id: "70000000-0000-4000-8000-000000000201",
        filename,
        mediaType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        sizeBytes: 482_304,
        createdAt: "2026-10-03T02:00:00Z",
        category: "SPREADSHEET",
        sessionId: null,
        sessionTitle: null,
        messageId: null,
        favorite: false,
        status: "READY",
        errorCode: null,
        deletedAt: null,
        purgeAfter: null,
        usedBy: [],
        deletable: true,
      },
    ],
  );
  await openLibrary(page);
  await tabs(page).getByRole("tab", { name: "Của tôi" }).click();
  // The middle is what gives way: the end, which tells one version from another, stays on screen.
  const end = row(page, filename).getByText("-2026.xlsx", { exact: true });
  await expect(end).toBeVisible();
  const start = row(page, filename).getByText("bao-cao-tai-chinh-quy-3", { exact: true });
  await expect(start).toBeVisible();
  expect((await start.boundingBox())?.y).toBe((await end.boundingBox())?.y);
  // A screen reader still reads the whole name; starring and downloading are in the row's menu.
  await expect(page.getByRole("button", { name: filename, exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: `Tải về ${filename}` })).toBeHidden();
  await expect(page.getByRole("button", { name: `Gắn sao ${filename}` })).toBeHidden();
  await page.getByRole("button", { name: `Thao tác với ${filename}` }).click();
  await expect(page.getByRole("menuitem", { name: "Thêm sao" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Tải về" })).toBeVisible();
  await page.screenshot({ path: "../output/playwright/library-row-phone.png" });
});

test.describe("on a touch screen", () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

  test("a tab and a view choice are tall enough for a finger", async ({ page }) => {
    await mockLibrary(page);
    await openLibrary(page);
    const mine = tabs(page).getByRole("tab", { name: "Của tôi" });
    await mine.click();
    for (const target of [mine, page.getByRole("radio", { name: /^Thùng rác/ })]) {
      await expect(target).toBeVisible();
      expect((await target.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    }
  });
});
