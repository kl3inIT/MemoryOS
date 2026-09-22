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
    if (IGNORED.test(cluster[0]!)) continue;
    if (WHITESPACE.test(cluster[0]!)) {
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
