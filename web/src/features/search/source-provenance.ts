import type { PdfHighlight } from "@/features/preview/pdf-pages";

/** What the PDF reader highlights; provenance is where MemoryOS records it. */
export type ProvenanceBox = PdfHighlight;

export type SourceLocation = {
  pages: number[];
  sheet?: string;
  boxes: ProvenanceBox[];
  /** A cited passage is a table row, whose page keeps the columns that the passage text flattens. */
  table: boolean;
};

const MAX_BOXES = 60;

/**
 * Reads the location recorded in chunk provenance. The JSON is written by each extraction route
 * (Docling `prov` items with `page_no`/`bbox`, spreadsheet `sheetName`, table rows wrapping the block
 * provenance in `source` with their `tableRow`), so every field is optional and malformed input yields no location.
 */
export function readSourceLocation(provenanceJson: readonly string[]): SourceLocation {
  const pages = new Set<number>();
  const boxes: ProvenanceBox[] = [];
  let sheet: string | undefined;
  let table = false;
  let pagesFound = 0;
  for (const json of provenanceJson) {
    let value: unknown;
    try {
      value = JSON.parse(json);
    } catch {
      continue;
    }
    visit(value, 0);
  }
  return { pages: [...pages].sort((a, b) => a - b), sheet, boxes, table };

  function visit(value: unknown, depth: number) {
    if (depth > 4 || value === null || typeof value !== "object") return;
    if (Array.isArray(value)) {
      for (const item of value.slice(0, MAX_BOXES)) visit(item, depth + 1);
      return;
    }
    const record = value as Record<string, unknown>;
    const page = record.page_no;
    if (Number.isInteger(page) && (page as number) > 0 && (page as number) <= 10000) {
      pagesFound++;
      pages.add(page as number);
      const box = readBox(page as number, record.bbox);
      if (box && boxes.length < MAX_BOXES) boxes.push(box);
    }
    if (!sheet && typeof record.sheetName === "string" && record.sheetName.trim())
      sheet = record.sheetName.trim().slice(0, 120);
    if ("source" in record) {
      const before = pagesFound;
      visit(record.source, depth + 1);
      // A table row counts only when its wrapped block provenance records a page.
      if (Number.isInteger(record.tableRow) && pagesFound > before) table = true;
    }
  }
}

function readBox(page: number, value: unknown): ProvenanceBox | undefined {
  if (!value || typeof value !== "object") return undefined;
  const { l, t, r, b, coord_origin: origin } = value as Record<string, unknown>;
  if (![l, t, r, b].every((n) => typeof n === "number" && Number.isFinite(n))) return undefined;
  return {
    page,
    left: l as number,
    top: t as number,
    right: r as number,
    bottom: b as number,
    origin: origin === "TOPLEFT" ? "TOPLEFT" : "BOTTOMLEFT",
  };
}
