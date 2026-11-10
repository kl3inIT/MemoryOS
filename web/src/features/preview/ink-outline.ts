/**
 * Tightens an OCR block's outline to the text lines inside it, from the pixels of the drawn page or picture.
 * The layout model's outline follows the block with a margin; the reader expects the mark to follow the
 * letters. Nothing here leaves the outline: the bands are built from ink found inside it, and anything that
 * cannot be trusted gives no bands, so the caller draws the outline as before.
 */

import type { PlacedOutline } from "./pdf-pages";

export type Point = [number, number];
/** An RGBA image, as `ImageData` holds it. */
export type InkImage = { data: Uint8ClampedArray; width: number; height: number };

const MAX_PIXELS = 4_000_000;
const MAX_LINES = 60;
const MIN_INK = 30;
/** Ink over this share of the outline is a picture, a seal or a dark background, not lines of text. */
const MAX_INK_SHARE = 0.5;
/** Grey levels between ink and paper below which the split is noise. */
const MIN_CONTRAST = 40;
/** A joined part with less ink than this is a speck, not a line. */
const MIN_LINE_INK = 20;
/** The centre is smoothed over this many line heights on each side. */
const SMOOTHING_LETTERS = 2;
/** A line is straight when its smoothed centre stays within this share of its height of one straight line. */
const STRAIGHT_SHARE = 0.4;
/** The band reaches the top that this share of columns rise above, and the foot this share stay above. */
const TOP_PERCENTILE = 0.2;
const FOOT_PERCENTILE = 0.8;
/** Room around the letters, in line heights. */
const PAD_LETTERS = 0.12;
/** Lines of one block within this share of their usual thickness take it. */
const SHARED_THICKNESS = 0.35;
/** The weight of a column between letters, so the centre crosses gaps without bending towards them. */
const GAP_WEIGHT = 0.2;
/** Segments of each rounded end. */
const CAP_STEPS = 8;

/**
 * One band per text line inside `outline`, in the image's pixels, drawn as a highlighter stroke: the line's
 * centre, straight when a straight line fits it and smoothed along it otherwise, widened to one thickness on
 * both sides and rounded at the ends. Each is a polygon: one edge left to right, the rounded end, the other edge
 * back, the other end. `undefined` when the region does not look like lines of text.
 */
export function inkLines(
  image: InkImage,
  outline: readonly (readonly [number, number])[],
): Point[][] | undefined {
  const { data, width, height } = image;
  if (outline.length < 3 || width < 2 || height < 2 || width * height > MAX_PIXELS)
    return undefined;
  const inside = fillPolygon(outline, width, height);
  const gray = new Uint8Array(width * height);
  const histogram = new Float64Array(256);
  let insideCount = 0;
  for (let index = 0; index < gray.length; index += 1) {
    const offset = index * 4;
    const value = Math.round(
      0.299 * (data[offset] ?? 255) +
        0.587 * (data[offset + 1] ?? 255) +
        0.114 * (data[offset + 2] ?? 255),
    );
    gray[index] = value;
    if (inside[index]) {
      histogram[value] = (histogram[value] ?? 0) + 1;
      insideCount += 1;
    }
  }
  if (insideCount < MIN_INK * 2) return undefined;
  const threshold = otsu(histogram, insideCount);
  const ink = new Uint8Array(width * height);
  let inkCount = 0;
  let inkSum = 0;
  let paperSum = 0;
  for (let index = 0; index < gray.length; index += 1) {
    if (!inside[index]) continue;
    const value = gray[index] ?? 255;
    if (value <= threshold) {
      ink[index] = 1;
      inkCount += 1;
      inkSum += value;
    } else paperSum += value;
  }
  if (inkCount < MIN_INK || inkCount > insideCount * MAX_INK_SHARE) return undefined;
  if (paperSum / (insideCount - inkCount) - inkSum / inkCount < MIN_CONTRAST) return undefined;

  const letter = letterHeight(ink, width, height);
  const joined = dilate(
    ink,
    width,
    height,
    Math.max(6, Math.round(letter * 1.2)),
    Math.max(1, Math.round(letter / 6)),
  );
  for (let index = 0; index < joined.length; index += 1) if (!inside[index]) joined[index] = 0;
  const parts = halfResolutionParts(joined, width, height);
  if (!parts || parts.count > MAX_LINES) return undefined;

  const bands: Band[] = [];
  for (const columns of columnInk(ink, width, height, parts)) {
    const band = lineBand(columns);
    if (band) bands.push(band);
  }
  if (!bands.length || bands.length > MAX_LINES) return undefined;
  // Lines of one block read as one paragraph: they share its usual thickness.
  if (bands.length > 1) {
    const usual = median(bands.map((band) => band.thickness));
    for (const band of bands)
      if (Math.abs(band.thickness - usual) < usual * SHARED_THICKNESS) band.thickness = usual;
  }
  return bands.map((band) => strokeOutline(band.centre, band.thickness));
}

