/**
 * Locating a cited passage inside a rendered original.
 *
 * Only PDFs carry recorded coordinates. Everywhere else the citation has to be found in the text the
 * renderer produced, and that text legitimately differs from the extraction: a table row is flattened onto
 * one line, a justified paragraph carries soft hyphens, a heading is set in capitals, and Vietnamese accents
 * may arrive as combining marks.
 *
 * The rule the whole module exists for: **a passage that cannot be located is not drawn**. A highlight in
 * the wrong place tells the reader a passage came from text that is not its source, which is worse than no
 * highlight at all. Every uncertain outcome is reported as `none`, never guessed.
 */

/** Normalized text, plus where each normalized character starts and ends in the source it came from. */
export type NormalizedText = { text: string; sources: number[]; ends: number[] };

export type CitationLocation =
  | { confidence: "exact" | "approximate"; start: number; end: number }
  | { confidence: "none" };

const NOT_FOUND: CitationLocation = { confidence: "none" };

/** Characters a renderer inserts for layout and the extraction never saw. */
const IGNORED = /[­​-‍﻿]/;
const COMBINING = /\p{M}/u;
const WHITESPACE = /\s/;

/** Words taken from each end when the passage is not found verbatim. */
const ANCHOR_WORDS = 8;

/** Below this many anchor words the ends are too common to identify one place in a document. */
const MIN_ANCHOR_WORDS = 3;

/** How the chunk header joins the heading trail it writes, outermost heading first. */
const HEADING_SEPARATOR = " > ";

/** How far an anchored span may differ in length from the passage before it is not the passage. */
const LENGTH_TOLERANCE = 0.4;

/**
 * Case-folded, NFC-composed text with runs of whitespace collapsed to one space and layout characters
 * dropped, so the extraction's text and the renderer's text can be compared directly.
 */
export function normalizeForMatch(raw: string): NormalizedText {
  const out: string[] = [];
  const sources: number[] = [];
  const ends: number[] = [];
  let pendingSpace = -1;
  let index = 0;
  while (index < raw.length) {
    const start = index;
    let cluster = String.fromCodePoint(raw.codePointAt(index)!);
    index += cluster.length;
    // Combining marks belong to the character before them, so they compose with it rather than alone.
    while (index < raw.length) {
      const mark = String.fromCodePoint(raw.codePointAt(index)!);
      if (!COMBINING.test(mark)) break;
      cluster += mark;
      index += mark.length;
    }
    if (IGNORED.test(cluster.charAt(0))) continue;
    if (WHITESPACE.test(cluster.charAt(0))) {
      if (pendingSpace < 0) pendingSpace = start;
      continue;
    }
    // A run of whitespace becomes one space, and only between characters: the ends are trimmed.
    if (pendingSpace >= 0) {
      if (out.length) {
        out.push(" ");
        sources.push(pendingSpace);
        ends.push(pendingSpace + 1);
      }
      pendingSpace = -1;
    }
    for (const character of cluster.normalize("NFC").toLowerCase()) {
      out.push(character);
      sources.push(start);
      ends.push(index);
    }
  }
  return { text: out.join(""), sources, ends };
}

/**
 * Where `passage` sits in `document`, as offsets into the source `document` was normalized from, or `none`
 * when it cannot be placed with confidence.
 */
export function locatePassage(
  document: NormalizedText,
  passage: string,
  section?: string,
): CitationLocation {
  const needle = normalizeForMatch(passage).text;
  if (!needle || !document.text) return NOT_FOUND;
  const found = document.text.indexOf(needle);
  if (found < 0) return anchored(document, needle);
  // A repeated line — a table header on every page, a figure in two statements — is in the document more
  // than once, so only the heading it was read under can say which occurrence was cited.
  if (document.text.indexOf(needle, found + 1) >= 0) return underSection(document, needle, section);
  return span(document, found, found + needle.length, "exact");
}

/**
 * The first occurrence after the heading the passage was read under. The heading has to identify one place
 * itself, or it says no more than the repeated passage does; the result is `approximate`, because a heading
 * narrows the search without proving which line inside its section was cited.
 */
