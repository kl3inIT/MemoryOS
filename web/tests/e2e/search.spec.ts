import { readFileSync } from "node:fs";
import { expect, test } from "./deployment-policy";
import {
  fulfillPdfRange,
  rangedBytes,
  rangedHandbookPdf,
  readsBytes,
  scannedReportPdf,
} from "../fixtures/ranged-pdf";

const documentId = "73835d74-d386-4b4e-b392-ad7f81e3b55a";
const generation = "6b780b3a-de22-4307-ace9-6c2f44e22fc1";
const passage = {
  ordinal: 2,
  content: "Mỗi nhân viên có 12 ngày nghỉ phép. <script>alert('x')</script>",
  provenanceJson: "[]",
};
const nextPassage = {
  ordinal: 3,
  content: "Đăng ký nghỉ với quản lý trước ba ngày.",
  provenanceJson: "[]",
};
const sections = [
  {
    startOrdinal: 2,
    endOrdinal: 3,
    matchingOrdinal: 3,
    score: 0.8,
    content: `Title: HR-2026 Quy định nghỉ phép\n${"Thông tin nội bộ và hướng dẫn thực hiện. ".repeat(12)}${passage.content}\n${nextPassage.content}`,
    provenance: [
      { ordinal: 2, provenanceJson: "[]" },
      { ordinal: 3, provenanceJson: "[]" },
    ],
  },
  {
    startOrdinal: 40,
    endOrdinal: 40,
    matchingOrdinal: 40,
    score: 0.7,
    content: "Ngày phép còn lại được đối soát cuối năm.",
    provenance: [{ ordinal: 40, provenanceJson: "[]" }],
  },
];

test.beforeEach(async ({ page }) => {
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
        authorizationVersion: 1,
        uiLanguage: "en",
        tenant: { displayName: "Tasco", role: "MEMBER" },
        capabilities: [
          "SYSTEM_BASIC",
          "SEARCH_READ",
          "CHAT_READ",
          "CHAT_WRITE",
          "IMAGE_GENERATE",
          "LLM_GATEWAY_USE",
        ],
        scopedCapabilities: [],
      },
    }),
  );
});