/**
 * {@link inkLines} on a drawn source (the PDF page's canvas, a decoded picture) of `sourceWidth` ×
 * `sourceHeight` pixels, reading only the outline's bounds. `undefined` where the pixels cannot be read.
 */
function inkLinesFrom(
  source: CanvasImageSource,
  sourceWidth: number,
  sourceHeight: number,
  outline: readonly (readonly [number, number])[],
): Point[][] | undefined {
  if (outline.length < 3 || typeof document === "undefined") return undefined;
  const xs = outline.map(([x]) => x);
  const ys = outline.map(([, y]) => y);
  const left = Math.max(0, Math.floor(Math.min(...xs)));
  const top = Math.max(0, Math.floor(Math.min(...ys)));
  const right = Math.min(sourceWidth, Math.ceil(Math.max(...xs)));
  const bottom = Math.min(sourceHeight, Math.ceil(Math.max(...ys)));
  const width = right - left;
  const height = bottom - top;
  if (width < 2 || height < 2 || width * height > MAX_PIXELS) return undefined;
  try {
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext("2d", { willReadFrequently: true });
    if (!context) return undefined;
    context.fillStyle = "#fff";
    context.fillRect(0, 0, width, height);
    context.drawImage(source, left, top, width, height, 0, 0, width, height);
    const pixels = context.getImageData(0, 0, width, height);
    const local = outline.map(([x, y]): Point => [x - left, y - top]);
    return inkLines({ data: pixels.data, width, height }, local)?.map((line) =>
      line.map(([x, y]): Point => [x + left, y + top]),
    );
  } catch {
    // A tainted or unavailable canvas: the outline is drawn instead.
    return undefined;
  }
}

/**
 * The lines of a placed region, read from a drawn source: `rect` is where the region sits in units of which one
 * is `scaleX` × `scaleY` source pixels, and `outline` and the lines returned are fractions of `rect`, as
 * {@link CitationRegion} draws them.
 */
export function placedInkLines(
  source: CanvasImageSource,
  sourceWidth: number,
  sourceHeight: number,
  rect: { left: number; top: number; width: number; height: number },
  outline: PlacedOutline,
  scaleX: number,
  scaleY: number,
): PlacedOutline[] | undefined {
  if (!(rect.width > 0) || !(rect.height > 0) || !(scaleX > 0) || !(scaleY > 0)) return undefined;
  const pixels = outline.map(([x, y]): Point => [
    (rect.left + x * rect.width) * scaleX,
    (rect.top + y * rect.height) * scaleY,
  ]);
  return inkLinesFrom(source, sourceWidth, sourceHeight, pixels)?.map((line) =>
    line.map(([x, y]): [number, number] => [
      (x / scaleX - rect.left) / rect.width,
      (y / scaleY - rect.top) / rect.height,
    ]),
  );
}

