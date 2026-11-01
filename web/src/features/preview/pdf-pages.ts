/**
 * Geometry and windowing for the PDF reader: where a highlight lands on a rendered page, how tall an
 * unrendered page is, and which pages are worth mounting. Nothing here knows how a highlight was recorded.
 */

/**
 * A rectangle to highlight on a page, as the extraction recorded it. Without a `frame` it is in PDF
 * user-space points. With one it is measured from the top left of a frame of that size as a reader sees it
 * (a picture's pixels, or the points of a PDF page as displayed), and `origin` is `TOPLEFT`.
 */
export type PdfHighlight = {
  page: number;
  left: number;
  top: number;
  right: number;
  bottom: number;
  origin: "TOPLEFT" | "BOTTOMLEFT";
  frame?: { width: number; height: number };
};

export type PdfPageView = { view: readonly number[]; rotate: number };

/** Two sizes with the same proportions, to within the rounding of a rendering (1%). */
function sameProportions(width: number, height: number, otherWidth: number, otherHeight: number) {
  return Math.abs((width * otherHeight) / (height * otherWidth) - 1) <= 0.01;
}

/**
 * Where a framed box sits on the surface showing its frame, as fractions of that surface's width and
 * height. A box without a frame, without area or outside its frame has no place.
 */
export function frameBoxRect(box: PdfHighlight) {
  const frame = box.frame;
  if (!frame || !(frame.width > 0) || !(frame.height > 0)) return undefined;
  const left = Math.min(box.left, box.right);
  const top = Math.min(box.top, box.bottom);
  const width = Math.abs(box.right - box.left);
  const height = Math.abs(box.bottom - box.top);
  if (
    !(width > 0) ||
    !(height > 0) ||
    left < 0 ||
    top < 0 ||
    left + width > frame.width ||
    top + height > frame.height
  )
    return undefined;
  return {
    left: left / frame.width,
    top: top / frame.height,
    width: width / frame.width,
    height: height / frame.height,
  };
}

/**
 * Maps a recorded box onto a page rendered at `renderedWidth` CSS pixels. A box in user space is drawn on
 * an upright page only: on a rotated one the extraction frame may already be orientation-corrected. A box
 * that carries its frame is measured in the page as displayed, so it is drawn at any rotation, and only
 * when that frame has the displayed page's proportions.
 */
export function pdfBoxRect(box: PdfHighlight, page: PdfPageView, renderedWidth: number) {
  const [x0 = 0, y0 = 0, x1 = 0, y1 = 0] = page.view;
  const pageWidth = x1 - x0;
  const pageHeight = y1 - y0;
  if (pageWidth <= 0 || pageHeight <= 0) return undefined;
  if (box.frame) {
    const turned = Math.abs(page.rotate % 180) === 90;
    const shownWidth = turned ? pageHeight : pageWidth;
    const shownHeight = turned ? pageWidth : pageHeight;
    const placed = frameBoxRect(box);
    if (!placed || !sameProportions(box.frame.width, box.frame.height, shownWidth, shownHeight))
      return undefined;
    const renderedHeight = (renderedWidth * shownHeight) / shownWidth;
    return {
      top: placed.top * renderedHeight - 2,
      left: placed.left * renderedWidth - 2,
      width: placed.width * renderedWidth + 4,
      height: placed.height * renderedHeight + 4,
    };
  }
  if (page.rotate % 360 !== 0) return undefined;
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

/** The page of `pages` closest to `to`, the earlier one on a tie; the order the page rail draws in. */
export function nearestPage(pages: readonly number[], to: number) {
  let best: number | undefined;
  for (const page of pages) {
    const closer = best === undefined || Math.abs(page - to) < Math.abs(best - to);
    if (closer || (Math.abs(page - to) === Math.abs(best! - to) && page < best!)) best = page;
  }
  return best;
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
