import { MAX_TABLE_ROWS, parseCsv, type Sheets } from "./preview-kind";

/** Where a workbook citation sits, as the extraction recorded it. */
export type SheetCitation = { sheet?: string; row: number };

/** The rendered sheet and row a citation was placed on. */
export type SheetPlacement = { sheetIndex: number; row: number };

/**
 * How many body rows the preview renders for each sheet. Parsing a workbook is the expensive part of placing
 * its citations, so a reader does it once per workbook and places each citation against the result.
 */
export function renderedRows(sheets: Sheets): number[] {
  // The header is line 0, so the body is one shorter than the parsed sheet.
  return sheets.map((sheet) =>
    Math.min(Math.max(parseCsv(sheet.csv).length - 1, 0), MAX_TABLE_ROWS),
  );
}

/**
 * Resolves each workbook citation onto a row the preview actually rendered. A workbook citation is placed by
 * the row number the extraction recorded rather than by searching for its text, because the extraction and the
 * preview format numbers and dates differently. A citation this cannot point at is left unplaced, so it is
 * never drawn on a row that merely looks like it.
 */
export function placeSheetCitations(
  sheets: Sheets,
  rendered: readonly number[],
  citations: readonly (SheetCitation | undefined)[],
): (SheetPlacement | undefined)[] {
  return citations.map((citation) => {
    if (!citation?.sheet || !Number.isInteger(citation.row) || citation.row < 1) return undefined;
    const sheetIndex = sheets.findIndex((sheet) => sheet.name === citation.sheet);
    if (sheetIndex < 0 || citation.row > (rendered[sheetIndex] ?? 0)) return undefined;
    return { sheetIndex, row: citation.row };
  });
}
