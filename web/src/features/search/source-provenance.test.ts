import { describe, expect, it } from "vitest";
import { readSourceLocation } from "./source-provenance";

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

  it("marks a table row only when its source records a page", () => {
    const empty = { pages: [], boxes: [], table: false };
    expect(readSourceLocation(['{"tableRow":2}'])).toEqual(empty);
    expect(readSourceLocation(['{"source":[{"page_no":"5"}],"tableRow":2}'])).toEqual(empty);
    expect(readSourceLocation(['{"source":[{"page_no":5}],"tableRow":"2"}'])).toEqual({
      ...empty,
      pages: [5],
    });
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
