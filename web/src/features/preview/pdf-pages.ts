/**
 * Geometry and windowing for the PDF reader: where a highlight lands on a rendered page, how tall an
 * unrendered page is, and which pages are worth mounting. Nothing here knows how a highlight was recorded.
 */

/** A rectangle to highlight on a page, in PDF user-space points as the extraction recorded it. */
export type PdfHighlight = {
  page: number;
  left: number;
  top: number;
  right: number;
  bottom: number;
  origin: "TOPLEFT" | "BOTTOMLEFT";
};

export type PdfPageView = { view: readonly number[]; rotate: number };

/**
 * Maps a Docling box (PDF points in the page's user space) onto a page rendered at `renderedWidth` CSS
 * pixels. Rotated pages are not outlined because the extraction frame may already be
 * orientation-corrected; the page itself still renders.
 */
export function pdfBoxRect(box: PdfHighlight, page: PdfPageView, renderedWidth: number) {
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

/** Compact page label such as "4", "4–5" or "2, 7". */
export function formatPages(pages: readonly number[]): string | undefined {
  const first = pages.at(0);
  const last = pages.at(-1);
  if (first === undefined || last === undefined) return undefined;
  if (pages.length === last - first + 1) return first === last ? `${first}` : `${first}–${last}`;
  return pages.slice(0, 3).join(", ") + (pages.length > 3 ? "…" : "");
}

/** US Letter portrait, used until a page reports its own size. */
const LETTER: PdfPageView = { view: [0, 0, 612, 792], rotate: 0 };

/**
 * Pages whose canvases are mounted: the cited page the view opens at, plus the pages near the viewport and
 * `overscan` neighbours of each. Every other page stays a sized placeholder, so a long original only renders
 * (and, with range loading, only downloads) what the reader scrolls to.
 */
export function pagesToRender(
  near: Iterable<number>,
  anchor: number,
  pageCount: number,
  overscan = 1,
): number[] {
  const pages = new Set<number>();
  const add = (page: number) => {
    if (Number.isInteger(page) && page >= 1 && page <= pageCount) pages.add(page);
  };
  add(anchor);
  for (const page of near) {
    for (let offset = -overscan; offset <= overscan; offset += 1) add(page + offset);
  }
  return [...pages].sort((first, second) => first - second);
}

/** CSS height of a page rendered `width` pixels wide; a page not loaded yet borrows `fallback`'s proportions. */
export function pageHeight(
  page: PdfPageView | undefined,
  width: number,
  fallback: PdfPageView = LETTER,
) {
  const proportions = (candidate: PdfPageView) => {
    const [left = 0, bottom = 0, right = 0, top = 0] = candidate.view;
    const across = Math.abs(right - left);
    const down = Math.abs(top - bottom);
    const ratio = Math.abs(candidate.rotate % 180) === 90 ? across / down : down / across;
    return Number.isFinite(ratio) && ratio > 0 ? ratio : undefined;
  };
  const ratio = (page && proportions(page)) ?? proportions(fallback) ?? proportions(LETTER)!;
  return Math.round(width * ratio);
}

/** The page showing the largest share of itself, for the page counter; ties keep the earlier page. */
export function mostVisiblePage(ratios: ReadonlyMap<number, number>) {
  let best: number | undefined;
  let bestRatio = 0;
  for (const [page, ratio] of ratios) {
    if (ratio > bestRatio || (ratio === bestRatio && best !== undefined && page < best)) {
      best = page;
      bestRatio = ratio;
    }
  }
  return best;
}
