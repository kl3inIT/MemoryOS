import { stripGeneratedTitlePrefix } from "@/features/documents/passage";
import {
  matchTerms,
  normalizeForMatch,
  type NormalizedText,
} from "@/features/preview/preview-highlight";

type HighlightPart = {
  text: string;
  highlighted: boolean;
};

export type SearchSnippet = {
  parts: HighlightPart[];
  text: string;
};

const DEFAULT_SNIPPET_LENGTH = 320;

export function createSearchSnippet(
  content: string,
  title: string,
  query: string,
  maxLength = DEFAULT_SNIPPET_LENGTH,
): SearchSnippet {
  const cleaned = stripGeneratedTitlePrefix(content, title).trim();
  if (!cleaned) return { text: "No preview text available.", parts: [] };

  const terms = highlightTerms(query);
  const anchor = firstLiteralMatch(normalizeForMatch(cleaned), cleaned, terms);
  const codePoints = Array.from(cleaned);
  const safeMaxLength = Math.max(80, maxLength);

  let start = 0;
  if (codePoints.length > safeMaxLength && anchor) {
    const anchorStart = Array.from(cleaned.slice(0, anchor.index)).length;
    const anchorLength = Array.from(anchor.text).length;
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

  return { text, parts: highlightLiteralTerms(text, terms) };
}

/**
 * What a query marks in a passage: the whole question and each of its words of two characters or more,
 * longest first. The card's excerpt and the opened original mark the same terms by the same comparison.
 */
export function highlightTerms(query: string): string[] {
  const fullQuery = query.trim();
  if (!fullQuery) return [];

  const tokens = fullQuery.match(/[\p{L}\p{N}]+(?:[-_.][\p{L}\p{N}]+)*/gu) ?? [];
  const unique = new Map<string, string>();
  for (const term of [fullQuery, ...tokens.filter((token) => Array.from(token).length >= 2)]) {
    const key = term.toLocaleLowerCase();
    if (!unique.has(key)) unique.set(key, term);
  }
  return [...unique.values()].sort((left, right) => right.length - left.length);
}

/** The first place the longest term that occurs at all is found: the whole question before any one word. */
function firstLiteralMatch(
  normalized: NormalizedText,
  content: string,
  terms: string[],
): { index: number; text: string } | null {
  for (const term of terms) {
    const [match] = matchTerms(normalized, [term]);
    if (match) return { index: match.start, text: content.slice(match.start, match.end) };
  }
  return null;
}

function highlightLiteralTerms(text: string, terms: string[]): HighlightPart[] {
  const parts: HighlightPart[] = [];
  let cursor = 0;
  for (const match of matchTerms(normalizeForMatch(text), terms)) {
    if (match.start > cursor) {
      parts.push({ text: text.slice(cursor, match.start), highlighted: false });
    }
    parts.push({ text: text.slice(match.start, match.end), highlighted: true });
    cursor = match.end;
  }
  if (cursor < text.length) parts.push({ text: text.slice(cursor), highlighted: false });
  return parts.length ? parts : [{ text, highlighted: false }];
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