/** Every pixel whose centre lies inside the polygon (even-odd), row by row. */
function fillPolygon(
  polygon: readonly (readonly [number, number])[],
  width: number,
  height: number,
) {
  const inside = new Uint8Array(width * height);
  const crossings: number[] = [];
  for (let row = 0; row < height; row += 1) {
    const y = row + 0.5;
    crossings.length = 0;
    for (let index = 0; index < polygon.length; index += 1) {
      const [x1, y1] = polygon[index]!;
      const [x2, y2] = polygon[(index + 1) % polygon.length]!;
      if (y1 <= y !== y2 <= y) crossings.push(x1 + ((y - y1) / (y2 - y1)) * (x2 - x1));
    }
    crossings.sort((a, b) => a - b);
    for (let pair = 0; pair + 1 < crossings.length; pair += 2) {
      const from = Math.max(0, Math.ceil(crossings[pair]! - 0.5));
      const to = Math.min(width - 1, Math.floor(crossings[pair + 1]! - 0.5));
      for (let column = from; column <= to; column += 1) inside[row * width + column] = 1;
    }
  }
  return inside;
}

/** Otsu's threshold: the grey level that best separates the two classes of the histogram. */
function otsu(histogram: Float64Array, total: number) {
  let sumAll = 0;
  for (let level = 0; level < 256; level += 1) sumAll += level * (histogram[level] ?? 0);
  let best = -1;
  let threshold = 127;
  let weight = 0;
  let sum = 0;
  for (let level = 0; level < 256; level += 1) {
    weight += histogram[level] ?? 0;
    sum += level * (histogram[level] ?? 0);
    if (weight === 0 || weight === total) continue;
    const below = sum / weight;
    const above = (sumAll - sum) / (total - weight);
    const between = weight * (total - weight) * (below - above) ** 2;
    if (between > best) {
      best = between;
      threshold = level;
    }
  }
  return threshold;
}

/** The 75th percentile of the ink's height in each column that has ink: about a letter's height. */
function letterHeight(ink: Uint8Array, width: number, height: number) {
  const runs: number[] = [];
  for (let column = 0; column < width; column += 1) {
    let count = 0;
    for (let row = 0; row < height; row += 1) count += ink[row * width + column] ?? 0;
    if (count) runs.push(count);
  }
  runs.sort((a, b) => a - b);
  return Math.max(6, runs[Math.floor(runs.length * 0.75)] ?? 6);
}

/** A separable dilation by a `(2·rx+1) × (2·ry+1)` rectangle, with running counts. */
function dilate(mask: Uint8Array, width: number, height: number, rx: number, ry: number) {
  const across = new Uint8Array(mask.length);
  for (let row = 0; row < height; row += 1) {
    let count = 0;
    const base = row * width;
    for (let column = -rx; column < width; column += 1) {
      const entering = column + rx;
      if (entering < width) count += mask[base + entering] ?? 0;
      const leaving = column - rx - 1;
      if (leaving >= 0) count -= mask[base + leaving] ?? 0;
      if (column >= 0) across[base + column] = count > 0 ? 1 : 0;
    }
  }
  const out = new Uint8Array(mask.length);
  for (let column = 0; column < width; column += 1) {
    let count = 0;
    for (let row = -ry; row < height; row += 1) {
      const entering = row + ry;
      if (entering < height) count += across[entering * width + column] ?? 0;
      const leaving = row - ry - 1;
      if (leaving >= 0) count -= across[leaving * width + column] ?? 0;
      if (row >= 0) out[row * width + column] = count > 0 ? 1 : 0;
    }
  }
  return out;
}

type Parts = { labels: Int32Array; width: number; count: number };

