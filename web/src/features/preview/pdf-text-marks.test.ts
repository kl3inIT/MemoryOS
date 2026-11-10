import { describe, expect, it } from "vitest";
import { markPdfRegions, placeTextItems, type Rect } from "./pdf-text-marks";

// A Letter page as displayed at scale 1: y grows downwards from the top of the page.
const VIEWPORT = [1, 0, 0, -1, 0, 792];
const STYLES = { F1: { fontFamily: "serif", ascent: 0.8 } };
const item = (str: string, x: number, baseline: number, hasEOL = true) => ({
  str,
  transform: [12, 0, 0, 12, x, baseline],
  // One unit of width a character, as the measure below counts them.
  width: str.length * 6,
  height: 12,
  fontName: "F1",
  hasEOL,
});
const items = [
  item("Điều 4. Nghỉ phép năm", 72, 700),
  item("Người lao động làm việc đủ 12 tháng được nghỉ", 72, 680, false),
  item(" phép", 72 + 45 * 6, 680),
  item("năm 12 ngày. Thời gian thử việc không tính.", 72, 666),
  item("Điều 5. Vắng mặt có lý do", 72, 600),
];
const page = { items: placeTextItems(items, STYLES, VIEWPORT), width: 612 };
const byCharacters = (text: string) => text.length;
const question = (phrase: string) => ({ phrase, words: phrase.split(" ") });
/** The region a block's box covers on the page, at scale 1. */
const box = (top: number, bottom: number): Rect => ({
  left: 70,
  top: 792 - top,
  width: 400,
  height: top - bottom,
});
const heading = box(712, 698);
const paragraph = box(692, 662);

describe("placeTextItems", () => {
  it("places a run from its baseline up by the font's ascent, as the page is displayed", () => {
    expect(page.items[0]).toMatchObject({
      str: "Điều 4. Nghỉ phép năm",
      left: 72,
      top: 792 - 700 - 12 * 0.8,
      height: 12,
      width: 21 * 6,
      eol: true,
    });
  });

  it("leaves out empty runs, marked content and runs that are not upright", () => {
    const placed = placeTextItems(
      [
        { type: "beginMarkedContent" },
        { ...item("", 0, 0), hasEOL: true },
        { ...item("Nghiêng", 72, 500), transform: [8.5, 8.5, -8.5, 8.5, 72, 500] },
      ],
      STYLES,
      VIEWPORT,
    );
    expect(placed).toEqual([]);
  });
});

describe("markPdfRegions", () => {
  it("marks the whole question inside the region and its sentence across the lines it wraps", () => {
    const found = markPdfRegions(page, [paragraph], 1, question("nghỉ phép"), byCharacters);
    // The phrase is split across two runs of one line: one rectangle covers both.
    expect(found?.marks).toHaveLength(1);
    expect(found?.marks[0]).toMatchObject({ whole: true });
    const [mark] = found?.marks ?? [];
    expect(mark?.left).toBeCloseTo(72 + 41 * 6 - 1);
    expect(mark?.width).toBeCloseTo("nghỉ phép".length * 6 + 2);
    // "Người … nghỉ phép" on one line, "năm 12 ngày." on the next; the sentence after it is left out.
    expect(found?.focus).toHaveLength(2);
    expect(found?.focus[1]?.width).toBeCloseTo(12 * 6 + 4);
  });

  it("never runs a sentence from one recorded block into the next", () => {
    const found = markPdfRegions(
      page,
      [heading, paragraph],
      1,
      question("nghỉ phép"),
      byCharacters,
    );
    expect(found?.marks).toHaveLength(2);
    // The heading's sentence stops at its own block: it does not reach into the paragraph.
    expect(found?.focus[0]?.top).toBeCloseTo(792 - 700 - 9.6 - 2);
    expect(found?.focus[0]?.height).toBeCloseTo(12 + 4);
  });

  it("scales the text onto the rendering", () => {
    const scaled = (rect: Rect) => ({
      left: rect.left * 2,
      top: rect.top * 2,
      width: rect.width * 2,
      height: rect.height * 2,
    });
    const found = markPdfRegions(page, [scaled(paragraph)], 2, question("nghỉ phép"), byCharacters);
    expect(found?.marks[0]?.left).toBeCloseTo((72 + 41 * 6) * 2 - 1);
  });

  it("marks the question's words alone when the region does not hold it", () => {
    // "làm việc", then "phép", "thử" and "việc" again; none of them is the whole question.
    const found = markPdfRegions(page, [paragraph], 1, question("thử việc phép"), byCharacters);
    expect(found?.marks.map((mark) => mark.whole)).toEqual([false, false, false, false]);
  });

  it("finds nothing to mark in a match found by meaning, and nothing at all on a scan", () => {
    expect(markPdfRegions(page, [paragraph], 1, question("vắng mặt"), byCharacters)).toEqual({
      focus: [],
      marks: [],
    });
    expect(markPdfRegions(page, [box(300, 200)], 1, question("nghỉ phép"), byCharacters)).toBe(
      undefined,
    );
  });
});
