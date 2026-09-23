import { describe, expect, it } from "vitest";
import { matchingProvenance, readSourceLocation } from "./source-provenance";

describe("matchingProvenance", () => {
  const chunks = [
    { ordinal: 8, provenanceJson: "eight" },
    { ordinal: 9, provenanceJson: "nine" },
    { ordinal: 10, provenanceJson: "ten" },
  ];

  it("leads with the chunk that matched, because that is the passage the reader was shown", () => {
    expect(matchingProvenance(chunks, 10)).toEqual(["ten", "eight", "nine"]);
  });

  it("keeps the recorded order when the matching chunk is not among them", () => {
    expect(matchingProvenance(chunks, 42)).toEqual(["eight", "nine", "ten"]);
  });
});

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

  it("draws a cited table once however many of its rows record it", () => {
    // Every row of a Docling table wraps that table's own box, so a cited table of 40 rows records the
    // same region 40 times. Drawing each one stacks 40 translucent highlights until the page reads as a
    // solid block instead of as the document it is pointing at.
    const row = (n: number) =>
      `{"source":[{"page_no":1,"bbox":{"l":10,"t":700,"r":500,"b":100,"coord_origin":"BOTTOMLEFT"}}],"tableRow":${n}}`;
    const location = readSourceLocation(Array.from({ length: 40 }, (_, index) => row(index + 1)));

    expect(location.pages).toEqual([1]);
    expect(location.boxes).toHaveLength(1);
    expect(location.table).toBe(true);
  });

  it("keeps the distinct regions a section's passages record", () => {
    const location = readSourceLocation([
      '[{"page_no":1,"bbox":{"l":72,"t":694,"r":341,"b":675,"coord_origin":"BOTTOMLEFT"}}]',
      '[{"page_no":1,"bbox":{"l":72,"t":634,"r":349,"b":615,"coord_origin":"BOTTOMLEFT"}}]',
    ]);

    expect(location.boxes).toHaveLength(2);
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

  it("reads the sheet row a workbook citation sits on, so the reader can mark that row", () => {
    const location = readSourceLocation([
      '{"source":{"sheetIndex":0,"sheetName":"Sheet1","visibility":"VISIBLE"},"tableRow":226}',
    ]);
    expect(location.sheet).toBe("Sheet1");
    expect(location.row).toBe(226);
    // A row without a sheet belongs to a document table, which is located by its text, not by a row number.
    expect(readSourceLocation(['{"source":[{"page_no":5}],"tableRow":2}']).row).toBeUndefined();
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