/** Connected parts of the joined ink at half resolution, four-connected; `undefined` past the line limit. */
function halfResolutionParts(joined: Uint8Array, width: number, height: number): Parts | undefined {
  const halfWidth = Math.ceil(width / 2);
  const halfHeight = Math.ceil(height / 2);
  const small = new Uint8Array(halfWidth * halfHeight);
  for (let row = 0; row < height; row += 1)
    for (let column = 0; column < width; column += 1)
      if (joined[row * width + column]) small[(row >> 1) * halfWidth + (column >> 1)] = 1;
  const labels = new Int32Array(small.length);
  const stack: number[] = [];
  let count = 0;
  for (let start = 0; start < small.length; start += 1) {
    if (!small[start] || labels[start]) continue;
    count += 1;
    if (count > MAX_LINES) return undefined;
    labels[start] = count;
    stack.push(start);
    while (stack.length) {
      const at = stack.pop()!;
      const row = Math.floor(at / halfWidth);
      const column = at % halfWidth;
      const neighbours = [
        column > 0 ? at - 1 : -1,
        column < halfWidth - 1 ? at + 1 : -1,
        row > 0 ? at - halfWidth : -1,
        row < halfHeight - 1 ? at + halfWidth : -1,
      ];
      for (const next of neighbours) {
        if (next >= 0 && small[next] && !labels[next]) {
          labels[next] = count;
          stack.push(next);
        }
      }
    }
  }
  return { labels, width: halfWidth, count };
}

/** The ink of one joined part (one line), column by column. */
type ColumnInk = {
  first: number;
  last: number;
  /** Per column from `first`: how many ink pixels, their summed rows, the highest and the lowest. */
  count: Float64Array;
  rowSum: Float64Array;
  top: Float64Array;
  bottom: Float64Array;
};

type Band = { centre: Point[]; thickness: number };

/** For each joined part, its ink in every column it holds ink in. */
function columnInk(ink: Uint8Array, width: number, height: number, parts: Parts): ColumnInk[] {
  const spans = Array.from({ length: parts.count }, () => ({ first: Infinity, last: -Infinity }));
  const labelAt = (row: number, column: number) =>
    parts.labels[(row >> 1) * parts.width + (column >> 1)] ?? 0;
  for (let row = 0; row < height; row += 1)
    for (let column = 0; column < width; column += 1) {
      if (!ink[row * width + column]) continue;
      const span = spans[labelAt(row, column) - 1];
      if (!span) continue;
      span.first = Math.min(span.first, column);
      span.last = Math.max(span.last, column);
    }
  const lines = spans.map(({ first, last }): ColumnInk | undefined => {
    if (!Number.isFinite(first)) return undefined;
    const span = last - first + 1;
    return {
      first,
      last,
      count: new Float64Array(span),
      rowSum: new Float64Array(span),
      top: new Float64Array(span).fill(Infinity),
      bottom: new Float64Array(span).fill(-Infinity),
    };
  });
  for (let row = 0; row < height; row += 1)
    for (let column = 0; column < width; column += 1) {
      if (!ink[row * width + column]) continue;
      const line = lines[labelAt(row, column) - 1];
      if (!line) continue;
      const at = column - line.first;
      line.count[at] = (line.count[at] ?? 0) + 1;
      line.rowSum[at] = (line.rowSum[at] ?? 0) + row;
      line.top[at] = Math.min(line.top[at] ?? Infinity, row);
      line.bottom[at] = Math.max(line.bottom[at] ?? -Infinity, row + 1);
    }
  return lines.filter((line): line is ColumnInk => line !== undefined);
}

/**
 * A line's highlighter stroke. The centre goes through the mass of each column's ink, so an accent or a
 * descender barely moves it, smoothed by a straight line fitted around each column; when one straight line fits
 * the whole line, that line is the centre. The thickness runs from the line's usual top (tall letters and
 * accents included) to its usual foot, measured across the line, so a lone accent does not widen it.
 */
