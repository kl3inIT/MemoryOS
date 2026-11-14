import { describe, expect, it } from "vitest";
import type { PdfHighlight, PlacedOutline } from "./pdf-pages";
import {
  bandTones,
  lineMarks,
  linesInBox,
  markedLinesOf,
  markShares,
  narrowRegion,
  slab,
  wordCount,
  type ScanLine,
} from "./scan-line-marks";

const picture = { width: 1000, height: 800 };

/** A line of a picture, left to right at `y`, 40 px tall. */
function line(text: string, y: number, left = 110, right = 890): ScanLine {
  return {
    page: 1,
    text,
    origin: "TOPLEFT",
    frame: picture,
    points: [
      [left, y],
      [right, y],
      [right, y + 40],
      [left, y + 40],
    ],
  };
}

const terms = (phrase: string) => ({ phrase, words: phrase.split(/\s+/) });

describe("lineMarks", () => {
  const lines = [
    line("Điều 5. Nghỉ phép năm", 110),
    line("Người lao động được nghỉ phép năm", 160),
    line("hưởng nguyên lương theo hợp đồng.", 210),
    line("Điều 6. Nghỉ ốm", 260),
  ];

  it("places the whole question in the lines that hold it, not where only its words are", () => {
    expect([...lineMarks(lines, terms("nghỉ phép năm"))]).toEqual([
      [0, [{ start: 8, end: 21, whole: true }]],
      [1, [{ start: 20, end: 33, whole: true }]],
    ]);
  });

  it("splits a phrase broken across two lines into its part in each", () => {
    expect([...lineMarks(lines, terms("phép năm hưởng nguyên lương"))]).toEqual([
      [1, [{ start: 25, end: 33, whole: true }]],
      [2, [{ start: 0, end: 18, whole: true }]],
    ]);
  });

  it("falls back to the question's words, each alone, when the phrase is nowhere", () => {
    expect([...lineMarks(lines, terms("ốm đau"))]).toEqual([
      [3, [{ start: 13, end: 15, whole: false }]],
    ]);
  });

  it("compares a question typed without diacritics with the text's diacritics removed", () => {
    expect([...lineMarks(lines, terms("nghi om"))].map(([index]) => index)).toEqual([3]);
  });

  it("marks nothing without a question or a line", () => {
    expect(lineMarks(lines, undefined).size).toBe(0);
    expect(lineMarks(lines, terms("  ")).size).toBe(0);
    expect(lineMarks([], terms("nghỉ")).size).toBe(0);
  });
});

describe("markShares", () => {
  const text = "trên bản scan: đoạn văn thẳng, dòng chữ";
  const question = [{ start: 15, end: 29, whole: true }];

  it("takes the question from the start of its first word to the end of its last, as the ink spaced them", () => {
    const spans: [number, number][] = [
      [0, 0.1],
      [0.12, 0.2],
      [0.22, 0.35],
      [0.4, 0.5],
      [0.52, 0.6],
      [0.62, 0.75],
      [0.78, 0.88],
      [0.9, 1],
    ];
    expect(wordCount(text)).toBe(8);
    expect(markShares(text, question, spans)).toEqual([{ from: 0.4, to: 0.75, whole: true }]);
  });

  it("falls back to the share of the text's characters when the ink did not show every space", () => {
    expect(markShares(text, question, [[0, 1]])).toEqual([
      { from: 15 / text.length, to: 29 / text.length, whole: true },
    ]);
    expect(markShares(text, question, undefined)).toEqual([
      { from: 15 / text.length, to: 29 / text.length, whole: true },
    ]);
  });
});

describe("slab", () => {
  it("cuts a tilted quadrilateral across, between two shares along it", () => {
    const quad: PlacedOutline = [
      [0, 0.4],
      [1, 0],
      [1, 0.2],
      [0, 0.6],
    ];
    expect(slab(quad, 0.25, 0.5)).toEqual([
      [0.25, 0.30000000000000004],
      [0.5, 0.2],
      [0.5, 0.4],
      [0.25, 0.5],
    ]);
  });
});

