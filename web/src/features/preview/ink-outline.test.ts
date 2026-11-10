import { describe, expect, it } from "vitest";
import { inkLines, type InkImage, type Point } from "./ink-outline";

/** A white RGBA image with `ink(x, y)` pixels painted in `shade`. */
function image(
  width: number,
  height: number,
  ink: (x: number, y: number) => boolean,
  shade = 20,
  paper = 255,
) {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y += 1)
    for (let x = 0; x < width; x += 1) {
      const value = ink(x + 0.5, y + 0.5) ? shade : paper;
      data.set([value, value, value, 255], (y * width + x) * 4);
    }
  return { data, width, height } satisfies InkImage;
}

function contains(polygon: readonly Point[], [x, y]: Point) {
  let inside = false;
  for (let index = 0, previous = polygon.length - 1; index < polygon.length; previous = index++) {
    const [x1, y1] = polygon[index]!;
    const [x2, y2] = polygon[previous]!;
    if (y1 > y !== y2 > y && x < ((x2 - x1) * (y - y1)) / (y2 - y1) + x1) inside = !inside;
  }
  return inside;
}

function area(polygon: readonly Point[]) {
  let sum = 0;
  for (let index = 0; index < polygon.length; index += 1) {
    const [x1, y1] = polygon[index]!;
    const [x2, y2] = polygon[(index + 1) % polygon.length]!;
    sum += x1 * y2 - x2 * y1;
  }
  return Math.abs(sum) / 2;
}

/** Where a band's upper and lower edges cross the column `x`, to a quarter pixel. */
function edgesAt(band: readonly Point[], x: number): [number, number] {
  const inside: number[] = [];
  for (let y = 0; y <= 200; y += 0.25) if (contains(band, [x, y])) inside.push(y);
  return [Math.min(...inside), Math.max(...inside)];
}

