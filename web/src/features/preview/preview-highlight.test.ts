import { describe, expect, it } from "vitest";
import {
  locatePassage,
  markQuery,
  matchKind,
  normalizeForMatch,
  sentenceAround,
} from "./preview-highlight";

/** What the reader would see highlighted, so the assertions read as the reader's experience. */
function highlighted(raw: string, passage: string, section?: string) {
  const found = locatePassage(normalizeForMatch(raw), passage, section);
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

  it("uses the heading a repeated passage was read under to say which occurrence was cited", () => {
    const raw = [
      "3.3. Bao cao tinh hinh tai chinh",
      "19. LNST cua cong ty me\t130.909.072.653",
      "3.4. Bao cao ket qua hoat dong kinh doanh rieng",
      "19. LNST cua cong ty me\t130.909.072.653",
    ].join("\n");

    const found = locatePassage(
      normalizeForMatch(raw),
      "130.909.072.653",
      "3.4. Bao cao ket qua hoat dong kinh doanh rieng",
    );

    expect(found.confidence).toBe("approximate");
    // The occurrence under that heading, not the identical one above it.
    expect(found.confidence !== "none" && found.start).toBeGreaterThan(raw.indexOf("3.4."));
  });

  it("reads the heading trail from its most specific end", () => {
    const raw = [
      "PHAN 3. BAO CAO TAI CHINH RIENG",
      "3.3. Bao cao tinh hinh tai chinh",
      "19. LNST cua cong ty me\t130.909.072.653",
      "3.4. Bao cao ket qua hoat dong kinh doanh rieng",
      "19. LNST cua cong ty me\t130.909.072.653",
    ].join("\n");

    const found = locatePassage(
      normalizeForMatch(raw),
      "130.909.072.653",
      "PHAN 3. BAO CAO TAI CHINH RIENG > 3.4. Bao cao ket qua hoat dong kinh doanh rieng",
    );

    // The outermost heading would have pointed at the first occurrence; the innermost one is the citation's.
    expect(found.confidence !== "none" && found.start).toBeGreaterThan(raw.indexOf("3.4."));
  });

  it("still refuses a repeated passage whose heading does not single out one place", () => {
    const raw = "Tong cong\t412\nTong cong\t412\n";
    expect(highlighted(raw, "Tong cong 412")).toEqual({ confidence: "none" });
    // A heading the document does not carry, and one it carries twice, both say nothing.
    expect(highlighted(raw, "Tong cong 412", "3.9. Khong co")).toEqual({ confidence: "none" });
    expect(highlighted(raw, "Tong cong 412", "Tong cong")).toEqual({ confidence: "none" });
  });

  it("refuses an empty passage and an empty document", () => {
    expect(highlighted("Doanh thu quý 3.", "   ")).toEqual({ confidence: "none" });
    expect(highlighted("", "Doanh thu quý 3.")).toEqual({ confidence: "none" });
  });
});

/** What a reader would see marked, in reading order, with `*` before a mark of the whole question. */
function marked(
  raw: string,
  query: string,
  words?: string[],
  within?: { start: number; end: number },
) {
  const terms = { phrase: query, words: words ?? query.split(" ") };
  return markQuery(normalizeForMatch(raw), terms, within).map(
    (mark) => `${mark.whole ? "*" : ""}${raw.slice(mark.start, mark.end)}`,
  );
}

