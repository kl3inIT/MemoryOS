import { describe, expect, it } from "vitest";
import { formatPages, mostVisiblePage, pageHeight, pagesToRender, pdfBoxRect } from "./pdf-pages";

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
});
