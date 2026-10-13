import { describe, expect, it } from "vitest";
import { planReads, type WantedBlock } from "./pdf-range-plan";

const none: (block: number) => boolean = () => false;
const asked = (...blocks: number[]): WantedBlock[] =>
  blocks.map((block, order) => ({ block, order }));
const spans = (wanted: WantedBlock[], count = 1000, taken = none) =>
  planReads(wanted, count, taken).map(({ first, last }) => [first, last]);

describe("planReads", () => {
  it("fetches a burst of scattered reads exactly, the most recent ask last", () => {
    // A scan's page dictionaries: one block per page, a page apart, asked for from the last page back.
    expect(spans(asked(159, 119, 79, 39))).toEqual([
      [159, 159],
      [119, 119],
      [79, 79],
      [39, 39],
    ]);
  });

  it("extends an ordinary read to the bytes that usually follow", () => {
    expect(spans(asked(10))).toEqual([[10, 25]]);
    expect(spans(asked(10, 100))).toEqual([
      [10, 25],
      [100, 115],
    ]);
  });

  it("makes one request of neighbours, and of a read that reaches the next one", () => {
    expect(spans(asked(10, 12, 15))).toEqual([[10, 25]]);
    expect(spans(asked(10, 22))).toEqual([[10, 37]]);
  });

  it("merges the neighbours of a burst but does not extend it", () => {
    // A text document's page dictionaries sit a few kilobytes apart and arrive as one run.
    expect(spans(asked(40, 41, 43, 200, 400))).toEqual([
      [40, 43],
      [200, 200],
      [400, 400],
    ]);
  });

  it("never fetches a block that is held or on its way", () => {
    const taken = (block: number) => block === 12 || block === 104;
    // The gap holds a taken block, so the two reads stay apart and neither request covers it.
    expect(spans(asked(10, 14), 1000, taken)).toEqual([
      [0, 11],
      [14, 29],
    ]);
    expect(spans(asked(12), 1000, taken)).toEqual([]);
  });

  it("grows backwards when held bytes or the end of the file stop it short", () => {
    const taken = (block: number) => block >= 104;
    expect(spans(asked(100), 1000, taken)).toEqual([[88, 103]]);
    expect(spans(asked(48), 50)).toEqual([[34, 49]]);
    expect(spans(asked(1), 3)).toEqual([[0, 2]]);
  });

  it("ignores blocks outside the file", () => {
    expect(spans(asked(-1, 50, 1.5), 50)).toEqual([]);
  });

  it("keeps the most recent ask of a merged run", () => {
    const runs = planReads(
      [
        { block: 10, order: 7 },
        { block: 11, order: 2 },
        { block: 300, order: 5 },
        { block: 600, order: 1 },
      ],
      1000,
      none,
    );
    expect(runs.map(({ first, order }) => [first, order])).toEqual([
      [600, 1],
      [300, 5],
      [10, 7],
    ]);
  });
});