test("searches merged sections, filters, pages and opens each best match with escaped text", async ({
  page,
}) => {
  const requests: unknown[] = [];
  const previewOffsets: number[] = [];
  await page.route("**/api/search", async (route) => {
    const request = route.request().postDataJSON();
    requests.push(request);
    expect(route.request().headers()["x-memoryos-csrf"]).toBe("1");
    await route.fulfill({
      json: {
        page: request.page,
        hasMore: request.page === 0,
        totalResults: 11,
        candidateLimit: 500,
        sourceFacets: { total: 11, types: [{ type: "FILE", count: 11 }] },
        results: [
          {
            documentId,
            generation,
            title: request.page === 0 ? "HR-2026 Quy định nghỉ phép" : "Quy định bổ sung",
            mediaType: "application/pdf",
            sourceTypes: ["FILE"],
            authors: [],
            providerUrl: null,
            updatedAt: "2026-09-08T00:00:00Z",
            score: 0.8,
            sections,
          },
        ],
      },
    });
  });
  await page.route("**/api/search/documents/*?*", (route) => {
    const url = new URL(route.request().url());
    expect(url.searchParams.get("generation")).toBe(generation);
    const from = Number(url.searchParams.get("from"));
    previewOffsets.push(from);
    return route.fulfill({
      json: {
        documentId,
        generation,
        title: "HR-2026 Quy định nghỉ phép",
        passages:
          from === 2
            ? [passage, nextPassage]
            : [{ ...passage, ordinal: 40, content: sections[1].content }],
        firstOrdinal: from,
        totalChunks: 41,
        hasMore: false,
      },
    });
  });
  await page.goto("/search");
  await page.getByRole("textbox", { name: "Search documents" }).fill("chính sách nghỉ phép");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await page.getByRole("button", { name: "File type: All file types" }).click();
  await page.getByRole("menuitemradio", { name: "PDF", exact: true }).click();
  const titleButton = page.getByRole("button", {
    name: "HR-2026 Quy định nghỉ phép",
    exact: true,
  });
  const resultCard = page.locator("article").filter({ has: titleButton });
  await expect(titleButton).toBeVisible();
  await expect(page.getByText(sections[0].content, { exact: true })).toHaveCount(0);
  await expect(page.getByText("Title: HR-2026 Quy định nghỉ phép", { exact: true })).toHaveCount(0);
  await expect(resultCard.getByText("PDF", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "11 results for “chính sách nghỉ phép”" }),
  ).toBeVisible();
  // The source rail counts the candidates the API reports per connector.
  await expect(page.getByRole("button", { name: "All sources: 11 results" })).toBeVisible();
  await expect(page.getByRole("button", { name: "File type: PDF" })).toBeVisible();
  await expect(page.getByText("application/pdf", { exact: true })).toHaveCount(0);
  await expect(
    resultCard.getByRole("button", { name: "Open best match in HR-2026 Quy định nghỉ phép" }),
  ).toBeVisible();
  await expect(
    resultCard.getByRole("button", { name: "Open related match 2 in HR-2026 Quy định nghỉ phép" }),
  ).toBeVisible();
  await expect(page.locator("mark").filter({ hasText: "nghỉ" }).first()).toBeVisible();
  await expect(page.locator("script").filter({ hasText: "alert('x')" })).toHaveCount(0);
  await expect(resultCard.getByText(/<script>alert\('x'\)<\/script>/)).toBeVisible();
  expect(requests[0]).toMatchObject({
    query: "chính sách nghỉ phép",
    mediaTypes: [],
    page: 0,
  });
  expect(requests[1]).toMatchObject({ mediaTypes: ["application/pdf"], page: 0 });
  // A PDF match opens on the stored original; the passages stay reachable from the rail.
  const originalPdf = rangedHandbookPdf();
  await page.route("**/api/search/documents/*/original?*", (route) =>
    fulfillPdfRange(route, originalPdf),
  );
  await titleButton.click();
  const reader = page.getByRole("dialog", { name: "HR-2026 Quy định nghỉ phép" });
  const rail = reader.getByRole("complementary", { name: "2 best-matching passages" });
  await expect(rail).toBeVisible();
  await reader.getByRole("button", { name: "All passages" }).click();
  const selected = reader.getByRole("article", { name: "Selected passage" });
  await expect(selected).toContainText(nextPassage.content);
  // Every match's passage window is read up front, so the rail can quote each citation.
  expect(previewOffsets).toEqual([2, 39]);
  await rail.getByRole("button", { name: /Ngày phép còn lại/ }).click();
  await expect(selected).toContainText(sections[1].content);
  await rail.getByRole("button", { name: /Đăng ký nghỉ với quản lý/ }).click();
  await expect(selected).toContainText(nextPassage.content);
  // Both windows are read up front; choosing a match re-reads its own window, never a shared one.
  expect(previewOffsets).toEqual([2, 39, 39, 2]);
  await page.keyboard.press("Escape");
  await expect(reader).toHaveCount(0);
  await expect(titleButton).toBeFocused();
  const relatedMatchButton = page.getByRole("button", {
    name: "Open related match 2 in HR-2026 Quy định nghỉ phép",
  });
  await relatedMatchButton.click();
  await expect(reader).toContainText(sections[1].content);
  await page.getByRole("button", { name: "Close document preview" }).click();
  await expect(relatedMatchButton).toBeFocused();
  await page.getByRole("button", { name: "Clear filters" }).click();
  await expect(page.getByRole("button", { name: "File type: All file types" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Clear filters" })).toHaveCount(0);
  await expect(titleButton).toBeVisible();
  const pages = page.getByRole("navigation", { name: "Search results pages" });
  await expect(pages).toContainText("1 / 2");
  await page.getByRole("button", { name: "Next", exact: true }).click();
  await expect(page.getByRole("button", { name: "Quy định bổ sung", exact: true })).toBeVisible();
  await expect(pages).toContainText("2 / 2");
  await expect(page.getByRole("button", { name: "Next", exact: true })).toBeDisabled();
});

test("picks a range of update days on the calendar beside the presets", async ({ page }) => {
  await page.clock.setFixedTime(new Date("2026-10-02T09:00:00+07:00"));
  await page.route("**/api/identity/me", (route) =>
    route.fulfill({
      json: {
        actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
        authorizationVersion: 1,
        uiLanguage: "vi",
        tenant: { displayName: "Tasco", role: "MEMBER" },
        capabilities: ["SYSTEM_BASIC", "SEARCH_READ", "CHAT_READ", "CHAT_WRITE"],
        scopedCapabilities: [],
      },
    }),
  );
  const requests: { updatedFrom?: string; updatedTo?: string }[] = [];
  const result = (
    id: string,
    title: string,
    mediaType: string,
    updatedAt: string,
    type: string,
  ) => ({
    documentId: id,
    generation,
    title,
    mediaType,
    sourceTypes: [type],
    authors: ["Phòng Tài chính"],
    providerUrl: null,
    updatedAt,
    score: 0.8,
    sections: [
      {
        startOrdinal: 0,
        endOrdinal: 0,
        matchingOrdinal: 0,
        score: 0.8,
        content: "Doanh thu quý III tăng 12% so với cùng kỳ, chủ yếu nhờ mảng logistics.",
        provenance: [{ ordinal: 0, provenanceJson: "[]" }],
      },
    ],
  });
  await page.route("**/api/search", async (route) => {
    requests.push(route.request().postDataJSON());
    await route.fulfill({
      json: {
        page: 0,
        hasMore: false,
        totalResults: 2,
        candidateLimit: 500,
        sourceFacets: {
          total: 2,
          types: [
            { type: "GOOGLE_DRIVE", count: 1 },
            { type: "SHAREPOINT", count: 1 },
          ],
        },
        results: [
          result(
            documentId,
            "Báo cáo tài chính quý III 2026",
            "application/pdf",
            "2026-09-17T03:00:00Z",
            "SHAREPOINT",
          ),
          result(
            "2f1c6a37-5b2e-4f43-9d55-0b7e1c9a4d10",
            "Kế hoạch ngân sách 2027",
            "application/vnd.google-apps.spreadsheet",
            "2026-09-05T08:30:00Z",
            "GOOGLE_DRIVE",
          ),
        ],
      },
    });
  });
  await page.goto("/search?q=b%C3%A1o%20c%C3%A1o%20doanh%20thu");
  await page.getByRole("button", { name: "Đã cập nhật: Mọi thời điểm" }).click();
  const apply = page.getByRole("button", { name: "Áp dụng" });
  await expect(apply).toBeDisabled();
  // A day after today cannot be picked.
  await expect(page.locator('td[data-day="2026-10-05"] button')).toBeDisabled();
  await page.locator('td[data-day="2026-09-03"]:not([data-outside]) button').click();
  await page.locator('td[data-day="2026-09-18"]:not([data-outside]) button').click();
  await expect(page.locator('[data-slot="popover-content"]')).toHaveCSS("opacity", "1");
  // The picked day eases into its colours over 150 ms; capture what the person then sees.
  await page.waitForTimeout(250);
  await page.screenshot({ path: "test-results/screens/search-updated-range-desktop.png" });
  await apply.click();

  await expect(page).toHaveURL(/from=2026-09-03&to=2026-09-18/);
  await expect(
    page.getByRole("button", { name: "Đã cập nhật: 03/09/2026 – 18/09/2026" }),
  ).toBeVisible();
  expect(requests.at(-1)).toMatchObject({
    updatedFrom: "2026-09-03T00:00:00.000Z",
    updatedTo: "2026-09-18T23:59:59.999Z",
  });
  await page.screenshot({ path: "test-results/screens/search-updated-range-applied.png" });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "Đã cập nhật: 03/09/2026 – 18/09/2026" }).click();
  await expect(page.locator('td[data-day="2026-09-03"]:not([data-outside])')).toHaveAttribute(
    "aria-selected",
    "true",
  );
  await expect(page.locator('[data-slot="popover-content"]')).toHaveCSS("opacity", "1");
  await page.screenshot({ path: "test-results/screens/search-updated-range-mobile.png" });
  await page.getByRole("button", { name: "7 ngày qua" }).click();
  await expect(page).toHaveURL(/time=7d/);
  await expect(page).not.toHaveURL(/from=/);
});

