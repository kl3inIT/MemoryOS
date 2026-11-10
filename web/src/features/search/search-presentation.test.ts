import { describe, expect, it } from "vitest";
import {
  createSearchSnippet,
  queryTerms,
  sectionMatchKind,
  sectionPlace,
} from "./search-presentation";

const marks = (snippet: ReturnType<typeof createSearchSnippet>) =>
  snippet.parts.filter((part) => part.mark).map((part) => `${part.mark}:${part.text}`);

describe("search presentation", () => {
  it("centers a bounded snippet on the exact phrase and highlights it", () => {
    const snippet = createSearchSnippet(
      `${"Mở đầu tài liệu. ".repeat(30)}năm ngày làm việc${" Nội dung sau. ".repeat(30)}`,
      "policy.md",
      "năm ngày làm việc",
      120,
    );

    expect(snippet.text.startsWith("…")).toBe(true);
    expect(snippet.text.endsWith("…")).toBe(true);
    expect(snippet.text).toContain("năm ngày làm việc");
    expect(marks(snippet)).toEqual(["phrase:năm ngày làm việc"]);
  });

  it("marks only the whole question where the passage holds it, and its words alone otherwise", () => {
    const query = "nghỉ phép";
    expect(
      marks(createSearchSnippet("Được nghỉ phép năm, báo trước ngày nghỉ.", "a.pdf", query)),
    ).toEqual(["phrase:nghỉ phép"]);
    expect(marks(createSearchSnippet("Số ngày phép còn lại.", "a.pdf", query))).toEqual([
      "word:phép",
    ]);
  });

  it("marks a question typed without accents, as the index matched it", () => {
    expect(marks(createSearchSnippet("Được nghỉ phép năm.", "a.pdf", "nghi phep"))).toEqual([
      "phrase:nghỉ phép",
    ]);
  });

  it("shows neither header line of any chunk the section joins", () => {
    const snippet = createSearchSnippet(
      "Title: a.pdf\nSection: Chương 2 > Điều 4\nĐược nghỉ phép năm.\nTitle: a.pdf\nSection: Điều 5\nGửi đơn trước.",
      "a.pdf",
      "nghỉ phép",
    );
    expect(snippet.text).toBe("Được nghỉ phép năm.\nGửi đơn trước.");
  });
  it("handles identifiers, regex characters and Unicode without constructing HTML", () => {
    const snippet = createSearchSnippet(
      "Mã SP-ORION-042 có biểu thức (A+B)* và dữ liệu 😀 an toàn. <script>alert(1)</script>",
      "report.md",
      "(A+B)* SP-ORION-042",
    );

    // The whole question is not in the passage, so its identifier is marked as a word.
    expect(marks(snippet)).toEqual(["word:SP-ORION-042"]);
    expect(snippet.text).toContain("😀");
    expect(snippet.text).toContain("<script>alert(1)</script>");
  });

  it("does not invent highlights for a semantic-only result", () => {
    const snippet = createSearchSnippet(
      "Quy trình hoàn ứng phải hoàn thành trong vòng năm ngày làm việc.",
      "policy.md",
      "thời hạn nộp chứng từ công tác",
    );

    expect(marks(snippet)).toEqual([]);
  });
  it("returns a useful fallback when a generated-only chunk has no content", () => {
    expect(createSearchSnippet("Title: empty.md", "empty.md", "empty")).toEqual({
      text: "No preview text available.",
      parts: [],
    });
  });
});

describe("queryTerms", () => {
  it("keeps the whole question and its distinct words of two characters or more", () => {
    expect(queryTerms("  Nghỉ phép a nghỉ SP-ORION-042 ")).toEqual({
      phrase: "Nghỉ phép a nghỉ SP-ORION-042",
      words: ["Nghỉ", "phép", "SP-ORION-042"],
    });
  });

  it("keeps a word pasted with combining marks whole", () => {
    expect(queryTerms("nghỉ phép".normalize("NFD"))).toEqual({
      phrase: "nghỉ phép",
      words: ["nghỉ", "phép"],
    });
  });
});

describe("sectionMatchKind", () => {
  it("reads the passage without its header lines", () => {
    // The title holds the whole question; the passage itself only one of its words.
    const content = "Title: Quy chế nghỉ phép.pdf\nSố ngày phép còn lại.";
    expect(sectionMatchKind(content, "Quy chế nghỉ phép.pdf", "nghỉ phép")).toBe("partial");
    expect(sectionMatchKind("Vắng mặt có lý do.", "a.pdf", "nghỉ phép")).toBe("none");
    expect(sectionMatchKind("Được nghỉ phép.", "a.pdf", "nghỉ phép")).toBe("exact");
  });
});

describe("sectionPlace", () => {
  const section = (
    provenance: Array<{ ordinal: number; provenanceJson: string }>,
    content = "",
  ) => ({
    startOrdinal: 1,
    endOrdinal: 2,
    matchingOrdinal: 2,
    score: 0.9,
    content,
    provenance,
  });

  it("reads the page of the matching chunk and the last heading of its trail", () => {
    const place = sectionPlace(
      section(
        [
          { ordinal: 1, provenanceJson: '[{"page_no":1}]' },
          { ordinal: 2, provenanceJson: '[{"page_no":3}]' },
        ],
        "Title: a.pdf\nSection: Chương 2 > Điều 3\nPhạm vi.\nTitle: a.pdf\nSection: Chương 2 > Điều 4. Nghỉ phép năm\nĐược nghỉ phép.",
      ),
    );
    // The second chunk matched: its heading, not the first chunk's.
    expect(place).toEqual({ page: 3, sheet: undefined, heading: "Điều 4. Nghỉ phép năm" });
  });

  it("names no heading when the section does not split into one part a chunk", () => {
    const place = sectionPlace(
      section(
        [{ ordinal: 2, provenanceJson: '[{"page_no":3}]' }],
        "Title: a.pdf\nSection: Điều 3\nPhạm vi và được nghỉ phép.",
      ),
    );
    expect(place).toEqual({ page: 3, sheet: undefined, heading: undefined });
  });

  it("keeps the section's provenance when it records nothing for the matching chunk", () => {
    const place = sectionPlace(section([{ ordinal: 1, provenanceJson: '[{"page_no":4}]' }]));
    expect(place.page).toBe(4);
  });

  it("reads a sheet and leaves out a heading that only repeats it", () => {
    const place = sectionPlace(
      section(
        [{ ordinal: 2, provenanceJson: '{"sheetName":"Tổng hợp"}' }],
        "Title: a.xlsx\nSection: Tổng hợp\nPhòng Kỹ thuật: 96 ngày.",
      ),
    );
    expect(place).toEqual({ page: undefined, sheet: "Tổng hợp", heading: undefined });
  });
});
