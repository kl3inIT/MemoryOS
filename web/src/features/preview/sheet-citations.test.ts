import { describe, expect, it } from "vitest";
import { placeSheetCitations, renderedRows } from "./sheet-citations";

const sheets = [
  { name: "Tổng hợp", csv: "Khu vực,Doanh thu\nHà Nội,120\nĐà Nẵng,80\n", truncated: false },
  { name: "Chi tiết", csv: "Mã,Giá\nA1,10\n", truncated: false },
];

const rendered = renderedRows(sheets);

describe("placeSheetCitations", () => {
  it("counts the body rows each sheet renders", () => {
    expect(rendered).toEqual([2, 1]);
  });

  it("places a citation on the sheet and row the extraction recorded", () => {
    expect(
      placeSheetCitations(sheets, rendered, [
        { sheet: "Chi tiết", row: 1 },
        { sheet: "Tổng hợp", row: 2 },
      ]),
    ).toEqual([
      { sheetIndex: 1, row: 1 },
      { sheetIndex: 0, row: 2 },
    ]);
  });

  it("places nothing it cannot point at, so no citation is drawn on the wrong row", () => {
    expect(
      placeSheetCitations(sheets, rendered, [
        undefined,
        { sheet: "Đã xoá", row: 1 },
        // The header line is not a cited row, and a row past the preview was never rendered.
        { sheet: "Tổng hợp", row: 0 },
        { sheet: "Tổng hợp", row: 3 },
        { row: 1 },
      ]),
    ).toEqual([undefined, undefined, undefined, undefined, undefined]);
  });
});