test("opens the pages of an uploaded PDF that a MemoryOS MCP document link names", async ({
  page,
}) => {
  const originalPdf = rangedHandbookPdf();
  // Claude and ChatGPT link an upload to /search?doc=<id>; the read names the original's media type.
  await page.route(
    (url) => url.pathname === `/api/search/documents/${documentId}`,
    (route) =>
      route.fulfill({
        json: {
          documentId,
          generation,
          title: "Sổ tay nhân sự 2026.pdf",
          mediaType: "application/pdf",
          passages: [{ ordinal: 0, content: "Chương 1. Quy định chung.", provenanceJson: "[]" }],
          firstOrdinal: 0,
          totalChunks: 40,
          hasMore: true,
        },
      }),
  );
  await page.route("**/api/search/documents/*/original?*", (route) =>
    fulfillPdfRange(route, originalPdf),
  );
  await page.goto(`/search?doc=${documentId}`);

  const dialog = page.getByRole("dialog");
  await expect(dialog.locator('[data-slot="pdf-page"][data-page="1"]')).toHaveAttribute(
    "data-rendered",
    "true",
  );
  await expect(dialog.getByText("/ 12")).toBeVisible();
  await expect(dialog.locator('[data-slot="pdf-page"]')).toHaveCount(12);
  await page.screenshot({ path: "test-results/screens/doc-link-pdf-desktop.png" });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(dialog.locator('[data-slot="pdf-page"][data-page="1"]')).toHaveAttribute(
    "data-rendered",
    "true",
  );
  await page.screenshot({ path: "test-results/screens/doc-link-pdf-mobile.png" });
});

