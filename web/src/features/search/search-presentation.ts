import { passageSection } from "@/features/documents/passage";
import { readSourceLocation } from "@/features/documents/source-provenance";
import {
  markQuery,
  matchKind,
  normalizeForMatch,
  type MatchKind,
  type QueryTerms,
} from "@/features/preview/preview-highlight";
import type { Section } from "@/lib/hey-api/types.gen";

type HighlightPart = {
  text: string;
  /** `phrase` for the whole question, `word` for one of its words alone; absent for plain text. */
  mark?: "phrase" | "word";
};

export type SearchSnippet = {
  parts: HighlightPart[];
  text: string;
};

const DEFAULT_SNIPPET_LENGTH = 320;

/**
 * What a question marks: the whole question, and each of its words of two characters or more for a passage
 * that does not hold the whole question. The card's excerpt and the opened original mark by the same rule.
 */
export function queryTerms(query: string): QueryTerms {
  // Composed first: a word pasted with combining marks would otherwise be cut before each mark.
  const phrase = query.normalize("NFC").trim();
  const tokens = phrase.match(/[\p{L}\p{N}]+(?:[-_.][\p{L}\p{N}]+)*/gu) ?? [];
  const words = new Map<string, string>();
  for (const token of tokens) {
    if (Array.from(token).length < 2) continue;
    const key = token.toLocaleLowerCase();
    if (!words.has(key)) words.set(key, token);
  }
  return { phrase, words: [...words.values()] };
}

/**
 * The passage a card shows: the section's text without the `Title:` and `Section:` lines the chunker wrote in
 * front of each of its chunks. A section joins consecutive chunks, so the lines recur inside it; the heading is
 * shown beside the excerpt instead.
 */
function passageText(content: string, title: string): string {
  const header = `Title: ${title}`;
  const lines = content.split("\n");
  const kept: string[] = [];
  for (let index = 0; index < lines.length; index += 1) {
    if (lines[index]?.replace(/\r$/, "") !== header) {
      kept.push(lines[index] ?? "");
      continue;
    }
    if (lines[index + 1]?.startsWith("Section: ")) index += 1;
  }
  return kept.join("\n").trim();
}

export function createSearchSnippet(
  content: string,
  title: string,
  query: string,
  maxLength = DEFAULT_SNIPPET_LENGTH,
): SearchSnippet {
  const cleaned = passageText(content, title);
  if (!cleaned) return { text: "No preview text available.", parts: [] };

  const terms = queryTerms(query);
  const [anchor] = markQuery(normalizeForMatch(cleaned), terms);
  const codePoints = Array.from(cleaned);
  const safeMaxLength = Math.max(80, maxLength);

  let start = 0;
  if (codePoints.length > safeMaxLength && anchor) {
    const anchorStart = Array.from(cleaned.slice(0, anchor.start)).length;
    const anchorLength = Array.from(cleaned.slice(anchor.start, anchor.end)).length;
    start = Math.max(0, anchorStart - Math.floor((safeMaxLength - anchorLength) / 2));
    start = Math.min(start, codePoints.length - safeMaxLength);
  }

  let end = Math.min(codePoints.length, start + safeMaxLength);
  start = moveStartToWordBoundary(codePoints, start);
  end = moveEndToWordBoundary(codePoints, end, start);

  const leadingEllipsis = start > 0 ? "…" : "";
  const trailingEllipsis = end < codePoints.length ? "…" : "";
  const excerpt = codePoints.slice(start, end).join("").trim();
  const text = `${leadingEllipsis}${excerpt}${trailingEllipsis}`;

  return { text, parts: highlightParts(text, terms) };
}

/** How a section answers the question: the whole question, some of its words, or only its meaning. */
export function sectionMatchKind(content: string, title: string, query: string): MatchKind {
  return matchKind(normalizeForMatch(passageText(content, title)), queryTerms(query));
}

/** Where a section's matching chunk sits in its document, as far as the extraction recorded it. */
export type SectionPlace = { page?: number; sheet?: string; heading?: string };

/**
 * The page or sheet of the section's matching chunk, and the last heading of the trail the chunker recorded
 * for it. A heading trail reads outermost first, so its last part is the one the passage sits under.
 */
export function sectionPlace(section: Section): SectionPlace {
  const location = readSourceLocation(matchingChunkProvenance(section));
  const trail = matchingChunkContent(section);
  const heading = (trail && passageSection(trail))?.split(" > ").at(-1)?.trim();
  return {
    page: location.pages[0],
    sheet: location.sheet,
    heading: heading && heading !== location.sheet ? heading : undefined,
  };
}

/**
 * The text of the chunk that matched. A section joins its chunks in order, each led by the chunker's `Title:`
 * line; when that does not split it into one part a chunk, which part matched is unknown and nothing is
 * returned, rather than another chunk's heading.
 */
function matchingChunkContent(section: Section): string | undefined {
  const chunks = section.content.split(/\n(?=Title: )/);
  if (chunks.length !== section.endOrdinal - section.startOrdinal + 1) return undefined;
  return chunks[section.matchingOrdinal - section.startOrdinal];
}

/**
 * The provenance of the chunk that matched, not of its neighbours in the section: they would be marked as if
 * they had matched too. A section that records nothing for its matching chunk keeps all of its provenance.
 */
export function matchingChunkProvenance(section: Section): string[] {
  const matching = section.provenance.filter((chunk) => chunk.ordinal === section.matchingOrdinal);
  return (matching.length ? matching : section.provenance).map((chunk) => chunk.provenanceJson);
}

function highlightParts(text: string, terms: QueryTerms): HighlightPart[] {
  const parts: HighlightPart[] = [];
  let cursor = 0;
  for (const match of markQuery(normalizeForMatch(text), terms)) {
    if (match.start > cursor) parts.push({ text: text.slice(cursor, match.start) });
    parts.push({
      text: text.slice(match.start, match.end),
      mark: match.whole ? "phrase" : "word",
    });
    cursor = match.end;
  }
  if (cursor < text.length) parts.push({ text: text.slice(cursor) });
  return parts.length ? parts : [{ text }];
}

function moveStartToWordBoundary(codePoints: string[], start: number): number {
  if (start === 0) return start;
  const limit = Math.min(codePoints.length, start + 24);
  for (let index = start; index < limit; index += 1) {
    if (/\s/u.test(codePoints[index] ?? "")) return index + 1;
  }
  return start;
}

function moveEndToWordBoundary(codePoints: string[], end: number, start: number): number {
  if (end === codePoints.length) return end;
  const limit = Math.max(start + 80, end - 24);
  for (let index = end; index > limit; index -= 1) {
    if (/\s/u.test(codePoints[index - 1] ?? "")) return index - 1;
  }
  return end;
}