describe("inkLines", () => {
  it("draws a tilted line with gaps between its letters as one band along it, far tighter than its outline", () => {
    // Letters 13 px long with 5 px gaps along a line tilted 20 degrees, 16 px thick.
    const angle = (-20 * Math.PI) / 180;
    const [cos, sin] = [Math.cos(angle), Math.sin(angle)];
    const along = (x: number, y: number) => (x - 20) * cos + (y - 200) * sin;
    const across = (x: number, y: number) => -(x - 20) * sin + (y - 200) * cos;
    const letters = (x: number, y: number) => {
      const s = along(x, y);
      return s >= 0 && s <= 420 && Math.abs(across(x, y)) <= 8 && s % 18 < 13;
    };
    // The layout model's outline: the line's box with a wide margin and a notch, as staging answered.
    const outline: Point[] = [
      [5, 240],
      [5, 170],
      [380, 20],
      [430, 20],
      [430, 90],
      [250, 150],
      [240, 140],
      [60, 240],
    ];

    const lines = inkLines(image(440, 260, letters), outline);

    expect(lines).toHaveLength(1);
    const band = lines![0]!;
    for (let s = 2; s <= 418; s += 23) {
      const point: Point = [20 + s * cos, 200 + s * sin];
      expect(contains(band, point), `letter at ${s}`).toBe(true);
    }
    // The band is a strip around the letters: well under half of the outline.
    expect(area(band)).toBeLessThan(area(outline) * 0.5);
    // A point of the outline's margin, above the line, is not marked.
    expect(contains(band, [120, 120])).toBe(false);
  });

  it("follows an arched heading and leaves the hollow under the arch unmarked", () => {
    const [cx, cy] = [220, 260];
    const letters = (x: number, y: number) => {
      const r = Math.hypot(x - cx, y - cy);
      const theta = Math.atan2(cy - y, x - cx);
      return (
        r >= 150 && r <= 166 && theta > 0.35 && theta < Math.PI - 0.35 && (theta * 160) % 18 < 13
      );
    };
    const outline: Point[] = [
      [40, 230],
      [40, 140],
      [140, 70],
      [300, 70],
      [400, 140],
      [400, 230],
    ];

    const lines = inkLines(image(440, 260, letters), outline);

    expect(lines).toHaveLength(1);
    const band = lines![0]!;
    for (const theta of [0.5, 1, Math.PI / 2, 2.1, 2.6]) {
      const point: Point = [cx + 158 * Math.cos(theta), cy - 158 * Math.sin(theta)];
      expect(contains(band, point), `arc at ${theta}`).toBe(true);
    }
    expect(contains(band, [cx, 200])).toBe(false);
  });

  it("gives each line of a paragraph its own band", () => {
    const letters = (x: number, y: number) =>
      x >= 20 && x <= 380 && [40, 80, 120].some((top) => y >= top && y <= top + 14) && x % 16 < 12;
    const outline: Point[] = [
      [10, 25],
      [395, 25],
      [395, 150],
      [10, 150],
    ];

    const lines = inkLines(image(400, 170, letters), outline);

    expect(lines).toHaveLength(3);
    const middles = lines!.map((band) => {
      const ys = band.map(([, y]) => y);
      return (Math.min(...ys) + Math.max(...ys)) / 2;
    });
    middles
      .sort((a, b) => a - b)
      .forEach((middle, index) => expect(middle).toBeCloseTo(47 + index * 40, -1));
  });

  it("draws a straight line of accented letters as a stroke with straight, parallel edges", () => {
    // Letters 12 px tall on one line; every third letter wears an accent above it and every fifth a
    // descender below it, as Vietnamese text does.
    const letters = (x: number, y: number) => {
      const letter = Math.floor((x - 20) / 16);
      if (x < 20 || x > 380 || (x - 20) % 16 >= 11) return false;
      const body = y >= 60 && y <= 72;
      const accent =
        letter % 3 === 0 && y >= 51 && y <= 55 && (x - 20) % 16 >= 3 && (x - 20) % 16 < 8;
      const descender = letter % 5 === 0 && y > 72 && y <= 78 && (x - 20) % 16 < 3;
      return body || accent || descender;
    };
    const outline: Point[] = [
      [10, 40],
      [395, 40],
      [395, 95],
      [10, 95],
    ];

    const [band] = inkLines(image(400, 110, letters), outline) ?? [];

    expect(band).toBeDefined();
    // Away from the rounded ends the stroke's edges stay at one height each: no bump at an accent.
    const edges = [44, 76, 100, 148, 196, 228, 260, 308, 340].map((x) => edgesAt(band!, x));
    for (const [top, foot] of edges) {
      expect(top).toBeCloseTo(edges[0]![0], 0);
      expect(foot).toBeCloseTo(edges[0]![1], 0);
    }
    const [top, foot] = edges[0]!;
    // The letters' bodies are covered; a lone accent's tip and a descender's foot are not what sizes it.
    expect(top).toBeLessThan(60);
    expect(top).toBeGreaterThan(51);
    expect(foot).toBeGreaterThan(72);
    expect(foot).toBeLessThan(78);
  });

  it("gives the lines of one paragraph one thickness", () => {
    // The middle line wears accents on many letters; the others on none.
    const letters = (x: number, y: number) => {
      if (x < 20 || x > 380 || x % 16 >= 12) return false;
      const body = [40, 80, 120].some((top) => y >= top && y <= top + 12);
      const accent = y >= 72 && y <= 77 && x % 32 < 8;
      return body || accent;
    };
    const outline: Point[] = [
      [10, 25],
      [395, 25],
      [395, 150],
      [10, 150],
    ];

    const lines = inkLines(image(400, 170, letters), outline);

    expect(lines).toHaveLength(3);
    const thicknesses = lines!.map((band) => {
      const ys = band.filter(([x]) => x > 60 && x < 340).map(([, y]) => y);
      return Math.max(...ys) - Math.min(...ys);
    });
    for (const thickness of thicknesses) expect(thickness).toBeCloseTo(thicknesses[0]!, 5);
  });

  it("gives nothing for a region without lines of text, so the outline is drawn", () => {
    const outline: Point[] = [
      [5, 5],
      [195, 5],
      [195, 95],
      [5, 95],
    ];
    // Blank paper; a dark fill (a photograph or a dark background); faint marks on grey paper.
    expect(
      inkLines(
        image(200, 100, () => false),
        outline,
      ),
    ).toBeUndefined();
    expect(
      inkLines(
        image(200, 100, () => true),
        outline,
      ),
    ).toBeUndefined();
    expect(
      inkLines(
        image(200, 100, (x, y) => y > 40 && y < 55 && x % 10 < 6, 205, 225),
        outline,
      ),
    ).toBeUndefined();
    // Fewer than three points is no outline.
    expect(
      inkLines(
        image(200, 100, (_, y) => y > 40 && y < 55),
        [
          [0, 0],
          [10, 10],
        ],
      ),
    ).toBeUndefined();
  });
});