describe("markQuery", () => {
  it("marks only the whole question where it occurs, whatever its capitals", () => {
    expect(marked("Nghỉ phép năm: mỗi năm 12 ngày NGHỈ PHÉP, ngày nghỉ lễ.", "nghỉ phép")).toEqual([
      "*Nghỉ phép",
      "*NGHỈ PHÉP",
    ]);
  });

  it("marks the question's words, the longer first, only when the whole question occurs nowhere", () => {
    expect(
      marked("Ngày nghỉ lễ và ngày phép năm.", "nghỉ phép năm", ["phép năm", "nghỉ", "phép"]),
    ).toEqual(["nghỉ", "phép năm"]);
  });

  it("counts a one-word question as the whole question", () => {
    expect(marked("Số ngày phép còn lại.", "phép")).toEqual(["*phép"]);
  });

  it("finds a word stored with combining marks, a soft hyphen or a line break inside the phrase", () => {
    const decomposed = "nghỉ phép".normalize("NFD");
    expect(marked(`Quy định ${decomposed} năm.`, "nghỉ phép")).toEqual([`*${decomposed}`]);
    expect(marked("Quy định nghỉ­phép", "nghỉphép")).toEqual(["*nghỉ­phép"]);
    expect(marked("Quy định nghỉ\n   phép năm.", "nghỉ phép")).toEqual(["*nghỉ\n   phép"]);
  });

  it("marks whole words only", () => {
    expect(marked("Ban hành quy chế an toàn.", "an", ["an"])).toEqual(["*an"]);
    expect(marked("Ban hành quy chế.", "an", ["an"])).toEqual([]);
  });

  it("compares without diacritics only when the question was typed without any", () => {
    expect(marked("Quy định nghỉ phép năm, đơn xin.", "nghi phep")).toEqual(["*nghỉ phép"]);
    expect(marked("Đơn xin nghỉ.", "don", ["don"])).toEqual(["*Đơn"]);
    // One accent typed is a reader who means the accents: "phẹp" does not mark "phép".
    expect(marked("Quy định nghỉ phép năm.", "nghỉ phẹp", ["phẹp"])).toEqual([]);
  });

  it("stays inside the span it is given", () => {
    const raw = "năm trước. Trong năm nay có 12 ngày. năm sau";
    const start = raw.indexOf("Trong");
    const end = raw.indexOf(" năm sau");
    expect(marked(raw, "năm", ["năm"], { start, end })).toEqual(["*năm"]);
  });

  it("marks nothing for a blank question", () => {
    expect(marked("Quy định nghỉ phép.", "  ", [])).toEqual([]);
  });
});

describe("matchKind", () => {
  const kind = (raw: string, query: string) =>
    matchKind(normalizeForMatch(raw), { phrase: query, words: query.split(" ") });

  it("tells the whole question from some of its words from none", () => {
    expect(kind("Được nghỉ phép năm.", "nghỉ phép")).toBe("exact");
    expect(kind("Số ngày phép còn lại.", "nghỉ phép")).toBe("partial");
    expect(kind("Vắng mặt có lý do.", "nghỉ phép")).toBe("none");
  });
});

describe("sentenceAround", () => {
  const sentence = (
    raw: string,
    word: string,
    within?: { start: number; end: number },
    breaks?: number[],
  ) => {
    const start = raw.indexOf(word);
    const span = sentenceAround(raw, { start, end: start + word.length }, within, breaks);
    return raw.slice(span.start, span.end);
  };

  it("widens a mark to its sentence", () => {
    const raw = "Điều 4. Người lao động được nghỉ phép năm. Thời gian thử việc không tính.";
    expect(sentence(raw, "nghỉ phép")).toBe("Người lao động được nghỉ phép năm.");
  });

  it("does not end a sentence at a decimal point", () => {
    expect(sentence("Mức 12.500 đồng cho nghỉ phép.", "nghỉ phép")).toBe(
      "Mức 12.500 đồng cho nghỉ phép.",
    );
  });

  it("stops at a line break, a block start and the edges of the span", () => {
    expect(sentence("Nghỉ phép năm\nNgười lao động được nghỉ.", "được")).toBe(
      "Người lao động được nghỉ.",
    );
    const raw = "Nghỉ phép năm Người lao động được nghỉ.";
    expect(sentence(raw, "được", undefined, [raw.indexOf("Người")])).toBe(
      "Người lao động được nghỉ.",
    );
    expect(sentence(raw, "được", { start: raw.indexOf("lao"), end: raw.indexOf(" nghỉ.") })).toBe(
      "lao động được",
    );
  });

  it("cuts a run-on sentence around the mark at word boundaries", () => {
    const raw = `${"chữ ".repeat(120)}nghỉ phép ${"chữ ".repeat(120)}hết.`;
    const found = sentence(raw, "nghỉ phép");
    expect(found.length).toBeLessThanOrEqual(320);
    expect(found).toContain("nghỉ phép");
    expect(found.startsWith("chữ")).toBe(true);
    expect(found.endsWith("chữ")).toBe(true);
  });
});

describe("normalizeForMatch", () => {
  it("maps every normalized character back to where it started in the source", () => {
    const raw = "  Doanh   thu\nquý 3  ";
    const { text, sources } = normalizeForMatch(raw);
    expect(text).toBe("doanh thu quý 3");
    expect(sources).toHaveLength(text.length);
    expect(raw.charAt(sources[0] ?? -1)).toBe("D");
    expect(raw.charAt(sources[text.indexOf("quý")] ?? -1)).toBe("q");
    expect(raw.charAt(sources[text.length - 1] ?? -1)).toBe("3");
  });
});
