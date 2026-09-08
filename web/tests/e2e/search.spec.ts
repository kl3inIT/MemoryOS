import { expect, test } from "@playwright/test";

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
    content: `${passage.content}\n${nextPassage.content}`,
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
        tenant: { displayName: "Tasco", role: "MEMBER" },
        capabilities: [],
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
        candidateLimit: 500,
        results: [
          {
            documentId,
            generation,
            title: request.page === 0 ? "HR-2026 Quy định nghỉ phép" : "Quy định bổ sung",
            mediaType: "application/pdf",
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
  await page.goto("/");
  await page.getByRole("textbox", { name: "Search documents" }).fill("chính sách nghỉ phép");
  await page.getByLabel("File type").selectOption("application/pdf");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await expect(page.getByRole("button", { name: "HR-2026 Quy định nghỉ phép" })).toBeVisible();
  await expect(page.getByText(sections[0].content, { exact: true })).toBeVisible();
  await expect(page.getByText("Passages 3–4", { exact: true })).toBeVisible();
  await expect(page.getByText("Passage 41", { exact: true })).toBeVisible();
  expect(requests[0]).toMatchObject({
    query: "chính sách nghỉ phép",
    mediaTypes: ["application/pdf"],
    page: 0,
  });
  await page.getByRole("button", { name: "HR-2026 Quy định nghỉ phép" }).click();
  await expect(page.getByRole("region", { name: "Document passages" })).toContainText("Passage 3");
  await expect(page.getByRole("region", { name: "Document passages" })).toContainText(
    nextPassage.content,
  );
  expect(previewOffsets).toEqual([2]);
  await page.getByRole("button", { name: "Read passage 41 in document" }).click();
  await expect(page.getByRole("region", { name: "Document passages" })).toContainText("Passage 41");
  expect(previewOffsets).toEqual([2, 39]);
  await page.getByRole("button", { name: "Read passages 3–4 in document" }).click();
  await expect(page.getByRole("region", { name: "Document passages" })).toContainText("Passage 3");
  await page.getByRole("button", { name: "Close document" }).click();
  await page.getByRole("button", { name: "Next", exact: true }).click();
  await expect(page.getByRole("button", { name: "Quy định bổ sung" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Next", exact: true })).toBeDisabled();
});

test("handles unavailable, retry, empty and a newer query overtaking an older one", async ({
  page,
}) => {
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
          candidateLimit: 500,
          results:
            request.query === "slow"
              ? [{ documentId, generation, title: "Old result", sections }]
              : [],
        },
      })
      .catch(() => {});
  });
  await page.goto("/");
  const input = page.getByRole("textbox", { name: "Search documents" });
  const search = page.getByRole("button", { name: "Search", exact: true });
  await input.fill("test");
  await search.click();
  await expect(page.getByRole("alert")).toContainText("Search is temporarily unavailable");
  await page.getByRole("button", { name: "Try again" }).click();
  await expect(page.getByRole("heading", { name: "No matching documents" })).toBeVisible();
  await input.fill("slow");
  await search.click();
  await expect(page.getByRole("status")).toContainText("Searching");
  await input.fill("newer");
  await search.click();
  await expect(page.getByRole("heading", { name: "No matching documents" })).toBeVisible();
  await page.waitForTimeout(900);
  await expect(page.getByRole("button", { name: "Old result" })).toHaveCount(0);
});
