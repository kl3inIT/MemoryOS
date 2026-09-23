export type HighlightPart = {
  text: string;
  highlighted: boolean;
};

export type SearchSnippet = {
  parts: HighlightPart[];
  text: string;
};

const DEFAULT_SNIPPET_LENGTH = 320;

const FRIENDLY_MEDIA_TYPES: Record<string, string> = {
  "application/json": "JSON",
  "application/msword": "Word document",
  "application/pdf": "PDF",
  "application/vnd.ms-excel": "Excel spreadsheet",
  "application/vnd.ms-powerpoint": "PowerPoint presentation",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation":
    "PowerPoint presentation",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "Excel spreadsheet",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "Word document",
  "application/vnd.google-apps.document": "Google Docs",
  "application/vnd.google-apps.presentation": "Google Slides",
  "application/vnd.google-apps.spreadsheet": "Google Sheets",
  "text/csv": "CSV",
  "text/markdown": "Markdown",
  "text/x-markdown": "Markdown",
  "text/plain": "Text document",
};

export function friendlyMediaType(mediaType: string): string {
  const normalized = mediaType.split(";", 1)[0]?.trim().toLowerCase() ?? "";
  const known = FRIENDLY_MEDIA_TYPES[normalized];
  if (known) return known;
  if (normalized.startsWith("image/")) return "Image";
  if (normalized.startsWith("audio/")) return "Audio";
  if (normalized.startsWith("video/")) return "Video";
  return "Document";
}

export function stripGeneratedTitlePrefix(content: string, title: string): string {
  const lineBreak = content.indexOf("\n");
  const firstLine = (lineBreak === -1 ? content : content.slice(0, lineBreak)).replace(/\r$/, "");
  if (firstLine !== `Title: ${title}`) return content;
  return lineBreak === -1 ? "" : content.slice(lineBreak + 1);
}

/**
 * The passage without the header `StructuredDocumentChunker` writes in front of it: a `Title:` line and,
 * when the block sits under headings, a `Section:` line. The original document contains neither, so a
 * citation is located and shown without them. A long header is truncated at indexing time, so the lines are
 * recognised by how they start rather than by their whole value.
 */
export function passageBody(content: string): string {
  let body = content;
  if (body.startsWith("Title: ")) body = afterFirstLine(body);
  else return content;
  if (body.startsWith("Section: ")) body = afterFirstLine(body);
  return body;
}

/**
 * The heading trail the chunker recorded for this passage, which is the context a search result is read in.
 * The original document does not contain the line, so it is shown beside a citation rather than searched for.
 */
export function passageSection(content: string): string | undefined {
  if (!content.startsWith("Title: ")) return undefined;
  const rest = afterFirstLine(content);
  if (!rest.startsWith("Section: ")) return undefined;
  const lineBreak = rest.indexOf("\n");
  return (lineBreak === -1 ? rest : rest.slice(0, lineBreak)).slice("Section: ".length).trim();
}

function afterFirstLine(value: string): string {
  const lineBreak = value.indexOf("\n");
  return lineBreak === -1 ? "" : value.slice(lineBreak + 1);
}

export function createSearchSnippet(
  content: string,
  title: string,
  query: string,
  maxLength = DEFAULT_SNIPPET_LENGTH,
): SearchSnippet {
  const cleaned = stripGeneratedTitlePrefix(content, title).trim();
  if (!cleaned) return { text: "No preview text available.", parts: [] };

  const terms = highlightTerms(query);
  const anchor = firstLiteralMatch(cleaned, terms);
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

function highlightTerms(query: string): string[] {
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

function firstLiteralMatch(
  content: string,
  terms: string[],
): { index: number; text: string } | null {
  for (const term of terms) {
    const match = new RegExp(escapeRegExp(term), "iu").exec(content);
    if (match?.index !== undefined) return { index: match.index, text: match[0] };
  }
  return null;
}

function highlightLiteralTerms(text: string, terms: string[]): HighlightPart[] {
  if (!terms.length) return [{ text, highlighted: false }];

  const pattern = new RegExp(terms.map(escapeRegExp).join("|"), "giu");
  const parts: HighlightPart[] = [];
  let cursor = 0;
  for (const match of text.matchAll(pattern)) {
    const index = match.index;
    if (index > cursor) parts.push({ text: text.slice(cursor, index), highlighted: false });
    parts.push({ text: match[0], highlighted: true });
    cursor = index + match[0].length;
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

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