function underSection(
  document: NormalizedText,
  needle: string,
  section: string | undefined,
): CitationLocation {
  // The trail reads outermost first, so the most specific heading is the last one and is tried first.
  const trail = (section ?? "").split(HEADING_SEPARATOR).reverse();
  for (const level of trail) {
    const heading = normalizeForMatch(level).text;
    if (!heading) continue;
    const at = document.text.indexOf(heading);
    if (at < 0 || document.text.indexOf(heading, at + 1) >= 0) continue;
    const found = document.text.indexOf(needle, at + heading.length);
    if (found >= 0) return span(document, found, found + needle.length, "approximate");
  }
  return NOT_FOUND;
}

/**
 * The passage is not present verbatim, so its first and last words are searched for instead and the text
 * between them is taken. The span is accepted only when it is about as long as the passage: matching ends
 * far apart are two unrelated places that happen to begin and end alike, not one citation.
 */
function anchored(document: NormalizedText, needle: string): CitationLocation {
  const words = needle.split(" ").filter(Boolean);
  const take = Math.min(ANCHOR_WORDS, Math.floor(words.length / 2));
  if (take < MIN_ANCHOR_WORDS) return NOT_FOUND;
  const head = words.slice(0, take).join(" ");
  const tail = words.slice(-take).join(" ");
  const from = document.text.indexOf(head);
  if (from < 0) return NOT_FOUND;
  const tailAt = document.text.indexOf(tail, from + head.length);
  if (tailAt < 0) return NOT_FOUND;
  const to = tailAt + tail.length;
  const length = to - from;
  if (length < needle.length * (1 - LENGTH_TOLERANCE)) return NOT_FOUND;
  if (length > needle.length * (1 + LENGTH_TOLERANCE)) return NOT_FOUND;
  return span(document, from, to, "approximate");
}

/** What a reader searched for: the whole question and its words, each compared on its own. */
export type QueryTerms = { phrase: string; words: readonly string[] };

/** One marked occurrence, as offsets into the source; `whole` when it is the whole question. */
export type QueryMark = { start: number; end: number; whole: boolean };

/** How a passage answers a question: it holds the whole question, some of its words, or none of them. */
export type MatchKind = "exact" | "partial" | "none";

type Span = { start: number; end: number };

const WORD_CHARACTER = /[\p{L}\p{M}\p{N}]/u;
const folded = new WeakMap<NormalizedText, string>();

/**
 * Normalized text without its Vietnamese diacritics (`đ` read as `d`). Every character keeps its place, so an
 * offset in the folded text names the same character as in the text it was folded from.
 */
function fold(text: string): string {
  let out = "";
  for (const character of text) {
    const base =
      character === "đ" ? "d" : character.normalize("NFD").replace(/\p{M}/gu, "") || character;
    out += base.length === character.length ? base : character;
  }
  return out;
}

/**
 * Where the reader's question occurs in `document`, in reading order and without overlap, as offsets into the
 * source it was normalized from. The whole question is marked wherever it occurs; only when it occurs nowhere
 * in the span are its words marked, each on its own, the longer first where two start together. An occurrence
 * starts and ends at a word boundary. The comparison is the passage locator's (capitals, combining marks, soft
 * hyphens and line breaks do not hide a word), and a question typed without any diacritic is compared with the
 * text's diacritics removed, as the index's folded analyzer matched it. `within` limits the search to a span of
 * the source, such as a located passage.
 */
export function markQuery(document: NormalizedText, terms: QueryTerms, within?: Span): QueryMark[] {
  const phrase = normalizeForMatch(terms.phrase).text;
  if (!phrase || !document.text) return [];
  const loose = fold(phrase) === phrase;
  const prepare = (value: string) => {
    const normalized = normalizeForMatch(value).text;
    return loose ? fold(normalized) : normalized;
  };
  let text = document.text;
  if (loose) {
    text = folded.get(document) ?? fold(document.text);
    folded.set(document, text);
  }
  const whole = occurrences(document, text, [prepare(terms.phrase)], within);
  if (whole.length) return whole.map((span) => ({ ...span, whole: true }));
  const words = [...new Set(terms.words.map(prepare))]
    .filter(Boolean)
    .sort((left, right) => right.length - left.length);
  return occurrences(document, text, words, within).map((span) => ({ ...span, whole: false }));
}

