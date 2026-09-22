import { describe, expect, it } from "vitest";
import { locatePassage, normalizeForMatch } from "./preview-highlight";

/** What the reader would see highlighted, so the assertions read as the reader's experience. */
function highlighted(raw: string, passage: string) {
  const found = locatePassage(normalizeForMatch(raw), passage);
  return found.confidence === "none"
    ? { confidence: found.confidence as string }
    : { confidence: found.confidence as string, text: raw.slice(found.start, found.end) };
}

describe("locatePassage", () => {
  it("finds a passage the rendered document contains verbatim", () => {
    const raw = "BÁO CÁO TÀI CHÍNH\n\nDoanh thu quý 3 đạt 412 tỷ đồng.\n\n2. Chi phí\n";
    expect(highlighted(raw, "Doanh thu quý 3 đạt 412 tỷ đồng.")).toEqual({
      confidence: "exact",
      text: "Doanh thu quý 3 đạt 412 tỷ đồng.",
    });
  });

  it("matches across the line breaks the renderer introduces", () => {
    const raw = "Doanh thu quý 3\n   đạt 412 tỷ đồng.";
    expect(highlighted(raw, "Doanh thu quý 3 đạt 412 tỷ đồng.")).toEqual({
      confidence: "exact",
      text: "Doanh thu quý 3\n   đạt 412 tỷ đồng.",
    });
  });

  it("ignores the soft hyphens a justified Word paragraph carries", () => {
    const raw = "Doanh thu quý 3 đạt 412 tỷ đồ­ng.";
    expect(highlighted(raw, "Doanh thu quý 3 đạt 412 tỷ đồng.")).toEqual({
      confidence: "exact",
      text: "Doanh thu quý 3 đạt 412 tỷ đồ­ng.",
    });
  });

  it("matches a heading the document sets in capitals", () => {
    expect(highlighted("BÁO CÁO TÀI CHÍNH QUÝ 3", "Báo cáo tài chính quý 3")).toEqual({
      confidence: "exact",
      text: "BÁO CÁO TÀI CHÍNH QUÝ 3",
    });
  });

  it("matches Vietnamese written with combining marks against composed extraction text", () => {
    // The renderer emits the accents as combining marks; the extraction stored them composed.
    const raw = "Chi phí quản lý doanh nghiệp tăng 12%.";
    const found = locatePassage(normalizeForMatch(raw), "Chi phí quản lý doanh nghiệp tăng 12%.");
    expect(found.confidence).toBe("exact");
  });

  it("finds a row whose cells the extraction flattened onto one line", () => {
    const raw = "Khoản mục\tQuý 3\tQuý 2\nDoanh thu\t412\t388\nChi phí\t301\t295\n";
    expect(highlighted(raw, "Doanh thu 412 388")).toEqual({
      confidence: "exact",
      text: "Doanh thu\t412\t388",
    });
  });

  it("anchors on the ends when the rendered text carries words the extraction dropped", () => {
    const raw =
      "Doanh thu hợp nhất quý 3 năm 2026 của công ty mẹ và các công ty con đạt 412 tỷ đồng, " +
      "tăng 6,2% so với cùng kỳ năm trước theo báo cáo đã soát xét.";
    const passage =
      "Doanh thu hợp nhất quý 3 năm 2026 đạt 412 tỷ đồng, " +
      "tăng 6,2% so với cùng kỳ năm trước theo báo cáo đã soát xét.";
    const found = highlighted(raw, passage);
    expect(found.confidence).toBe("approximate");
    expect(found.text).toBe(raw);
  });

  it("refuses a passage the document does not carry", () => {
    const raw = "BÁO CÁO TÀI CHÍNH\n\nDoanh thu quý 3 đạt 412 tỷ đồng.\n";
    expect(highlighted(raw, "Lợi nhuận sau thuế đạt 87 tỷ đồng.")).toEqual({
      confidence: "none",
    });
  });

  it("refuses to span two unrelated places that happen to start and end alike", () => {
    const raw =
      "Doanh thu hợp nhất quý 3 năm 2026 của công ty mẹ được trình bày dưới đây. " +
      "Phần này mô tả chi phí bán hàng, chi phí quản lý, thuế và các khoản mục khác " +
      "trong suốt bốn quý liên tiếp của năm tài chính, kèm thuyết minh chi tiết cho từng khoản. " +
      "tăng 6,2% so với cùng kỳ năm trước theo báo cáo đã soát xét.";
    const passage =
      "Doanh thu hợp nhất quý 3 năm 2026 đạt 412 tỷ đồng, " +
      "tăng 6,2% so với cùng kỳ năm trước theo báo cáo đã soát xét.";
    expect(highlighted(raw, passage)).toEqual({ confidence: "none" });
  });

  it("refuses a passage the document repeats, because which one was cited is unknown", () => {
    const raw =
      "Khoản mục\tQuý 3\tQuý 2\nDoanh thu\t412\t388\n\n— trang 2 —\n" +
      "Khoản mục\tQuý 3\tQuý 2\nDoanh thu\t415\t390\n";
    expect(highlighted(raw, "Khoản mục Quý 3 Quý 2")).toEqual({ confidence: "none" });
    // The row that differs between the two pages is still placed.
    expect(highlighted(raw, "Doanh thu 415 390")).toEqual({
      confidence: "exact",
      text: "Doanh thu\t415\t390",
    });
  });

  it("refuses to anchor a passage whose ends are too few words to identify a place", () => {
    // Not present verbatim, and two words are far too common to say where the citation came from.
    expect(highlighted("Doanh thu quý 3 đạt 412 tỷ đồng.", "Doanh thu 412")).toEqual({
      confidence: "none",
    });
  });

  it("refuses an empty passage and an empty document", () => {
    expect(highlighted("Doanh thu quý 3.", "   ")).toEqual({ confidence: "none" });
    expect(highlighted("", "Doanh thu quý 3.")).toEqual({ confidence: "none" });
  });
});

describe("normalizeForMatch", () => {
  it("maps every normalized character back to where it started in the source", () => {
    const raw = "  Doanh   thu\nquý 3  ";
    const { text, sources } = normalizeForMatch(raw);
    expect(text).toBe("doanh thu quý 3");
    expect(sources).toHaveLength(text.length);
    expect(raw[sources[0]!]).toBe("D");
    expect(raw[sources[text.indexOf("quý")]!]).toBe("q");
    expect(raw[sources[text.length - 1]!]).toBe("3");
  });
});