function lineBand(line: ColumnInk): Band | undefined {
  const span = line.last - line.first + 1;
  let inked = 0;
  for (let at = 0; at < span; at += 1) inked += line.count[at] ?? 0;
  if (inked < MIN_LINE_INK) return undefined;
  const weight = line.count;
  const centre = new Float64Array(span).fill(Number.NaN);
  for (let at = 0; at < span; at += 1)
    if (weight[at]) centre[at] = (line.rowSum[at] ?? 0) / (weight[at] ?? 1);
  // Columns between letters take the straight line between their inked neighbours.
  let previous = -1;
  for (let at = 0; at < span; at += 1) {
    if (Number.isNaN(centre[at])) continue;
    if (previous < 0) centre.fill(centre[at] ?? 0, 0, at);
    else
      for (let gap = previous + 1; gap < at; gap += 1)
        centre[gap] =
          (centre[previous] ?? 0) +
          (((centre[at] ?? 0) - (centre[previous] ?? 0)) * (gap - previous)) / (at - previous);
    previous = at;
  }
  if (previous >= 0) centre.fill(centre[previous] ?? 0, previous + 1);

  // The line's own height sets every length below, so a line is drawn the same alone or in a paragraph.
  const extents: number[] = [];
  for (let at = 0; at < span; at += 1)
    if (weight[at]) extents.push((line.bottom[at] ?? 0) - (line.top[at] ?? 0));
  const height = Math.max(4, median(extents));
  const radius = Math.max(6, Math.round(height * SMOOTHING_LETTERS));
  const fitted = fitLine(centre, weight);
  const smooth = new Float64Array(span);
  for (let at = 0; at < span; at += 1) smooth[at] = smoothAt(centre, weight, at, radius);
  // Straight when the smoothed centre stays near one line away from the ends, where smoothing sees one side.
  let worst = 0;
  for (let at = radius; at < span - radius; at += 1)
    worst = Math.max(worst, Math.abs((smooth[at] ?? 0) - fitted(at)));
  const straight = worst < height * STRAIGHT_SHARE;
  const centreAt = (at: number) => (straight ? fitted(at) : (smooth[at] ?? 0));

  const tops: number[] = [];
  const feet: number[] = [];
  for (let at = 0; at < span; at += 1) {
    if (!weight[at]) continue;
    const slope = (centreAt(Math.min(span - 1, at + 2)) - centreAt(Math.max(0, at - 2))) / 4;
    const across = Math.cos(Math.atan(slope));
    tops.push(((line.top[at] ?? 0) - centreAt(at)) * across);
    feet.push(((line.bottom[at] ?? 0) - centreAt(at)) * across);
  }
  const pad = Math.max(2, height * PAD_LETTERS);
  const upper = percentile(tops, TOP_PERCENTILE) - pad;
  const lower = percentile(feet, FOOT_PERCENTILE) + pad;

  const step = Math.max(2, Math.round(height / 3));
  const along: Point[] = [];
  for (let at = 0; at < span; at += step) along.push([line.first + at, centreAt(at)]);
  along.push([line.last + 1, centreAt(span - 1)]);
  // Move the centre across the line, along its normal, to the middle of the band.
  const shift = (upper + lower) / 2;
  const normals = along.map((_, index) => normalAt(along, index));
  const moved = along.map(([x, y], index): Point => {
    const [nx, ny] = normals[index] ?? [0, 1];
    return [x + nx * shift, y + ny * shift];
  });
  return { centre: simplify(moved, 0.5), thickness: lower - upper };
}

/** Weighted sums for a least-squares line through `(x, value)` pairs. */
class LineFit {
  private w0 = 0;
  private w1 = 0;
  private w2 = 0;
  private v0 = 0;
  private v1 = 0;

  add(x: number, value: number, w: number) {
    this.w0 += w;
    this.w1 += w * x;
    this.w2 += w * x * x;
    this.v0 += w * value;
    this.v1 += w * x * value;
  }

  /** The line's value at `x`; a flat line at the mean where the points cannot fix a slope. */
  at(x: number) {
    const determinant = this.w0 * this.w2 - this.w1 * this.w1;
    const slope = determinant > 1e-9 ? (this.w0 * this.v1 - this.w1 * this.v0) / determinant : 0;
    const intercept = this.w0 > 0 ? (this.v0 - slope * this.w1) / this.w0 : 0;
    return intercept + slope * x;
  }
}

/** One straight line through every column's centre, weighted by its ink, as a function of the column. */
function fitLine(centre: Float64Array, weight: Float64Array): (column: number) => number {
  const fit = new LineFit();
  for (let at = 0; at < centre.length; at += 1)
    fit.add(at, centre[at] ?? 0, (weight[at] ?? 0) + GAP_WEIGHT);
  return (column) => fit.at(column);
}