/** How `document` (or its span `within`) answers the question. */
export function matchKind(document: NormalizedText, terms: QueryTerms, within?: Span): MatchKind {
  const [first] = markQuery(document, terms, within);
  if (!first) return "none";
  return first.whole ? "exact" : "partial";
}

function occurrences(
  document: NormalizedText,
  text: string,
  needles: readonly string[],
  within?: Span,
): Span[] {
  const found: Span[] = [];
  if (!needles.length) return found;
  const stop = within ? firstFrom(document.sources, within.end) : text.length;
  let at = within ? firstFrom(document.sources, within.start) : 0;
  const bounded = (from: number, to: number) =>
    !WORD_CHARACTER.test(text.charAt(from - 1)) && !WORD_CHARACTER.test(text.charAt(to));
  while (at < stop) {
    const from = at;
    const needle = needles.find(
      (candidate) =>
        from + candidate.length <= stop &&
        text.startsWith(candidate, from) &&
        bounded(from, from + candidate.length),
    );
    const start = document.sources[from];
    const end = needle ? document.ends[from + needle.length - 1] : undefined;
    if (!needle || start === undefined || end === undefined) {
      at += 1;
      continue;
    }
    found.push({ start, end });
    at += needle.length;
  }
  return found;
}

const TERMINATOR = /[.!?…;]/;
const LINE_BREAK = /[\n\r]/;
const SPACE = /\s/;

/** The longest focus a mark is widened to, in characters of the source. */
const MAX_SENTENCE = 320;

/**
 * The sentence a mark stands in, as offsets into `raw`: back to the previous terminator followed by a space, a
 * line break, a block start in `breaks` or the start of `within`, and forward to the next terminator, line break,
 * block start or the end of `within`. A sentence longer than `MAX_SENTENCE` is cut around the mark at word
 * boundaries, so a run-on paragraph does not turn the focus back into the whole passage.
 */
export function sentenceAround(
  raw: string,
  mark: Span,
  within: Span = { start: 0, end: raw.length },
  breaks: readonly number[] = [],
): Span {
  // A long document has a block start for every paragraph or row: too many to spread into Math.max.
  let lower = within.start;
  let upper = within.end;
  for (const at of breaks) {
    if (at <= mark.start && at > lower) lower = at;
    if (at >= mark.end && at < upper) upper = at;
  }
  let start = mark.start;
  while (start > lower) {
    const before = raw.charAt(start - 1);
    if (LINE_BREAK.test(before)) break;
    if (SPACE.test(before) && TERMINATOR.test(raw.charAt(start - 2))) break;
    start -= 1;
  }
  let end = mark.end;
  while (end < upper) {
    const character = raw.charAt(end);
    if (LINE_BREAK.test(character)) break;
    end += 1;
    if (TERMINATOR.test(character) && (end >= upper || SPACE.test(raw.charAt(end)))) break;
  }
  if (end - start > MAX_SENTENCE) {
    // Share the room around the mark evenly, giving one side what the other cannot use.
    const room = Math.max(0, MAX_SENTENCE - (mark.end - mark.start));
    let before = Math.min(mark.start - start, Math.floor(room / 2));
    const after = Math.min(end - mark.end, room - before);
    before = Math.min(mark.start - start, room - after);
    let from = mark.start - before;
    let to = mark.end + after;
    if (from > start) while (from < mark.start && !SPACE.test(raw.charAt(from - 1))) from += 1;
    if (to < end) while (to > mark.end && !SPACE.test(raw.charAt(to))) to -= 1;
    start = from;
    end = to;
  }
  while (start < mark.start && SPACE.test(raw.charAt(start))) start += 1;
  while (end > mark.end && SPACE.test(raw.charAt(end - 1))) end -= 1;
  return { start, end };
}

/** The first normalized character that starts at or after `offset` of the source. */
function firstFrom(sources: readonly number[], offset: number): number {
  let low = 0;
  let high = sources.length;
  while (low < high) {
    const middle = (low + high) >> 1;
    if ((sources[middle] ?? 0) < offset) low = middle + 1;
    else high = middle;
  }
  return low;
}

function span(
  document: NormalizedText,
  from: number,
  to: number,
  confidence: "exact" | "approximate",
): CitationLocation {
  const start = document.sources[from];
  const end = document.ends[to - 1];
  if (start === undefined || end === undefined) return NOT_FOUND;
  return { confidence, start, end };
}
