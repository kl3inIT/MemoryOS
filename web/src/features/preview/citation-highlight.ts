import {
  locatePassage,
  markQuery,
  normalizeForMatch,
  sentenceAround,
  type QueryTerms,
} from "./preview-highlight";

export type CitationPlacement = {
  confidence: "exact" | "approximate" | "none";
  /** Absent whenever the passage could not be placed; nothing is drawn for it. */
  range?: Range;
  /**
   * Where the reader's question occurs inside the placed passage: the whole question, or its words alone when
   * the passage does not hold it. Absent when no question was given, empty when nothing of it occurs.
   */
  marks?: { range: Range; whole: boolean }[];
  /** The sentences that hold the marks: what the reader is pointed to instead of the whole passage. */
  focus?: Range[];
};

const UNPLACED: CitationPlacement = { confidence: "none" };

/** The highlight names the stylesheet paints; see `::highlight()` in `styles/base.css`. */
const CITATION_HIGHLIGHT = "memoryos-citation";
const ACTIVE_CITATION_HIGHLIGHT = "memoryos-citation-active";
const FOCUS_HIGHLIGHT = "memoryos-citation-focus";
const TERM_HIGHLIGHT = "memoryos-citation-term";
const WORD_HIGHLIGHT = "memoryos-citation-word";
const HINT_HIGHLIGHT = "memoryos-citation-hint";
const HIGHLIGHTS = [
  CITATION_HIGHLIGHT,
  ACTIVE_CITATION_HIGHLIGHT,
  FOCUS_HIGHLIGHT,
  TERM_HIGHLIGHT,
  WORD_HIGHLIGHT,
  HINT_HIGHLIGHT,
];

/** Elements that start a line of their own: a sentence never runs from one into the next. */
const BLOCK =
  "p, li, td, th, h1, h2, h3, h4, h5, h6, pre, blockquote, dt, dd, caption, figcaption, tr, div";

type TextRun = { node: Text; from: number };

/**
 * Places each passage in the text the container renders, reading that text once however deeply it is
 * nested. The heading each passage was read under is passed with it, because a figure that appears in two
 * statements is told apart only by its own section. A passage the locator will not commit to comes back
 * without a range, so the caller draws nothing for it rather than drawing it somewhere plausible. `terms`,
 * the reader's question, is marked only inside a placed passage, with the sentences that hold the marks: a word
 * marked across the whole document is noise, and nothing is drawn for a passage that was not found.
 */
export function placeCitations(
  container: Node,
  passages: readonly string[],
  sections: readonly (string | undefined)[] = [],
  terms?: QueryTerms,
): CitationPlacement[] {
  const runs: TextRun[] = [];
  const breaks: number[] = [];
  let raw = "";
  let block: Element | null | undefined;
  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const text = node as Text;
    const owner = text.parentElement?.closest(BLOCK);
    if (block !== undefined && owner !== block) breaks.push(raw.length);
    block = owner;
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
    if (!terms) return { confidence: found.confidence, range };
    const marks: { range: Range; whole: boolean }[] = [];
    const focus: Range[] = [];
    let focused = -1;
    for (const mark of markQuery(document_, terms, found)) {
      const marked = rangeOf(runs, mark.start, mark.end);
      if (!marked) continue;
      marks.push({ range: marked, whole: mark.whole });
      // Two marks in one sentence share its focus.
      if (mark.start < focused) continue;
      const sentence = sentenceAround(raw, mark, found, breaks);
      const ranged = rangeOf(runs, sentence.start, sentence.end);
      if (ranged) focus.push(ranged);
      focused = sentence.end;
    }
    return { confidence: found.confidence, range, marks, focus };
  });
}

/**
 * Paints the placed citations, with `active` drawn in its own colour so the rail's choice is visible. Without a
 * question every placed passage is filled, the active one at full strength. With one, the active passage's
 * sentences that hold the question are the focus, the whole question is marked over them and a lone word is
 * underlined; the other passages carry only a faint underline under their marks. A passage that holds nothing
 * of the question (a semantic match) keeps the passage fill, the only place there is to point at.
 */
export function paintCitations(placements: readonly CitationPlacement[], active?: number): void {
  const highlights = globalThis.CSS?.highlights;
  if (!highlights || typeof Highlight === "undefined") return;
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
  const chosen = active === undefined ? undefined : placements[active];
  const others = placements.filter((_, index) => index !== active);
  const searched = placements.some((placement) => placement.marks !== undefined);
  if (!searched) {
    paint(
      CITATION_HIGHLIGHT,
      others.map((placement) => placement.range),
    );
    paint(ACTIVE_CITATION_HIGHLIGHT, [chosen?.range]);
    for (const name of [FOCUS_HIGHLIGHT, TERM_HIGHLIGHT, WORD_HIGHLIGHT, HINT_HIGHLIGHT])
      highlights.delete(name);
    return;
  }
  const marks = chosen?.marks ?? [];
  highlights.delete(CITATION_HIGHLIGHT);
  paint(ACTIVE_CITATION_HIGHLIGHT, marks.length ? [] : [chosen?.range]);
  paint(FOCUS_HIGHLIGHT, marks.length ? (chosen?.focus ?? []) : []);
  // The marks sit on top of the focus, whichever was registered last.
  paint(
    TERM_HIGHLIGHT,
    marks.filter((mark) => mark.whole).map((mark) => mark.range),
    2,
  );
  paint(
    WORD_HIGHLIGHT,
    marks.filter((mark) => !mark.whole).map((mark) => mark.range),
    2,
  );
  paint(
    HINT_HIGHLIGHT,
    others.flatMap((placement) => (placement.marks ?? []).map((mark) => mark.range)),
    1,
  );
}

export function clearCitations(): void {
  const highlights = globalThis.CSS?.highlights;
  for (const name of HIGHLIGHTS) highlights?.delete(name);
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
