import type { PdfHighlight, PlacedOutline } from "./pdf-pages";
import { markQuery, normalizeForMatch, type QueryTerms } from "./preview-highlight";

/**
 * One text line the OCR located in a cited block of a scan: its page, its text and its quadrilateral, corner by
 * corner, in the space and origin of the block's own box.
 */
export type ScanLine = {
  page: number;
  text: string;
  origin: "TOPLEFT" | "BOTTOMLEFT";
  frame?: { width: number; height: number };
  points: readonly (readonly [number, number])[];
};

/** Points a stroke's outline is sampled at to tell whose line it is. */
const SAMPLES = 64;

/** How a stroke or a region is drawn once the question's lines are known: the line holding the question, or not. */
export type LineTone = "focus" | "faint";

/**
 * Where the question lies in a line's text, `[start, end)`; `whole` when it is the whole question rather than one of
 * its words, as the passage marker tells them apart.
 */
export type QuestionSpan = { start: number; end: number; whole: boolean };

/** The question inside one cited region: the part of a marked line it takes, and that line's stroke when read. */
export type QuestionMark = {
  /** The part of the line's quadrilateral the question takes, as fractions of the region. */
  slab: PlacedOutline;
  /** The line's highlighter stroke, which the mark follows inside the slab; without it the slab is drawn. */
  band?: PlacedOutline;
  /** The whole question is filled with the highlighter's darker step and underlined; a lone word is underlined. */
  whole: boolean;
};

/** A line of a region that holds the question: its quadrilateral and the slabs of it the question takes. */
export type MarkedLine = {
  quad: PlacedOutline;
  slabs: { slab: PlacedOutline; whole: boolean }[];
};

/** A cited region narrowed to the question. */
export type NarrowedRegion = {
  /** Shapes to draw, as fractions of the placed region, each with its tone; none draws the region whole. */
  lines?: PlacedOutline[];
  tones?: LineTone[];
  /** The question itself, drawn at full strength over its line. */
  marks?: QuestionMark[];
  /** The tone of the whole region when it holds no stroke or line to draw. */
  tone?: LineTone;
};

/**
 * Where the question lies in each line, by line index: offsets into the line's text, under its phrase wherever it
 * occurs, or under its words when the phrase occurs nowhere, with the passage marker's own comparison. The lines
 * are read as one text in their order, so a phrase broken across two lines marks its part in each.
 */
export function lineMarks(
  lines: readonly ScanLine[],
  terms: QueryTerms | undefined,
): ReadonlyMap<number, QuestionSpan[]> {
  const marked = new Map<number, QuestionSpan[]>();
  if (!lines.length || !terms?.phrase.trim()) return marked;
  const starts: number[] = [];
  let joined = "";
  for (const line of lines) {
    if (joined) joined += "\n";
    starts.push(joined.length);
    joined += line.text;
  }
  for (const { start, end, whole } of markQuery(normalizeForMatch(joined), terms)) {
    for (let index = 0; index < lines.length; index += 1) {
      const from = starts[index]!;
      const to = from + lines[index]!.text.length;
      if (start >= to || end <= from) continue;
      const ranges = marked.get(index) ?? [];
      ranges.push({ start: Math.max(start, from) - from, end: Math.min(end, to) - from, whole });
      marked.set(index, ranges);
    }
  }
  return marked;
}

/** The number of words of a line's text, as its spaces separate them. */
export function wordCount(text: string) {
  return text.match(/\S+/g)?.length ?? 0;
}

/**
 * Where the question lies along a line, as shares of its quadrilateral from its left side: each occurrence from the
 * start of its first word to the end of its last. Read from the line's word spans (`wordSpans`) when they were
 * read and there are as many as the text has words; in proportion to the text's characters otherwise.
 */
export function markShares(
  text: string,
  ranges: readonly QuestionSpan[],
  spans: readonly (readonly [number, number])[] | undefined,
): { from: number; to: number; whole: boolean }[] {
  const words = [...text.matchAll(/\S+/g)].map(
    (word) => [word.index, word.index + word[0].length] as const,
  );
  const read = spans && spans.length === words.length;
  return ranges.flatMap(({ start, end, whole }) => {
    if (!text.length || end <= start) return [];
    if (!read) return [{ from: start / text.length, to: end / text.length, whole }];
    const first = words.findIndex(([, wordEnd]) => wordEnd > start);
    const last = words.findLastIndex(([wordStart]) => wordStart < end);
    if (first < 0 || last < first) return [];
    return [{ from: spans[first]![0], to: spans[last]![1], whole }];
  });
}