test("handles unavailable, retry, empty and a pending search", async ({ page }) => {
  let failures = 1;
  await page.route("**/api/search", async (route) => {
    const request = route.request().postDataJSON();
    if (failures-- > 0) {
      await route.fulfill({ status: 503, json: { code: "SEARCH_UNAVAILABLE" } });
      return;
    }
    if (request.query === "slow") await new Promise((resolve) => setTimeout(resolve, 800));
    await route
      .fulfill({
        json: {
          page: 0,
          hasMore: false,
          totalResults: request.query === "slow" ? 1 : 0,
          candidateLimit: 500,
          sourceFacets: { total: request.query === "slow" ? 1 : 0, types: [] },
          results:
            request.query === "slow"
              ? [
                  {
                    documentId,
                    generation,
                    title: "Old result",
                    mediaType: "application/pdf",
                    sourceTypes: [],
                    authors: [],
                    providerUrl: null,
                    updatedAt: "2026-09-08T00:00:00Z",
                    score: 0.8,
                    sections,
                  },
                ]
              : [],
        },
      })
      .catch(() => {});
  });
  await page.goto("/search");
  const input = page.getByRole("textbox", { name: "Search documents" });
  const search = page.getByRole("button", { name: "Search", exact: true });
  await input.fill("test");
  await search.click();
  await expect(page.getByRole("alert")).toContainText("Search is temporarily unavailable");
  await page.getByRole("button", { name: "Try again" }).click();
  await expect(page.getByRole("heading", { name: "No matching documents" })).toBeVisible();
  await input.fill("slow");
  await search.click();
  // Scope to the page: the sidebar has its own status ("No conversations yet.") on an empty history.
  await expect(page.getByRole("main").getByRole("status")).toContainText("Searching");
  await expect(page.getByRole("button", { name: "Cancel" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Search is loading" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Old result", exact: true })).toBeVisible();
});

test("keeps the document preview usable inside a mobile viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route("**/api/search", (route) =>
    route.fulfill({
      json: {
        page: 0,
        hasMore: false,
        totalResults: 1,
        candidateLimit: 500,
        sourceFacets: { total: 1, types: [] },
        results: [
          {
            documentId,
            generation,
            title: "HR-2026 Quy định nghỉ phép",
            mediaType: "application/pdf",
            sourceTypes: [],
            authors: [],
            providerUrl: null,
            updatedAt: "2026-09-08T00:00:00Z",
            score: 0.8,
            sections,
          },
        ],
      },
    }),
  );
  await page.route("**/api/search/documents/*?*", (route) =>
    route.fulfill({
      json: {
        documentId,
        generation,
        title: "HR-2026 Quy định nghỉ phép",
        passages: [passage, nextPassage],
        firstOrdinal: 2,
        totalChunks: 41,
        hasMore: false,
      },
    }),
  );

  await page.goto("/search");
  await page.getByRole("textbox", { name: "Search documents" }).fill("nghỉ phép");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await page.getByRole("button", { name: "HR-2026 Quy định nghỉ phép", exact: true }).click();

  const dialog = page.getByRole("dialog", { name: "HR-2026 Quy định nghỉ phép" });
  await expect(dialog).toBeVisible();
  const bounds = await dialog.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.y).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390);
  expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(844);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await expect
    .poll(() => dialog.evaluate((element) => element.contains(document.activeElement)))
    .toBe(true);
  await page.keyboard.press("Shift+Tab");
  await expect
    .poll(() => dialog.evaluate((element) => element.contains(document.activeElement)))
    .toBe(true);
});

