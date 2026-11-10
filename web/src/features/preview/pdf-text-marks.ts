import { markQuery, normalizeForMatch, sentenceAround, type QueryTerms } from "./preview-highlight";

/**
 * The question's marks inside a cited region of a PDF page, found in the page's own text rather than drawn as
 * the whole region. Nothing here knows pdf.js: the reader hands over the page's text items and the regions it
 * would otherwise fill, and gets rectangles back.
 */

/** One run of a page's text, placed on the page at scale 1, top left origin, as the reader displays it. */
export type PlacedText = {
  str: string;
  left: number;
  top: number;
  width: number;
  height: number;
  fontFamily: string;
  /** The run ends its line. */
  eol: boolean;
};

/** A page's text, and the width of the page it was placed on. */
export type PageText = { items: readonly PlacedText[]; width: number };

/** A text item as pdf.js `getTextContent` reports it. */
type TextItem = {
  str: string;
  transform: readonly number[];
  width: number;
  fontName: string;
  hasEOL: boolean;
};

type FontStyle = { fontFamily?: string; ascent?: number; descent?: number; vertical?: boolean };

export type Rect = { left: number; top: number; width: number; height: number };

/** What the reader draws for a cited region: the sentences that hold the question and its marks. */
export type PdfMarks = { focus: Rect[]; marks: (Rect & { whole: boolean })[] };

/** Measures `text` set in `fontFamily`, in any unit; only ratios within one run are used. */
export type MeasureText = (text: string, fontFamily: string) => number;

/** Text turned further than this from upright is left out: its marks would not fit a level rectangle. */
const MAX_TILT = 0.02;

/** A vertical step larger than this many lines between two runs of one region starts another block. */
const BLOCK_GAP = 1.6;

/** `m1 × m2` for the 2D affine matrices PDF uses, `[a, b, c, d, e, f]`. */
function multiply(m1: readonly number[], m2: readonly number[]): number[] {
  const [a1 = 1, b1 = 0, c1 = 0, d1 = 1, e1 = 0, f1 = 0] = m1;
  const [a2 = 1, b2 = 0, c2 = 0, d2 = 1, e2 = 0, f2 = 0] = m2;
  return [
    a1 * a2 + c1 * b2,
    b1 * a2 + d1 * b2,
    a1 * c2 + c1 * d2,
    b1 * c2 + d1 * d2,
    a1 * e2 + c1 * f2 + e1,
    b1 * e2 + d1 * f2 + f1,
  ];
}

/**
 * Places a page's text items on the page as displayed at scale 1, as pdf.js's own text layer does: through the
 * viewport's transform, from the baseline up by the font's ascent. Runs that are not upright, or set
 * vertically, are left out.
 */
export function placeTextItems(
  items: readonly unknown[],
  styles: Readonly<Record<string, FontStyle>>,
  viewportTransform: readonly number[],
): PlacedText[] {
  const placed: PlacedText[] = [];
  for (const item of items) {
    if (!item || typeof item !== "object" || !("str" in item)) continue;
    const text = item as TextItem;
    const style = styles[text.fontName] ?? {};
    const last = placed.at(-1);
    if (!text.str) {
      if (text.hasEOL && last) last.eol = true;
      continue;
    }
    if (style.vertical) continue;
    const matrix = multiply(viewportTransform, text.transform);
    const [a = 0, b = 0, c = 0, d = 0, e = 0, f = 0] = matrix;
    if (Math.abs(Math.atan2(b, a)) > MAX_TILT) continue;
    const height = Math.hypot(c, d);
    if (!(height > 0) || !(text.width > 0)) continue;
    const ascent = style.ascent ?? (style.descent ? 1 + style.descent : 0.8);
    placed.push({
      str: text.str,
      left: e,
      top: f - height * ascent,
      // pdf.js reports the width in page units; the viewport is at scale 1.
      width: text.width,
      height,
      fontFamily: style.fontFamily ?? "sans-serif",
      eol: text.hasEOL,
    });
  }
  return placed;
}