/** The part of a line's quadrilateral (top left, top right, bottom right, bottom left) between two shares along it. */
export function slab(quad: PlacedOutline, from: number, to: number): PlacedOutline {
  if (quad.length !== 4) return quad;
  const [topLeft, topRight, bottomRight, bottomLeft] = quad as [
    [number, number],
    [number, number],
    [number, number],
    [number, number],
  ];
  const at = (a: [number, number], b: [number, number], t: number): [number, number] => [
    a[0] + (b[0] - a[0]) * t,
    a[1] + (b[1] - a[1]) * t,
  ];
  return [
    at(topLeft, topRight, from),
    at(topLeft, topRight, to),
    at(bottomLeft, bottomRight, to),
    at(bottomLeft, bottomRight, from),
  ];
}

/**
 * The lines of one cited box that hold the question, each placed by `place` (a box carrying the line's corners as
 * its outline, to that outline as fractions of the region, as `frameBoxRect` or `pdfBoxRect` places it) with the
 * slabs the question takes, from the line's word spans when they were read.
 */
export function markedLinesOf(
  box: PdfHighlight,
  lines: readonly ScanLine[],
  marks: ReadonlyMap<number, readonly QuestionSpan[]>,
  spans: ReadonlyMap<number, readonly (readonly [number, number])[] | undefined> | undefined,
  place: (outlined: PdfHighlight) => PlacedOutline | undefined,
): MarkedLine[] {
  return linesInBox(box, lines).flatMap((index) => {
    const ranges = marks.get(index);
    const line = lines[index]!;
    const quad = ranges && place({ ...box, outline: line.points });
    if (!ranges || !quad) return [];
    return [
      {
        quad,
        slabs: markShares(line.text, ranges, spans?.get(index)).map(({ from, to, whole }) => ({
          slab: slab(quad, from, to),
          whole,
        })),
      },
    ];
  });
}

/** The lines that belong to a cited box: on its page, in its space, with their centre inside it. */
export function linesInBox(box: PdfHighlight, lines: readonly ScanLine[]): number[] {
  const inside: number[] = [];
  const [left, right] = [Math.min(box.left, box.right), Math.max(box.left, box.right)];
  const [low, high] = [Math.min(box.top, box.bottom), Math.max(box.top, box.bottom)];
  lines.forEach((line, index) => {
    if (line.page !== box.page || line.origin !== box.origin || line.points.length < 3) return;
    if (
      Boolean(line.frame) !== Boolean(box.frame) ||
      (line.frame &&
        box.frame &&
        (line.frame.width !== box.frame.width || line.frame.height !== box.frame.height))
    )
      return;
    const x = line.points.reduce((sum, [px]) => sum + px, 0) / line.points.length;
    const y = line.points.reduce((sum, [, py]) => sum + py, 0) / line.points.length;
    if (x >= left && x <= right && y >= low && y <= high) inside.push(index);
  });
  return inside;
}

/**
 * Which strokes are a marked line's: a stroke is when more than half of its outline, measured along its length,
 * lies inside one of the marked lines' quadrilaterals or within a quarter of that line's height of it. A straight
 * stroke has most of its corners in its rounded ends, so the outline is sampled evenly rather than by corner. The
 * neighbouring line's stroke has one edge near at most, so it stays faint. Shapes are fractions of the region;
 * `aspect` is the region's width over its height on screen, so distances are measured as drawn.
 */
export function bandTones(
  bands: readonly PlacedOutline[],
  marked: readonly PlacedOutline[],
  aspect: number,
): LineTone[] {
  const scale = Number.isFinite(aspect) && aspect > 0 ? aspect : 1;
  const quads = marked.map((quad) => quad.map(([x, y]): [number, number] => [x * scale, y]));
  return bands.map((band) => {
    const samples = resample(
      band.map(([x, y]): [number, number] => [x * scale, y]),
      SAMPLES,
    );
    if (!samples.length) return "faint";
    const near = quads.some((quad) => {
      const reach = height(quad) / 4;
      const held = samples.filter((point) => distance(point, quad) <= reach).length;
      return held * 2 > samples.length;
    });
    return near ? "focus" : "faint";
  });
}

