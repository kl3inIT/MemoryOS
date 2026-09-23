import type { ReactNode } from "react";
import { uiLocale } from "@/i18n/format";
import { matches } from "./transcript-search";
import type { UtteranceSpan } from "./meeting-socket";

/** Plain text, a stretch the provider was unsure of, or a hit for what the reader is looking for. */
const PLAIN = 0;
const UNSURE = 1;
const MATCH = 2;

/**
 * The provider's own text, with the stretches it was unsure of marked and whatever the reader is searching for
 * picked out. A hit wins over an uncertain stretch where they overlap: the reader asked for one and not the other.
 */
export function Said({
  text,
  spans,
  query = "",
  firstMatch = 0,
  currentMatch = -1,
  unsure,
}: {
  text: string;
  spans: UtteranceSpan[];
  query?: string;
  /** The ordinal of this line's first hit among the whole transcript's, so each can be reached by name. */
  firstMatch?: number;
  currentMatch?: number;
  /** Wraps a marked stretch, so the owner can open it; left out, a mark is only a mark. */
  unsure?: (span: UtteranceSpan, mark: ReactNode) => ReactNode;
}) {
  const hits = matches(text, query);
  if (spans.length === 0 && hits.length === 0) return text;

  const kinds = new Array<number>(text.length).fill(PLAIN);
  const confidence = new Map<number, number>();
  for (const span of spans) {
    const start = Math.max(0, span.start);
    const end = Math.min(text.length, span.end);
    for (let at = start; at < end; at += 1) kinds[at] = UNSURE;
    if (start < end) confidence.set(start, span.confidence);
  }
  const ordinals = new Map<number, number>();
  hits.forEach((start, index) => {
    ordinals.set(start, firstMatch + index);
    for (let at = start; at < Math.min(text.length, start + query.trim().length); at += 1)
      kinds[at] = MATCH;
  });

  const parts: ReactNode[] = [];
  let from = 0;
  while (from < text.length) {
    let to = from + 1;
    while (to < text.length && kinds[to] === kinds[from]) to += 1;
    const piece = text.slice(from, to);
    if (kinds[from] === PLAIN) parts.push(piece);
    else if (kinds[from] === MATCH) {
      const ordinal = ordinals.get(from);
      parts.push(
        <mark
          key={from}
          id={ordinal === undefined ? undefined : `meeting-match-${ordinal}`}
          className={
            ordinal === currentMatch
              ? "rounded-sm bg-status-info-emphasis px-0.5 text-content-on-emphasis"
              : "rounded-sm bg-status-info-surface px-0.5 text-status-info-content"
          }
        >
          {piece}
        </mark>,
      );
    } else {
      const sure = confidence.get(from);
      const mark = (
        <mark
          key={from}
          className="rounded-sm bg-status-warning-surface px-0.5 text-status-warning-content"
          title={
            sure === undefined
              ? undefined
              : new Intl.NumberFormat(uiLocale(), { style: "percent" }).format(sure)
          }
        >
          {piece}
        </mark>
      );
      // A search hit can cut into a mark; only a whole mark is offered for correcting.
      const span = spans.find((entry) => entry.start === from && entry.end === to);
      parts.push(unsure && span ? <span key={from}>{unsure(span, mark)}</span> : mark);
    }
    from = to;
  }
  return parts;
}