type Run = PlacedText & { from: number };

/**
 * The question's marks and the sentences holding them inside `regions` (rendered pixels), with the page's text
 * scaled by `scale` onto the rendering. `undefined` when no text of the page falls inside the regions, as on a
 * scan, so the caller draws the region as recorded; empty lists when there is text but nothing of the question
 * is in it, as for a match found by meaning.
 */
export function markPdfRegions(
  text: PageText,
  regions: readonly Rect[],
  scale: number,
  terms: QueryTerms,
  measure: MeasureText,
): PdfMarks | undefined {
  const runs: Run[] = [];
  const breaks: number[] = [];
  let raw = "";
  let region = -1;
  let previous: PlacedText | undefined;
  for (const item of text.items) {
    const centreX = (item.left + item.width / 2) * scale;
    const centreY = (item.top + item.height / 2) * scale;
    const inside = regions.findIndex(
      (rect) =>
        centreX >= rect.left &&
        centreX <= rect.left + rect.width &&
        centreY >= rect.top &&
        centreY <= rect.top + rect.height,
    );
    if (inside < 0) continue;
    if (previous) {
      const newLine = previous.eol || Math.abs(item.top - previous.top) > previous.height / 2;
      const gap = item.left - (previous.left + previous.width);
      const spaced = /\s$/.test(previous.str) || /^\s/.test(item.str);
      if (inside !== region || item.top - previous.top > previous.height * BLOCK_GAP) {
        raw += " ";
        breaks.push(raw.length);
      } else if (!spaced && (newLine || gap > item.height * 0.15)) raw += " ";
    }
    runs.push({ ...item, from: raw.length });
    raw += item.str;
    region = inside;
    previous = item;
  }
  if (!runs.length) return undefined;
  const document = normalizeForMatch(raw);
  const found = markQuery(document, terms);
  if (!found.length) return { focus: [], marks: [] };
  const focus: Rect[] = [];
  let focused = -1;
  for (const mark of found) {
    if (mark.start < focused) continue;
    const sentence = sentenceAround(raw, mark, { start: 0, end: raw.length }, breaks);
    focus.push(...spanRects(runs, sentence.start, sentence.end, scale, measure, 2));
    focused = sentence.end;
  }
  const marks = found.flatMap((mark) =>
    spanRects(runs, mark.start, mark.end, scale, measure, 1).map((rect) => ({
      ...rect,
      whole: mark.whole,
    })),
  );
  return { focus, marks };
}

/**
 * The rectangles covering `raw[start, end)`: one a run, the run's width shared among its characters in
 * proportion to their measured width, and runs that continue one line joined into one rectangle.
 */
function spanRects(
  runs: readonly Run[],
  start: number,
  end: number,
  scale: number,
  measure: MeasureText,
  pad: number,
): Rect[] {
  const rects: Rect[] = [];
  for (const run of runs) {
    const from = Math.max(start, run.from) - run.from;
    const to = Math.min(end, run.from + run.str.length) - run.from;
    if (from >= to) continue;
    const whole = measure(run.str, run.fontFamily);
    const at = (index: number) =>
      whole > 0
        ? (measure(run.str.slice(0, index), run.fontFamily) / whole) * run.width
        : (index / run.str.length) * run.width;
    const left = (run.left + at(from)) * scale;
    const right = (run.left + at(to)) * scale;
    const rect = {
      left: left - pad,
      top: run.top * scale - pad,
      width: right - left + pad * 2,
      height: run.height * scale + pad * 2,
    };
    const last = rects.at(-1);
    if (
      last &&
      Math.abs(last.top - rect.top) < rect.height / 2 &&
      rect.left - (last.left + last.width) < rect.height
    ) {
      const lastRight = Math.max(last.left + last.width, rect.left + rect.width);
      const top = Math.min(last.top, rect.top);
      last.height = Math.max(last.top + last.height, rect.top + rect.height) - top;
      last.top = top;
      last.width = lastRight - last.left;
    } else rects.push(rect);
  }
  return rects;
}
