import { expect, test } from "@playwright/test";
import { fulfillPdfRange, rangedBytes, rangedHandbookPdf } from "../fixtures/ranged-pdf";

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
        results: [
          {
            documentId,
            generation,
            title: request.page === 0 ? "HR-2026 Quy định nghỉ phép" : "Quy định bổ sung",
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
  await titleButton.click();
  const reader = page.getByRole("dialog", { name: "HR-2026 Quy định nghỉ phép" });
  await expect(reader).toContainText("Selected passage");
  await expect(reader).toContainText(nextPassage.content);
  expect(previewOffsets).toEqual([2]);
  await reader.getByRole("button", { name: "Passage 2" }).click();
  await expect(reader).toContainText(sections[1].content);
  expect(previewOffsets).toEqual([2, 39]);
  await reader.getByRole("button", { name: "Passage 1" }).click();
  await expect(reader).toContainText(nextPassage.content);
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
  // A table row: the preview opens on its PDF page.
  const box =
    '{"source":[{"page_no":7,"bbox":{"l":72,"t":694,"r":341,"b":675,"coord_origin":"BOTTOMLEFT"}}],"tableRow":2}';
  const located = [{ ...sections[0], provenance: [{ ordinal: 2, provenanceJson: box }] }];
  const providerUrl = "https://drive.google.com/open?id=1AbCdEfGhIjKlMnOp";
  await page.route("**/api/search", (route) =>
    route.fulfill({
      json: {
        page: 0,
        hasMore: false,
        totalResults: 1,
        candidateLimit: 500,
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
  await expect(
    dialog.getByRole("link", { name: "Open HR-2026 Quy định nghỉ phép in Google Drive" }),
  ).toHaveAttribute("href", providerUrl);
  await expect(dialog.getByRole("tab", { name: "PDF pages" })).toHaveAttribute(
    "aria-selected",
    "true",
  );
  // The whole 12-page original opens at the cited page 7; distant pages stay unrendered placeholders.
  const citedPage = dialog.locator('[data-slot="pdf-page"][data-page="7"]');
  await expect(citedPage).toHaveAttribute("data-rendered", "true");
  await expect(dialog.locator('[data-slot="pdf-citation-box"]')).toBeInViewport();
  await expect(dialog.getByText("Page 7 / 12")).toBeVisible();
  await expect(dialog.locator('[data-slot="pdf-page"][data-page="1"]')).not.toHaveAttribute(
    "data-rendered",
  );
  await expect(dialog.locator('[data-slot="pdf-page"]')).toHaveCount(12);
  await expect(dialog.locator('[data-slot="pdf-citation-box"]')).toHaveCount(1);
  expect(new Set(originals.map(({ url }) => url.searchParams.get("generation")))).toEqual(
    new Set([generation]),
  );
  expect(originals.filter(({ range }) => !range)).toHaveLength(1);
  expect(
    rangedBytes(
      originals.map(({ range }) => range),
      originalPdf.length,
    ),
  ).toBeLessThan(originalPdf.length / 2);
  // A failing range read falls back to one whole read instead of an error.
  await dialog.getByRole("tab", { name: "Passages" }).click();
  await page.unroute("**/api/search/documents/*/original?*");
  const fallbackReads: (string | undefined)[] = [];
  await page.route("**/api/search/documents/*/original?*", (route) => {
    const range = route.request().headers()["range"];
    fallbackReads.push(range);
    return range ? route.fulfill({ status: 500 }) : fulfillPdfRange(route, originalPdf);
  });
  await dialog.getByRole("tab", { name: "PDF pages" }).click();
  await expect(dialog.locator('[data-slot="pdf-page"][data-page="7"]')).toHaveAttribute(
    "data-rendered",
    "true",
  );
  await expect(dialog.getByText("Page 7 / 12")).toBeVisible();
  await expect(dialog.getByRole("alert")).toHaveCount(0);
  expect(fallbackReads.filter((range) => !range)).toHaveLength(2);
  await dialog.getByRole("tab", { name: "Passages" }).click();
  await expect(dialog.getByText(nextPassage.content)).toBeVisible();
});
