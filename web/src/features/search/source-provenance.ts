/**
 * Reads the location recorded in chunk provenance. The JSON is written by each extraction route
 * (Docling `prov` items with `page_no`/`bbox`, spreadsheet `sheetName`, table rows wrapping the block
 * provenance in `source` with their `tableRow`), so every field is optional and malformed input yields no location.
 */
export type ProvenanceBox = {
  page: number;
  left: number;
  top: number;
  right: number;
  bottom: number;
  origin: "TOPLEFT" | "BOTTOMLEFT";
};

export type SourceLocation = {
  pages: number[];
  sheet?: string;
  boxes: ProvenanceBox[];
  /** A cited passage is a table row, whose page keeps the columns that the passage text flattens. */
  table: boolean;
};

const MAX_BOXES = 60;

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

/** Compact page label such as "4", "4–5" or "2, 7". */
export function formatPages(pages: readonly number[]): string | undefined {
  if (!pages.length) return undefined;
  const first = pages[0]!;
  const last = pages.at(-1)!;
  if (pages.length === last - first + 1) return first === last ? `${first}` : `${first}–${last}`;
  return pages.slice(0, 3).join(", ") + (pages.length > 3 ? "…" : "");
}

export type PdfPageView = { view: readonly number[]; rotate: number };

/**
 * Maps a Docling box (PDF points in the page's user space) onto a page rendered at `renderedWidth` CSS
 * pixels. Rotated pages are not outlined because the extraction frame may already be
 * orientation-corrected; the page itself still renders.
 */
export function pdfBoxRect(box: ProvenanceBox, page: PdfPageView, renderedWidth: number) {
  if (page.rotate % 360 !== 0) return undefined;
  const [x0 = 0, y0 = 0, x1 = 0, y1 = 0] = page.view;
  const pageWidth = x1 - x0;
  const pageHeight = y1 - y0;
  if (pageWidth <= 0 || pageHeight <= 0) return undefined;
  const scale = renderedWidth / pageWidth;
  const top =
    box.origin === "BOTTOMLEFT"
      ? y1 - Math.max(box.top, box.bottom)
      : Math.min(box.top, box.bottom) - y0;
  const height = Math.abs(box.top - box.bottom);
  const left = Math.min(box.left, box.right) - x0;
  const width = Math.abs(box.right - box.left);
  if (height <= 0 || width <= 0 || top < -1 || left < -1 || top > pageHeight || left > pageWidth)
    return undefined;
  return {
    top: Math.max(0, top) * scale - 2,
    left: Math.max(0, left) * scale - 2,
    width: width * scale + 4,
    height: height * scale + 4,
  };
}
