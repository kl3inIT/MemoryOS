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

/**
 * One band per text line inside `outline`, in the image's pixels: each the line's upper edge left to right,
 * then its lower edge right to left. `undefined` when the region does not look like lines of text.
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

  const lines: Point[][] = [];
  const window = Math.max(4, Math.round(letter * 0.8));
  const pad = Math.max(3, Math.round(letter / 8));
  for (const edges of columnEdges(ink, width, height, parts)) {
    if (edges.count < 20) continue;
    const span = edges.last - edges.first + 1;
    const top = new Float64Array(span);
    const bottom = new Float64Array(span);
    bridge(edges.top, edges.first, span, top);
    bridge(edges.bottom, edges.first, span, bottom);
    const upper = mean(slide(top, window, Math.min), window);
    const lower = mean(slide(bottom, window, Math.max), window);
    const upperEdge: Point[] = [];
    const lowerEdge: Point[] = [];
    for (let column = 0; column < span; column += 1) {
      upperEdge.push([edges.first + column, (upper[column] ?? 0) - pad]);
      lowerEdge.push([edges.first + column, (lower[column] ?? 0) + 1 + pad]);
    }
    if (upperEdge.length) {
      upperEdge[0] = [edges.first - pad, upperEdge[0]![1]];
      upperEdge[upperEdge.length - 1] = [edges.last + 1 + pad, upperEdge.at(-1)![1]];
      lowerEdge[0] = [edges.first - pad, lowerEdge[0]![1]];
      lowerEdge[lowerEdge.length - 1] = [edges.last + 1 + pad, lowerEdge.at(-1)![1]];
    }
    lines.push([...simplify(upperEdge, 2), ...simplify(lowerEdge, 2).reverse()]);
  }
  return lines.length && lines.length <= MAX_LINES ? lines : undefined;
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

type Edges = {
  top: Map<number, number>;
  bottom: Map<number, number>;
  first: number;
  last: number;
  count: number;
};

/** For each joined part, the highest and lowest ink pixel of every column it holds ink in. */
function columnEdges(ink: Uint8Array, width: number, height: number, parts: Parts): Edges[] {
  const edges: Edges[] = Array.from({ length: parts.count }, () => ({
    top: new Map<number, number>(),
    bottom: new Map<number, number>(),
    first: Infinity,
    last: -Infinity,
    count: 0,
  }));
  for (let row = 0; row < height; row += 1) {
    for (let column = 0; column < width; column += 1) {
      if (!ink[row * width + column]) continue;
      const label = parts.labels[(row >> 1) * parts.width + (column >> 1)] ?? 0;
      const part = edges[label - 1];
      if (!part) continue;
      part.count += 1;
      if (!part.top.has(column)) part.top.set(column, row);
      part.bottom.set(column, row);
      part.first = Math.min(part.first, column);
      part.last = Math.max(part.last, column);
    }
  }
  return edges.filter((part) => part.count > 0);
}

/** A column's value, or the straight line between its nearest columns with ink. */
function bridge(known: Map<number, number>, first: number, span: number, into: Float64Array) {
  let previous = first;
  let previousValue = known.get(first) ?? 0;
  for (let column = 0; column < span; column += 1) {
    const at = first + column;
    const value = known.get(at);
    if (value !== undefined) {
      for (let gap = previous + 1; gap < at; gap += 1)
        into[gap - first] =
          previousValue + ((value - previousValue) * (gap - previous)) / (at - previous);
      into[column] = value;
      previous = at;
      previousValue = value;
    }
  }
}

function slide(values: Float64Array, radius: number, pick: (...values: number[]) => number) {
  const out = new Float64Array(values.length);
  for (let index = 0; index < values.length; index += 1) {
    const from = Math.max(0, index - radius);
    const to = Math.min(values.length, index + radius + 1);
    out[index] = pick(...values.subarray(from, to));
  }
  return out;
}

function mean(values: Float64Array, radius: number) {
  const out = new Float64Array(values.length);
  for (let index = 0; index < values.length; index += 1) {
    const from = Math.max(0, index - radius);
    const to = Math.min(values.length, index + radius + 1);
    let sum = 0;
    for (let at = from; at < to; at += 1) sum += values[at] ?? 0;
    out[index] = sum / (to - from);
  }
  return out;
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
