import { describe, expect, it } from "vitest";
import {
  formatPages,
  frameBoxRect,
  mostVisiblePage,
  nearestPage,
  pageHeight,
  pagesToRender,
  pdfBoxRect,
} from "./pdf-pages";

describe("pagesToRender", () => {
  it("keeps the cited page and neighbours of the pages near the viewport inside the document", () => {
    expect(pagesToRender([], 7, 120)).toEqual([7]);
    expect(pagesToRender([1, 2], 7, 120)).toEqual([1, 2, 3, 7]);
    expect(pagesToRender([120], 1, 120, 2)).toEqual([1, 118, 119, 120]);
  });

  it("drops pages outside the document and a cited page beyond a shorter original", () => {
    expect(pagesToRender([0, Number.NaN], 9, 3)).toEqual([1]);
  });
});

describe("pageHeight", () => {
  it("scales the page proportions to the rendered width and swaps them for quarter turns", () => {
    expect(pageHeight({ view: [0, 0, 612, 792], rotate: 0 }, 612)).toBe(792);
    expect(pageHeight({ view: [0, 0, 612, 792], rotate: 90 }, 792)).toBe(612);
    expect(pageHeight({ view: [10, 20, 310, 620], rotate: 180 }, 150)).toBe(300);
  });

  it("borrows the loaded page's proportions, then US Letter, for unknown or degenerate pages", () => {
    const a4 = { view: [0, 0, 595, 842], rotate: 0 };
    expect(pageHeight(undefined, 595, a4)).toBe(842);
    expect(pageHeight({ view: [0, 0, 0, 0], rotate: 0 }, 612, { view: [], rotate: 0 })).toBe(792);
  });
});

describe("mostVisiblePage", () => {
  it("chooses the largest visible share and the earlier page on a tie", () => {
    expect(mostVisiblePage(new Map())).toBeUndefined();
    expect(
      mostVisiblePage(
        new Map([
          [4, 0.25],
          [5, 0.75],
        ]),
      ),
    ).toBe(5);
    expect(
      mostVisiblePage(
        new Map([
          [9, 0.5],
          [8, 0.5],
        ]),
      ),
    ).toBe(8);
  });
});

describe("nearestPage", () => {
  it("chooses the page closest to the one being read and the earlier page on a tie", () => {
    expect(nearestPage([], 5)).toBeUndefined();
    expect(nearestPage([1, 9, 4], 5)).toBe(4);
    expect(nearestPage([7, 3], 5)).toBe(3);
  });
});

describe("formatPages", () => {
  it("compacts ranges and lists", () => {
    expect(formatPages([])).toBeUndefined();
    expect(formatPages([4])).toBe("4");
    expect(formatPages([4, 5, 6])).toBe("4–6");
    expect(formatPages([2, 7])).toBe("2, 7");
    expect(formatPages([1, 3, 5, 9])).toBe("1, 3, 5…");
  });
});

describe("pdfBoxRect", () => {
  const letter = { view: [0, 0, 612, 792], rotate: 0 };

  it("flips bottom-left boxes onto the rendered page with a small outline margin", () => {
    const rect = pdfBoxRect(
      { page: 1, left: 72, top: 700, right: 540, bottom: 640, origin: "BOTTOMLEFT" },
      letter,
      306,
    );
    expect(rect).toEqual({
      top: 92 * 0.5 - 2,
      left: 72 * 0.5 - 2,
      width: 468 * 0.5 + 4,
      height: 60 * 0.5 + 4,
    });
  });

  it("keeps top-left boxes and skips rotated pages or boxes outside the page", () => {
    expect(
      pdfBoxRect(
        { page: 1, left: 0, top: 100, right: 612, bottom: 150, origin: "TOPLEFT" },
        letter,
        612,
      ),
    ).toEqual({ top: 98, left: -2, width: 616, height: 54 });
    expect(
      pdfBoxRect(
        { page: 1, left: 0, top: 10, right: 5, bottom: 0, origin: "BOTTOMLEFT" },
        { ...letter, rotate: 90 },
        612,
      ),
    ).toBeUndefined();
    expect(
      pdfBoxRect(
        { page: 1, left: 900, top: 10, right: 950, bottom: 0, origin: "BOTTOMLEFT" },
        letter,
        612,
      ),
    ).toBeUndefined();
  });

  // A portrait A4 page stored with /Rotate: 842.04 x 595.44 as displayed for a quarter turn.
  const a4 = [0, 0, 595.44, 842.04];
  const framed = (width: number, height: number) =>
    ({
      page: 1,
      left: 79,
      top: 143,
      right: 760,
      bottom: 438,
      origin: "TOPLEFT",
      frame: { width, height },
    }) as const;

  it("draws a box measured in the page as displayed on a page turned 90, 180 or 270 degrees", () => {
    for (const rotate of [90, 270]) {
      const rect = pdfBoxRect(framed(842.04, 595.44), { view: a4, rotate }, 421.02);
      expect(rect?.left).toBeCloseTo(79 / 2 - 2);
      expect(rect?.top).toBeCloseTo(143 / 2 - 2);
      expect(rect?.width).toBeCloseTo(681 / 2 + 4);
      expect(rect?.height).toBeCloseTo(295 / 2 + 4);
    }
    const half = pdfBoxRect(
      { ...framed(595.44, 842.04), right: 500 },
      { view: a4, rotate: 180 },
      595.44,
    );
    expect(half?.left).toBeCloseTo(77);
    expect(half?.top).toBeCloseTo(141);
    // The CropBox's own corner is not part of the displayed frame.
    const offset = { view: [36, 48, 36 + 595.44, 48 + 842.04], rotate: 90 };
    expect(pdfBoxRect(framed(842.04, 595.44), offset, 842.04)?.left).toBeCloseTo(77);
  });

  it("draws no framed box whose frame is not the displayed page, and no unframed box on a turned page", () => {
    // Measured in the stored page, not the displayed one.
    expect(
      pdfBoxRect({ ...framed(595.44, 842.04), right: 500 }, { view: a4, rotate: 90 }, 842.04),
    ).toBeUndefined();
    expect(pdfBoxRect(framed(842.04, 595.44), { view: a4, rotate: 0 }, 595.44)).toBeUndefined();
    const { frame: _frame, ...unframed } = framed(842.04, 595.44);
    expect(pdfBoxRect(unframed, { view: a4, rotate: 180 }, 595.44)).toBeUndefined();
  });
});

describe("frameBoxRect", () => {
  const box = { page: 1, left: 100, top: 300, right: 500, bottom: 900, origin: "TOPLEFT" } as const;

  it("places a box as fractions of its frame", () => {
    expect(frameBoxRect({ ...box, frame: { width: 1000, height: 2000 } })).toEqual({
      left: 0.1,
      top: 0.15,
      width: 0.4,
      height: 0.3,
    });
  });

  it("places nothing without a frame, without area or outside the frame", () => {
    expect(frameBoxRect(box)).toBeUndefined();
    expect(frameBoxRect({ ...box, frame: { width: 0, height: 2000 } })).toBeUndefined();
    expect(
      frameBoxRect({ ...box, right: 100, frame: { width: 1000, height: 2000 } }),
    ).toBeUndefined();
    expect(frameBoxRect({ ...box, frame: { width: 400, height: 2000 } })).toBeUndefined();
  });
});