test("shows source type, provider and authors, links to Google Drive and outlines the matched PDF region", async ({
  page,
}) => {
  // Search opens PDF matches on their pages; this match is also a table row.
  const box =
    '{"source":[{"page_no":7,"bbox":{"l":72,"t":694,"r":341,"b":675,"coord_origin":"BOTTOMLEFT"}}],"tableRow":2}';
  const located = [{ ...sections[0], provenance: [{ ordinal: 2, provenanceJson: box }] }];
  const providerUrl = "https://drive.google.com/open?id=1AbCdEfGhIjKlMnOp";
  await page.route("**/api/search", (route) =>
    route.fulfill({
      json: {
        page: 0,
        hasMore: false,
        totalResults: 3,
        candidateLimit: 500,
        sourceFacets: {
          total: 3,
          types: [
            { type: "GOOGLE_DRIVE", count: 2 },
            { type: "FILE", count: 1 },
          ],
        },
        results: [
          {
            documentId,
            generation,
            title: "HR-2026 Quy định nghỉ phép",
            mediaType: "application/pdf",
            updatedAt: "2026-09-08T00:00:00Z",
            score: 0.8,
            sections: located,
            sourceTypes: ["GOOGLE_DRIVE"],
            authors: ["Alice", "Bình", "Chi"],
            providerUrl,
          },
          {
            documentId: "6f1d2c3b-4a5e-4f60-8a7b-9c0d1e2f3a41",
            generation: "7a2e3d4c-5b6f-4071-9b8c-0d1e2f3a4b52",
            title: "Bảng theo dõi ngày phép",
            mediaType: "application/vnd.google-apps.spreadsheet",
            updatedAt: "2026-09-08T00:00:00Z",
            score: 0.7,
            sections: [
              {
                ...sections[0],
                content:
                  "Bảng theo dõi ngày phép 2026: Alice còn 8 ngày nghỉ phép, Bình còn 5 ngày, Chi đã dùng hết.",
                provenance: [{ ordinal: 2, provenanceJson: '[{"sheetName":"2026"}]' }],
              },
            ],
            sourceTypes: ["GOOGLE_DRIVE"],
            authors: [],
            providerUrl: "https://drive.google.com/open?id=1SheetIdAbCdEfGh",
          },
          {
            documentId: "8b3f4e5d-6c70-4182-8c9d-1e2f3a4b5c63",
            generation: "9c405f6e-7d81-4293-9dae-2f3a4b5c6d74",
            title: "Quy trình nghỉ phép.docx",
            mediaType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            updatedAt: "2026-09-07T00:00:00Z",
            score: 0.6,
            sections: [
              {
                ...sections[0],
                content:
                  "Quy trình nghỉ phép: gửi đơn trên cổng nội bộ, quản lý duyệt trong hai ngày làm việc.",
                provenance: [{ ordinal: 2, provenanceJson: "[]" }],
              },
            ],
            sourceTypes: ["FILE"],
            authors: [],
            providerUrl: null,
          },
        ],
      },
    }),
  );
  await page.route("**/api/search/documents/*?*", (route) =>
    route.fulfill({
      json: {
        documentId,
        generation,
        title: "HR-2026 Quy định nghỉ phép",
        passages: [passage, nextPassage],
        firstOrdinal: 2,
        totalChunks: 41,
        hasMore: false,
      },
    }),
  );
  const originalPdf = rangedHandbookPdf();
  const originals: { url: URL; range?: string }[] = [];
  await page.route("**/api/search/documents/*/original?*", (route) => {
    originals.push({
      url: new URL(route.request().url()),
      range: route.request().headers()["range"],
    });
    return fulfillPdfRange(route, originalPdf);
  });
  await page.goto("/search");
  await page.getByRole("textbox", { name: "Search documents" }).fill("nghỉ phép");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  const titleButton = page.getByRole("button", { name: "HR-2026 Quy định nghỉ phép", exact: true });
  const card = page.locator("article").filter({ has: titleButton });
  await expect(card.locator('[data-slot="document-source-icon"]')).toHaveAttribute(
    "data-provider",
    "google_drive",
  );
  await expect(card).toContainText("Google Drive");
  await expect(card).toContainText("Alice, Bình +1");
  // Every media type on the page is selectable, not only the fixed filter set.
  await page.getByRole("button", { name: "File type: All file types" }).click();
  await expect(page.getByRole("menuitemradio", { name: "Google Sheets" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(
    page.locator("article").filter({ hasText: "Quy trình nghỉ phép.docx" }).getByRole("link"),
  ).toHaveCount(0);
  await expect(
    card.getByRole("link", { name: "Open HR-2026 Quy định nghỉ phép in Google Drive" }),
  ).toHaveAttribute("href", providerUrl);
  await titleButton.click();
  const dialog = page.getByRole("dialog");
  // The whole 12-page original opens at the cited page 7; distant pages stay unrendered placeholders.
  const citedPage = dialog.locator('[data-slot="pdf-page"][data-page="7"]');
  await expect(citedPage).toHaveAttribute("data-rendered", "true");
  await expect(dialog.locator('[data-slot="pdf-citation-box"]')).toBeInViewport();
  await expect(dialog.getByRole("spinbutton", { name: "Page number" })).toHaveValue("7");
  await expect(dialog.getByText("/ 12")).toBeVisible();
  await expect(dialog.locator('[data-slot="pdf-page"][data-page="1"]')).not.toHaveAttribute(
    "data-rendered",
  );
  await expect(dialog.locator('[data-slot="pdf-page"]')).toHaveCount(12);
  await expect(dialog.locator('[data-slot="pdf-citation-box"]')).toHaveCount(1);
  expect(new Set(originals.map(({ url }) => url.searchParams.get("generation")))).toEqual(
    new Set([generation]),
  );
  // The reader opens the file by range itself; nothing probes it whole.
  expect(originals.filter(({ range }) => !range)).toHaveLength(0);
  expect(
    rangedBytes(
      originals.map(({ range }) => range),
      originalPdf.length,
    ),
  ).toBeLessThan(originalPdf.length / 2);
  // A failing range read falls back to one whole read instead of an error.
  await dialog.getByRole("button", { name: "All passages" }).click();
  await page.unroute("**/api/search/documents/*/original?*");
  const fallbackReads: (string | undefined)[] = [];
  await page.route("**/api/search/documents/*/original?*", (route) => {
    const range = route.request().headers()["range"];
    fallbackReads.push(range);
    return range ? route.fulfill({ status: 500 }) : fulfillPdfRange(route, originalPdf);
  });
  await dialog.getByRole("button", { name: "Original file" }).click();
  await expect(dialog.locator('[data-slot="pdf-page"][data-page="7"]')).toHaveAttribute(
    "data-rendered",
    "true",
  );
  await expect(dialog.getByRole("spinbutton", { name: "Page number" })).toHaveValue("7");
  await expect(dialog.getByRole("alert")).toHaveCount(0);
  expect(fallbackReads.filter((range) => !range)).toHaveLength(1);
  await dialog.getByRole("button", { name: "All passages" }).click();
  await expect(dialog.getByRole("article", { name: "Selected passage" })).toContainText(
    nextPassage.content,
  );
});

test("opens a scanned report at its cited page without reading the rest of it", async ({
  page,
}) => {
  // A scan lists its pages flat, each a picture apart; pdf.js needs every page dictionary before it draws one.
  const box =
    '[{"page_no":13,"bbox":{"l":72,"t":694,"r":341,"b":675,"coord_origin":"BOTTOMLEFT"}}]';
  await page.route("**/api/search", (route) =>
    route.fulfill({
      json: {
        page: 0,
        hasMore: false,
        totalResults: 1,
        candidateLimit: 500,
        sourceFacets: { total: 1, types: [{ type: "FILE", count: 1 }] },
        results: [
          {
            documentId,
            generation,
            title: "Báo cáo tài chính 2023.pdf",
            mediaType: "application/pdf",
            updatedAt: "2026-09-08T00:00:00Z",
            score: 0.8,
            sections: [{ ...sections[0], provenance: [{ ordinal: 2, provenanceJson: box }] }],
            sourceTypes: ["FILE"],
            authors: [],
            providerUrl: null,
          },
        ],
      },
    }),
  );
  await page.route("**/api/search/documents/*?*", (route) =>
    route.fulfill({
      json: {
        documentId,
        generation,
        title: "Báo cáo tài chính 2023.pdf",
        mediaType: "application/pdf",
        passages: [passage, nextPassage],
        firstOrdinal: 2,
        totalChunks: 41,
        hasMore: false,
      },
    }),
  );
  const originalPdf = scannedReportPdf();
  const reads: (string | undefined)[] = [];
  await page.route("**/api/search/documents/*/original?*", (route) => {
    reads.push(route.request().headers()["range"]);
    return fulfillPdfRange(route, originalPdf);
  });
  /** No read for a while: the neighbours of the cited page and the thumbnails in view are in. */
  const settled = () =>
    expect
      .poll(
        async () => {
          const before = reads.length;
          await page.waitForTimeout(800);
          return reads.length === before;
        },
        { timeout: 30_000 },
      )
      .toBe(true);

  await page.goto("/search");
  await page.getByRole("textbox", { name: "Search documents" }).fill("báo cáo");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  const title = page.getByRole("button", { name: "Báo cáo tài chính 2023.pdf", exact: true });
  await title.click();
  const dialog = page.getByRole("dialog");
  const cited = dialog.locator('[data-slot="pdf-page"][data-page="13"]');
  await expect(cited.locator("canvas")).toBeVisible();
  await expect(dialog.locator('[data-slot="pdf-citation-box"]')).toBeInViewport();
  await expect(dialog.getByText("/ 24")).toBeVisible();
  const rail = dialog.getByRole("navigation", { name: "Pages" });
  await expect(rail.locator("canvas").first()).toBeVisible();
  await settled();

  // Every read is a range: the file is opened by its head and its tail, never probed whole.
  expect(reads.every(Boolean)).toBe(true);
  expect(reads[0]).toBe("bytes=0-131071");
  // The first page is twelve pages from the cited one. Its picture is neither drawn nor fetched.
  expect(readsBytes(reads, 150 * 1024, 280 * 1024)).toBe(false);
  expect(rangedBytes(reads, originalPdf.length)).toBeLessThan(originalPdf.length / 2);
  // The rail draws the thumbnails in view, not one for every page.
  expect(await rail.locator("canvas").count()).toBeLessThan(12);

  // Opening the document again authorizes it with one byte and reads what it kept from memory.
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  const kept = reads.length;
  await title.click();
  await expect(cited.locator("canvas")).toBeVisible();
  await settled();
  const again = reads.slice(kept);
  expect(again[0]).toBe("bytes=0-0");
  expect(rangedBytes(again, originalPdf.length)).toBeLessThan(originalPdf.length / 10);

  // A range that fails once the document is open reads it whole, and the new canvas follows the reader. The
  // first page's picture was never fetched, so drawing it is such a read.
  await page.unroute("**/api/search/documents/*/original?*");
  const later: (string | undefined)[] = [];
  await page.route("**/api/search/documents/*/original?*", (route) => {
    const range = route.request().headers()["range"];
    later.push(range);
    return range ? route.fulfill({ status: 500 }) : fulfillPdfRange(route, originalPdf);
  });
  const pageNumber = dialog.getByRole("spinbutton", { name: "Page number" });
  await pageNumber.fill("1");
  await pageNumber.press("Enter");
  await expect.poll(() => later.filter((range) => !range).length).toBe(1);
  // The whole read opens at the cited page again.
  await expect(pageNumber).toHaveValue("13");
  await pageNumber.fill("24");
  await pageNumber.press("Enter");
  await expect(dialog.locator('[data-slot="pdf-page"][data-page="24"] canvas')).toBeVisible();
  await expect(pageNumber).toHaveValue("24");
  await expect(dialog.getByRole("alert")).toHaveCount(0);
  expect(later.filter((range) => !range)).toHaveLength(1);
});

test("marks the cited region on an image original, through zoom and a turn, and only on the picture it was measured in", async ({
  page,
}) => {
  // The fixture is 854 x 534 pixels; the OCR answered [171, 107, 598, 267] in those pixels.
  const picture = readFileSync(
    new URL("../fixtures/generated-files/bieu-do-doanh-thu.png", import.meta.url),
  );
  const box = (width: number) =>
    `{"page_no":1,"bbox":{"l":171,"t":107,"r":598,"b":267,"coord_origin":"TOPLEFT","frame_width":${width},"frame_height":534}}`;
  let frameWidth = 854;
  await page.route("**/api/search", (route) =>
    route.fulfill({
      json: {
        page: 0,
        hasMore: false,
        totalResults: 1,
        candidateLimit: 500,
        sourceFacets: { total: 1, types: [{ type: "FILE", count: 1 }] },
        results: [
          {
            documentId,
            generation,
            title: "Biểu đồ doanh thu.png",
            mediaType: "image/png",
            updatedAt: "2026-09-08T00:00:00Z",
            score: 0.8,
            sections: [
              { ...sections[0], provenance: [{ ordinal: 2, provenanceJson: box(frameWidth) }] },
            ],
            sourceTypes: ["FILE"],
            authors: [],
            providerUrl: null,
          },
        ],
      },
    }),
  );
  await page.route("**/api/search/documents/*?*", (route) =>
    route.fulfill({
      json: {
        documentId,
        generation,
        title: "Biểu đồ doanh thu.png",
        mediaType: "image/png",
        passages: [passage, nextPassage],
        firstOrdinal: 2,
        totalChunks: 41,
        hasMore: false,
      },
    }),
  );
  await page.route("**/api/search/documents/*/original?*", (route) =>
    route.fulfill({ contentType: "image/png", body: picture }),
  );
  const search = async () => {
    await page.goto("/search");
    await page.getByRole("textbox", { name: "Search documents" }).fill("doanh thu");
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await page.getByRole("button", { name: "Biểu đồ doanh thu.png", exact: true }).click();
  };

  await search();
  const dialog = page.getByRole("dialog");
  const shown = dialog.getByRole("img", { name: "Biểu đồ doanh thu.png" });
  const region = dialog.locator('[data-slot="image-citation-box"]');
  await expect(region).toBeInViewport();
  await expect(dialog.getByText("Highlighted")).toBeVisible();
  /** The region's box as shares of the picture's box on screen. */
  const share = async () => {
    const [outer, inner] = [await shown.boundingBox(), await region.boundingBox()];
    if (!outer || !inner) throw new Error("the picture or its region is not laid out");
    return [
      (inner.x - outer.x) / outer.width,
      (inner.y - outer.y) / outer.height,
      inner.width / outer.width,
      inner.height / outer.height,
    ];
  };
  const close = (actual: number[], expected: number[]) =>
    actual.forEach((value, index) => expect(value).toBeCloseTo(expected[index] ?? NaN, 2));
  const [left, top, width, height] = [171 / 854, 107 / 534, 427 / 854, 160 / 534];
  close(await share(), [left, top, width, height]);

  // Zoomed, the region keeps its place on the picture.
  await dialog.getByRole("button", { name: "Zoom in" }).click();
  await expect.poll(async () => (await shown.boundingBox())?.width).toBeGreaterThan(0);
  await page.waitForTimeout(400);
  close(await share(), [left, top, width, height]);

  // Turned a quarter clockwise, what was the picture's top edge is its right edge.
  await dialog.getByRole("button", { name: "Rotate right" }).click();
  await page.waitForTimeout(400);
  close(await share(), [1 - top - height, left, height, width]);

  // A region measured in a picture of another size is not this picture's: nothing is drawn, and the rail says so.
  frameWidth = 534;
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await search();
  await expect(shown).toBeVisible();
  await expect(dialog.getByText("Not found")).toBeVisible();
  await expect(region).toHaveCount(0);
});

/** One result with a match on every tenth passage, best first. */
function matchedPassages(
  title: string,
  mediaType: string,
  matches: { content: string; provenanceJson: string }[],
) {
  return {
    page: 0,
    hasMore: false,
    totalResults: 1,
    candidateLimit: 500,
    sourceFacets: { total: 1, types: [{ type: "FILE", count: 1 }] },
    results: [
      {
        documentId,
        generation,
        title,
        mediaType,
        updatedAt: "2026-09-08T00:00:00Z",
        score: 0.9,
        sections: matches.map((match, index) => ({
          startOrdinal: index * 10,
          endOrdinal: index * 10,
          matchingOrdinal: index * 10,
          score: 0.9 - index * 0.05,
          content: match.content,
          provenance: [{ ordinal: index * 10, provenanceJson: match.provenanceJson }],
        })),
        sourceTypes: ["FILE"],
        authors: [],
        providerUrl: null,
      },
    ],
  };
}

test("steps between the cited passages of a PDF original from the reader's own toolbar", async ({
  page,
}) => {
  const region = (pageNumber: number, top: number) =>
    `[{"page_no":${pageNumber},"bbox":{"l":72,"t":${top},"r":341,"b":${top - 20},"coord_origin":"BOTTOMLEFT"}}]`;
  // Ranked best first: page 3, then two regions of page 7 a screen apart.
  const matches = [region(3, 694), region(7, 694), region(7, 120)].map((provenanceJson, index) => ({
    content: `Leave rule number ${index + 1}.`,
    provenanceJson,
  }));
  await page.route("**/api/search", (route) =>
    route.fulfill({ json: matchedPassages("Leave handbook.pdf", "application/pdf", matches) }),
  );
  // The third passage's text is held back, to arrive while the reader is somewhere else.
  let releaseThird: () => void = () => undefined;
  const thirdHeld = new Promise<void>((resolve) => {
    releaseThird = resolve;
  });
  await page.route("**/api/search/documents/*?*", async (route) => {
    const from = Number(new URL(route.request().url()).searchParams.get("from"));
    const ordinal = from === 0 ? 0 : from + 1;
    if (ordinal === 20) await thirdHeld;
    await route.fulfill({
      json: {
        documentId,
        generation,
        title: "Leave handbook.pdf",
        mediaType: "application/pdf",
        passages: [
          { ordinal, content: `Leave rule number ${ordinal / 10 + 1}.`, provenanceJson: "[]" },
        ],
        firstOrdinal: from,
        totalChunks: 60,
        hasMore: true,
      },
    });
  });
  const originalPdf = rangedHandbookPdf();
  await page.route("**/api/search/documents/*/original?*", (route) =>
    fulfillPdfRange(route, originalPdf),
  );
  await page.goto("/search");
  await page.getByRole("textbox", { name: "Search documents" }).fill("policy");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await page.getByRole("button", { name: "Open best match in Leave handbook.pdf" }).click();

  const dialog = page.getByRole("dialog");
  const pageField = dialog.getByRole("spinbutton", { name: "Page number" });
  const box = dialog.locator('[data-slot="pdf-citation-box"]');
  const toolbar = dialog.getByRole("toolbar", { name: "Document controls" });
  // One toolbar: the steps sit beside the page field and the zoom, not in a second bar over them.
  await expect(dialog.getByRole("toolbar")).toHaveCount(1);
  await expect(pageField).toHaveValue("3");
  await expect(box).toBeInViewport();
  await expect(toolbar.getByText("1 / 3")).toBeVisible();
  await expect(toolbar.getByRole("button", { name: "Previous passage" })).toBeDisabled();

  // A step moves the cited page and its region, as a rail card does.
  await toolbar.getByRole("button", { name: "Next passage" }).click();
  await expect(pageField).toHaveValue("7");
  await expect(box).toBeInViewport();
  await expect(toolbar.getByText("2 / 3")).toBeVisible();
  // The next passage is on the page already open; the reader is taken to its region all the same.
  await toolbar.getByRole("button", { name: "Next passage" }).click();
  await expect(toolbar.getByText("3 / 3")).toBeVisible();
  await expect(box).toBeInViewport();
  await expect(pageField).toHaveValue("7");
  await expect(toolbar.getByRole("button", { name: "Next passage" })).toBeDisabled();
  const canvas = dialog.locator('[data-slot="pdf-page"][data-page="7"]');
  const scrolled = () =>
    canvas.evaluate((figure) => {
      let node = figure.parentElement;
      while (node && node.scrollHeight <= node.clientHeight) node = node.parentElement;
      return node?.scrollTop ?? -1;
    });
  const atSecondRegion = await scrolled();

  // A reader who scrolled away stays there when the dialog repaints around the same passage.
  await canvas.hover();
  await page.mouse.wheel(0, -900);
  await expect.poll(scrolled).toBeLessThan(atSecondRegion - 300);
  const away = await scrolled();
  const rail = dialog.getByRole("complementary", { name: "3 best-matching passages" });
  await expect(rail.getByText("Leave rule number 3.")).toHaveCount(0);
  releaseThird();
  await expect(rail.getByText("Leave rule number 3.")).toBeVisible();
  await page.waitForTimeout(500);
  expect(await scrolled()).toBe(away);

  // Stepping back returns to the other region of the same page.
  await toolbar.getByRole("button", { name: "Previous passage" }).click();
  await expect(toolbar.getByText("2 / 3")).toBeVisible();
  await expect(box).toBeInViewport();
  expect(await scrolled()).toBeLessThan(atSecondRegion - 300);
});
