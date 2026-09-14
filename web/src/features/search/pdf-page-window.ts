import type { PdfPageView } from "./source-provenance";

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
