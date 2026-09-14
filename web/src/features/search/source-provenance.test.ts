import { describe, expect, it } from "vitest";
import { formatPages, pdfBoxRect, readSourceLocation } from "./source-provenance";

describe("readSourceLocation", () => {
  it("reads Docling pages and boxes, including table rows wrapping block provenance", () => {
    const location = readSourceLocation([
      '[{"page_no":4,"bbox":{"l":10,"t":700,"r":200,"b":650,"coord_origin":"BOTTOMLEFT"},"charspan":[0,9]}]',
      '{"source":[{"page_no":5,"bbox":{"l":1,"t":2,"r":3,"b":4,"coord_origin":"TOPLEFT"}}],"tableRow":2}',
      '[{"page_no":4}]',
    ]);
    expect(location.pages).toEqual([4, 5]);
    expect(location.boxes).toEqual([
      { page: 4, left: 10, top: 700, right: 200, bottom: 650, origin: "BOTTOMLEFT" },
      { page: 5, left: 1, top: 2, right: 3, bottom: 4, origin: "TOPLEFT" },
    ]);
    expect(location.table).toBe(true);
    expect(readSourceLocation(['[{"page_no":4}]']).table).toBe(false);
  });

  it("reads spreadsheet sheet names and ignores malformed or unrelated provenance", () => {
    const location = readSourceLocation([
      "not json",
      '{"sheetIndex":1,"sheetName":" Doanh thu "}',
      '{"tabId":"t.0","section":"body","startIndex":1}',
      '[{"page_no":-1},{"page_no":"2"},{"page_no":3,"bbox":{"l":"x"}}]',
    ]);
    expect(location).toEqual({ pages: [3], sheet: "Doanh thu", boxes: [], table: false });
  });
});

describe("formatPages", () => {
  it("compacts ranges and lists", () => {
    expect(formatPages([])).toBeUndefined();
    expect(formatPages([4])).toBe("4");
    expect(formatPages([4, 5, 6])).toBe("4–6");
    expect(formatPages([2, 7])).toBe("2, 7");
    expect(formatPages([1, 3, 5, 9])).toBe("1, 3, 5…");
  });
});

describe("pdfBoxRect", () => {
  const letter = { view: [0, 0, 612, 792], rotate: 0 };

  it("flips bottom-left boxes onto the rendered page with a small outline margin", () => {
    const rect = pdfBoxRect(
      { page: 1, left: 72, top: 700, right: 540, bottom: 640, origin: "BOTTOMLEFT" },
      letter,
      306,
    );
    expect(rect).toEqual({
      top: 92 * 0.5 - 2,
      left: 72 * 0.5 - 2,
      width: 468 * 0.5 + 4,
      height: 60 * 0.5 + 4,
    });
  });

  it("keeps top-left boxes and skips rotated pages or boxes outside the page", () => {
    expect(
      pdfBoxRect(
        { page: 1, left: 0, top: 100, right: 612, bottom: 150, origin: "TOPLEFT" },
        letter,
        612,
      ),
    ).toEqual({ top: 98, left: -2, width: 616, height: 54 });
    expect(
      pdfBoxRect(
        { page: 1, left: 0, top: 10, right: 5, bottom: 0, origin: "BOTTOMLEFT" },
        { ...letter, rotate: 90 },
        612,
      ),
    ).toBeUndefined();
    expect(
      pdfBoxRect(
        { page: 1, left: 900, top: 10, right: 950, bottom: 0, origin: "BOTTOMLEFT" },
        letter,
        612,
      ),
    ).toBeUndefined();
  });
});