/**
 * One region of a citation whose question was found in its lines. The stroke of a line holding the question is its
 * focus, light, and the question itself is drawn at full strength over it, following the stroke inside the slab of
 * the line it takes; the region's other strokes are faint. A marked line no stroke was matched to, or a region whose
 * pixels were not read, draws the line's quadrilateral as the focus and the slab as the mark, so the question is
 * never left unmarked. A region holding none of the question is faint.
 */
export function narrowRegion(
  bands: readonly PlacedOutline[] | undefined,
  marked: readonly MarkedLine[],
  aspect: number,
): NarrowedRegion {
  if (!marked.length)
    return bands?.length
      ? { lines: [...bands], tones: bands.map((): LineTone => "faint") }
      : { tone: "faint" };
  const lines = [...(bands ?? [])];
  const tones = bandTones(
    lines,
    marked.map(({ quad }) => quad),
    aspect,
  );
  const marks: QuestionMark[] = [];
  for (const { quad, slabs } of marked) {
    const band = lines.find(
      (candidate, index) =>
        index < (bands?.length ?? 0) && bandTones([candidate], [quad], aspect)[0] === "focus",
    );
    if (!band) {
      lines.push(quad);
      tones.push("focus");
    }
    for (const { slab: part, whole } of slabs)
      marks.push(band ? { slab: part, band, whole } : { slab: part, whole });
  }
  return { lines, tones, marks };
}

/** Points spread evenly along a closed outline, by length, starting at its first corner. */
function resample(
  outline: readonly (readonly [number, number])[],
  count: number,
): [number, number][] {
  if (outline.length < 2) return outline.map(([x, y]): [number, number] => [x, y]);
  const edges = outline.map((from, index) => {
    const to = outline[(index + 1) % outline.length]!;
    return { from, to, length: Math.hypot(to[0] - from[0], to[1] - from[1]) };
  });
  const perimeter = edges.reduce((sum, edge) => sum + edge.length, 0);
  if (!(perimeter > 0)) return [[outline[0]![0], outline[0]![1]]];
  const points: [number, number][] = [];
  let edge = 0;
  let walked = 0;
  for (let index = 0; index < count; index += 1) {
    const at = (index * perimeter) / count;
    while (edge < edges.length - 1 && walked + edges[edge]!.length < at)
      walked += edges[edge++]!.length;
    const { from, to, length } = edges[edge]!;
    const t = length ? Math.min(1, (at - walked) / length) : 0;
    points.push([from[0] + t * (to[0] - from[0]), from[1] + t * (to[1] - from[1])]);
  }
  return points;
}

/** A line quadrilateral's height: the mean of its two short sides, corner order as the OCR answers it. */
function height(quad: readonly (readonly [number, number])[]) {
  if (quad.length !== 4) {
    const ys = quad.map(([, y]) => y);
    return Math.max(...ys) - Math.min(...ys);
  }
  const side = (a: readonly [number, number], b: readonly [number, number]) =>
    Math.hypot(a[0] - b[0], a[1] - b[1]);
  const [a, b, c, d] = quad as [
    readonly [number, number],
    readonly [number, number],
    readonly [number, number],
    readonly [number, number],
  ];
  const across = [side(a, b), side(b, c), side(c, d), side(d, a)].sort((x, y) => x - y);
  return (across[0]! + across[1]!) / 2;
}

/** Distance from a point to a polygon: zero inside it, else to its nearest edge. */
function distance(
  point: readonly [number, number],
  polygon: readonly (readonly [number, number])[],
) {
  const [x, y] = point;
  let inside = false;
  let nearest = Infinity;
  for (let index = 0, previous = polygon.length - 1; index < polygon.length; previous = index++) {
    const [x1, y1] = polygon[index]!;
    const [x2, y2] = polygon[previous]!;
    if (y1 > y !== y2 > y && x < ((x2 - x1) * (y - y1)) / (y2 - y1) + x1) inside = !inside;
    const dx = x2 - x1;
    const dy = y2 - y1;
    const length = dx * dx + dy * dy;
    const t = length ? Math.max(0, Math.min(1, ((x - x1) * dx + (y - y1) * dy) / length)) : 0;
    nearest = Math.min(nearest, Math.hypot(x - (x1 + t * dx), y - (y1 + t * dy)));
  }
  return inside ? 0 : nearest;
}
