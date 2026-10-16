import { locatePassage, matchTerms, normalizeForMatch } from "./preview-highlight";

export type CitationPlacement = {
  confidence: "exact" | "approximate" | "none";
  /** Absent whenever the passage could not be placed; nothing is drawn for it. */
  range?: Range;
  /** Where the reader's own words occur inside the placed passage; empty when none do. */
  terms?: Range[];
};

const UNPLACED: CitationPlacement = { confidence: "none" };

/** The highlight names the stylesheet paints; see `::highlight()` in `styles/base.css`. */
const CITATION_HIGHLIGHT = "memoryos-citation";
const ACTIVE_CITATION_HIGHLIGHT = "memoryos-citation-active";
const TERM_HIGHLIGHT = "memoryos-citation-term";

type TextRun = { node: Text; from: number };

/**
 * Places each passage in the text the container renders, reading that text once however deeply it is
 * nested. The heading each passage was read under is passed with it, because a figure that appears in two
 * statements is told apart only by its own section. A passage the locator will not commit to comes back
 * without a range, so the caller draws nothing for it rather than drawing it somewhere plausible. `terms`,
 * the words a reader searched for, are ranged only inside a placed passage: a word marked across the whole
 * document is noise, and nothing is drawn for a passage that was not found.
 */
export function placeCitations(
  container: Node,
  passages: readonly string[],
  sections: readonly (string | undefined)[] = [],
  terms: readonly string[] = [],
): CitationPlacement[] {
  const runs: TextRun[] = [];
  let raw = "";
  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const text = node as Text;
    runs.push({ node: text, from: raw.length });
    raw += text.data;
  }
  if (!raw) return passages.map(() => UNPLACED);
  const document_ = normalizeForMatch(raw);
  return passages.map((passage, index) => {
    const found = locatePassage(document_, passage, sections[index]);
    if (found.confidence === "none") return UNPLACED;
    const range = rangeOf(runs, found.start, found.end);
    if (!range) return UNPLACED;
    if (!terms.length) return { confidence: found.confidence, range };
    const marked = matchTerms(document_, terms, found)
      .map((term) => rangeOf(runs, term.start, term.end))
      .filter((term): term is Range => term !== undefined);
    return { confidence: found.confidence, range, terms: marked };
  });
}

/** Paints the placed citations, with `active` drawn in its own colour so the rail's choice is visible. */
export function paintCitations(placements: readonly CitationPlacement[], active?: number): void {
  const highlights = globalThis.CSS?.highlights;
  if (!highlights || typeof Highlight === "undefined") return;
  const ranges = placements.map((placement) => placement.range);
  const paint = (name: string, drawn: readonly (Range | undefined)[], priority = 0) => {
    const present = drawn.filter((range): range is Range => range !== undefined);
    if (!present.length) {
      highlights.delete(name);
      return;
    }
    const highlight = new Highlight(...present);
    highlight.priority = priority;
    highlights.set(name, highlight);
  };
  paint(
    CITATION_HIGHLIGHT,
    ranges.filter((_, index) => index !== active),
  );
  paint(ACTIVE_CITATION_HIGHLIGHT, active === undefined ? [] : [ranges[active]]);
  // The words sit on top of both passage highlights, whichever was registered last.
  paint(
    TERM_HIGHLIGHT,
    placements.flatMap((placement) => placement.terms ?? []),
    1,
  );
}

export function clearCitations(): void {
  const highlights = globalThis.CSS?.highlights;
  highlights?.delete(CITATION_HIGHLIGHT);
  highlights?.delete(ACTIVE_CITATION_HIGHLIGHT);
  highlights?.delete(TERM_HIGHLIGHT);
}

function rangeOf(runs: readonly TextRun[], start: number, end: number): Range | undefined {
  const from = pointIn(runs, start);
  const to = pointIn(runs, end);
  if (!from || !to) return undefined;
  const range = document.createRange();
  range.setStart(from.node, from.offset);
  range.setEnd(to.node, to.offset);
  return range;
}

/** The text node and offset holding character `at`; the run's end counts as its own last position. */
function pointIn(runs: readonly TextRun[], at: number): { node: Text; offset: number } | undefined {
  let low = 0;
  let high = runs.length - 1;
  let found: TextRun | undefined;
  while (low <= high) {
    const middle = (low + high) >> 1;
    const run = runs[middle];
    if (!run) break;
    if (at < run.from) high = middle - 1;
    else if (at > run.from + run.node.data.length) low = middle + 1;
    else {
      found = run;
      // A position on a boundary belongs to the earlier run, which is where the range should close.
      high = middle - 1;
    }
  }
  return found && { node: found.node, offset: at - found.from };
}