describe("linesInBox and markedLinesOf", () => {
  const box: PdfHighlight = {
    page: 1,
    left: 100,
    top: 100,
    right: 900,
    bottom: 300,
    origin: "TOPLEFT",
    frame: picture,
  };

  it("keeps the lines of the box's page and frame whose centre it holds", () => {
    const lines = [
      line("trong khối", 110),
      line("khối khác", 400),
      { ...line("trang khác", 110), page: 2 },
      { ...line("khung khác", 110), frame: { width: 500, height: 400 } },
      { ...line("không khung", 110), frame: undefined },
    ];
    expect(linesInBox(box, lines)).toEqual([0]);
  });

  it("places a box's marked lines and the slabs their question takes", () => {
    const lines = [line("Nghỉ phép năm", 110), line("Nghỉ ốm", 160)];
    const marks = lineMarks(lines, terms("phép năm"));
    const spans = new Map([
      [
        0,
        [
          [0, 0.3],
          [0.35, 0.6],
          [0.65, 1],
        ] as [number, number][],
      ],
    ]);
    const placed = markedLinesOf(box, lines, marks, spans, (outlined) =>
      outlined.outline?.map(([x, y]): [number, number] => [(x - 100) / 800, (y - 100) / 200]),
    );
    expect(placed).toHaveLength(1);
    expect(placed[0]!.quad).toEqual([
      [0.0125, 0.05],
      [0.9875, 0.05],
      [0.9875, 0.25],
      [0.0125, 0.25],
    ]);
    expect(placed[0]!.slabs).toEqual([{ slab: slab(placed[0]!.quad, 0.35, 1), whole: true }]);
  });
});

describe("bandTones", () => {
  // A region 800 px wide and 200 px tall; three strokes, one per line, as fractions of it.
  const stroke = (top: number): PlacedOutline => [
    [0.02, top],
    [0.98, top],
    [0.98, top + 0.18],
    [0.02, top + 0.18],
  ];
  const bands = [stroke(0.06), stroke(0.31), stroke(0.56)];

  it("makes the stroke along a marked line its focus and leaves the others faint", () => {
    const quad: PlacedOutline = [
      [0.0125, 0.3],
      [0.9875, 0.3],
      [0.9875, 0.5],
      [0.0125, 0.5],
    ];
    expect(bandTones(bands, [quad], 4)).toEqual(["faint", "focus", "faint"]);
  });

  it("measures a straight stroke along its length, not by the corners of its rounded ends", () => {
    // A stroke as the ink reader draws a straight line: two long edges and eight corners in each rounded end,
    // the ends reaching past the OCR's quadrilateral.
    const cap = (x: number, side: 1 | -1): [number, number][] =>
      Array.from({ length: 8 }, (_, step) => {
        const angle = (Math.PI * (step + 1)) / 9;
        return [x + side * 0.02 * Math.sin(angle), 0.4 - side * 0.08 * Math.cos(angle)];
      });
    const straight: PlacedOutline = [
      [0.05, 0.32],
      [0.95, 0.32],
      ...cap(0.95, 1),
      [0.95, 0.48],
      [0.05, 0.48],
      ...cap(0.05, -1),
    ];
    const quad: PlacedOutline = [
      [0.06, 0.3],
      [0.94, 0.3],
      [0.94, 0.5],
      [0.06, 0.5],
    ];
    expect(bandTones([straight], [quad], 4)).toEqual(["focus"]);
  });

  it("leaves every stroke faint when no line is marked", () => {
    expect(bandTones(bands, [], 4)).toEqual(["faint", "faint", "faint"]);
  });
});

describe("narrowRegion", () => {
  const band: PlacedOutline = [
    [0.02, 0.1],
    [0.98, 0.1],
    [0.98, 0.3],
    [0.02, 0.3],
  ];
  const far: PlacedOutline = [
    [0.02, 0.7],
    [0.98, 0.7],
    [0.98, 0.9],
    [0.02, 0.9],
  ];
  const near: PlacedOutline = [
    [0.01, 0.08],
    [0.99, 0.08],
    [0.99, 0.32],
    [0.01, 0.32],
  ];

  it("makes the marked line's stroke the focus and lays the question over it, following the stroke", () => {
    const part = slab(near, 0.2, 0.5);
    expect(narrowRegion([band], [{ quad: near, slabs: [{ slab: part, whole: true }] }], 4)).toEqual(
      {
        lines: [band],
        tones: ["focus"],
        marks: [{ slab: part, band, whole: true }],
      },
    );
  });

  it("draws a marked line no stroke matched as its quadrilateral, with its slab over it", () => {
    const part = slab(far, 0, 0.5);
    expect(narrowRegion([band], [{ quad: far, slabs: [{ slab: part, whole: false }] }], 4)).toEqual(
      {
        lines: [band, far],
        tones: ["faint", "focus"],
        marks: [{ slab: part, whole: false }],
      },
    );
    expect(
      narrowRegion(undefined, [{ quad: far, slabs: [{ slab: part, whole: true }] }], 4),
    ).toEqual({ lines: [far], tones: ["focus"], marks: [{ slab: part, whole: true }] });
  });

  it("makes a region holding none of the question faint", () => {
    expect(narrowRegion(undefined, [], 4)).toEqual({ tone: "faint" });
    expect(narrowRegion([band], [], 4)).toEqual({ lines: [band], tones: ["faint"] });
  });
});