/**
 * The centre at `at`, from a straight line fitted to the columns within `radius`, weighted by their ink and by a
 * tricube of their distance (local linear regression): it follows any curve and, unlike a moving average, does
 * not sag at the ends of the line, where only one side is seen.
 */
function smoothAt(centre: Float64Array, weight: Float64Array, at: number, radius: number) {
  const fit = new LineFit();
  const from = Math.max(0, at - radius);
  const to = Math.min(centre.length - 1, at + radius);
  for (let index = from; index <= to; index += 1) {
    const kernel = (1 - (Math.abs(index - at) / (radius + 1)) ** 3) ** 3;
    fit.add(index - at, centre[index] ?? 0, ((weight[index] ?? 0) + GAP_WEIGHT) * kernel);
  }
  return fit.at(0);
}

/** The unit normal of a polyline at a point, pointing down for a line drawn left to right. */
function normalAt(points: readonly Point[], index: number): Point {
  const [ax, ay] = points[Math.max(0, index - 1)] ?? [0, 0];
  const [bx, by] = points[Math.min(points.length - 1, index + 1)] ?? [1, 0];
  const length = Math.hypot(bx - ax, by - ay) || 1;
  return [-(by - ay) / length, (bx - ax) / length];
}

/**
 * A stroke of `thickness` along `centre`, as a polygon: one edge, a rounded end, the other edge back and the
 * other rounded end.
 */
function strokeOutline(centre: readonly Point[], thickness: number): Point[] {
  const half = thickness / 2;
  const first = centre[0] ?? [0, 0];
  const last = centre.at(-1) ?? first;
  const normals = centre.map((_, index) => normalAt(centre, index));
  const upper = centre.map(([x, y], index): Point => {
    const [nx, ny] = normals[index] ?? [0, 1];
    return [x - nx * half, y - ny * half];
  });
  const lower = centre.map(([x, y], index): Point => {
    const [nx, ny] = normals[index] ?? [0, 1];
    return [x + nx * half, y + ny * half];
  });
  const cap = ([x, y]: Point, [nx, ny]: Point, forward: number): Point[] => {
    // Directions along the line: the normal turned a quarter back.
    const [dx, dy] = [ny * forward, -nx * forward];
    const points: Point[] = [];
    for (let step = 1; step < CAP_STEPS; step += 1) {
      const angle = -Math.PI / 2 + (Math.PI * step) / CAP_STEPS;
      const along = Math.cos(angle) * half;
      const across = Math.sin(angle) * half * forward;
      points.push([x + dx * along + nx * across, y + dy * along + ny * across]);
    }
    return points;
  };
  return [
    ...upper,
    ...cap(last, normals.at(-1) ?? [0, 1], 1),
    ...lower.reverse(),
    ...cap(first, normals[0] ?? [0, 1], -1),
  ];
}

function median(values: readonly number[]) {
  return percentile(values, 0.5);
}

function percentile(values: readonly number[], share: number) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor((sorted.length - 1) * share)] ?? 0;
}

/** Douglas–Peucker: the fewest points within `tolerance` pixels of the edge. */
function simplify(points: Point[], tolerance: number): Point[] {
  if (points.length < 3) return points;
  const [x1, y1] = points[0]!;
  const [x2, y2] = points.at(-1)!;
  const length = Math.hypot(x2 - x1, y2 - y1) || 1;
  let farthest = 0;
  let at = 0;
  for (let index = 1; index < points.length - 1; index += 1) {
    const [x, y] = points[index]!;
    const distance = Math.abs((y2 - y1) * (x - x1) - (x2 - x1) * (y - y1)) / length;
    if (distance > farthest) {
      farthest = distance;
      at = index;
    }
  }
  if (farthest <= tolerance) return [points[0]!, points.at(-1)!];
  return [
    ...simplify(points.slice(0, at + 1), tolerance).slice(0, -1),
    ...simplify(points.slice(at), tolerance),
  ];
}
