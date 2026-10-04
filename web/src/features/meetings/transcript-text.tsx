import type { ReactNode } from "react";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { matches } from "./transcript-search";
import type { UtteranceSpan } from "./meeting-socket";

/** Plain text, a stretch the provider was unsure of, one the owner changed, or a hit for what the reader seeks. */
const PLAIN = 0;
const UNSURE = 1;
const FIXED = 2;
const MATCH = 3;

/** Words that replaced what the transcriber heard, where they stand in the text now. */
export type Fix = { id: string; start: number; end: number };

const NO_FIXES: Fix[] = [];

// Coloured and underlined, not filled: seen at a glance, yet a meeting has many of these and they must not
// outweigh what was said.
const UNSURE_MARK =
  "bg-transparent font-medium text-status-warning-content underline decoration-dotted decoration-2 underline-offset-4";
const FIXED_MARK =
  "bg-transparent font-medium text-status-success-content underline decoration-2 underline-offset-4";

/**
 * What the two colours in the lines mean, each shown as the mark itself, with whatever acts on all of them at the
 * end of the row.
 */
export function MarkLegend({
  unsure,
  correctable,
  fixed,
  action,
}: {
  unsure: boolean;
  /** The owner may open a mark to write what was said. */
  correctable: boolean;
  /** How many changes are in force. */
  fixed: number;
  action?: ReactNode;
}) {
  const ui = useAppTranslation();
  if (!unsure && fixed === 0) return null;
  return (
    <div className="flex min-h-8 flex-wrap items-center gap-x-5 gap-y-1 px-2 text-xs text-content-muted">
      {unsure && (
        <span>
          <span className={UNSURE_MARK}>{ui("Chữ cam")}</span>{" "}
          {correctable ? ui("máy nghe chưa chắc, bấm để sửa") : ui("máy nghe chưa chắc")}
        </span>
      )}
      {fixed > 0 && (
        <span>
          <span className={FIXED_MARK}>{ui("Chữ xanh lá")}</span>{" "}
          {ui("đã sửa {{count}} chỗ, bấm để xem chữ cũ", { count: fixed })}
        </span>
      )}
      {action && <div className="ml-auto">{action}</div>}
    </div>
  );
}

/**
 * The provider's own text, with the stretches it was unsure of and the ones the owner changed marked, and whatever
 * the reader is searching for picked out. A hit wins over either where they overlap: the reader asked for it.
 */
export function Said({
  text,
  spans,
  query = "",
  firstMatch = 0,
  currentMatch = -1,
  unsure,
  fixes = NO_FIXES,
  fixed,
}: {
  text: string;
  spans: UtteranceSpan[];
  query?: string;
  /** The ordinal of this line's first hit among the whole transcript's, so each can be reached by name. */
  firstMatch?: number;
  currentMatch?: number;
  /** Wraps a marked stretch, so the owner can open it; left out, a mark is only a mark. */
  unsure?: (span: UtteranceSpan, mark: ReactNode) => ReactNode;
  fixes?: Fix[];
  /** Wraps changed words, so the owner can read what was heard there and take the change back. */
  fixed?: (fix: Fix, mark: ReactNode) => ReactNode;
}) {
  const hits = matches(text, query);
  if (spans.length === 0 && hits.length === 0 && fixes.length === 0) return text;

  const kinds = new Array<number>(text.length).fill(PLAIN);
  const confidence = new Map<number, number>();
  for (const span of spans) {
    const start = Math.max(0, span.start);
    const end = Math.min(text.length, span.end);
    for (let at = start; at < end; at += 1) kinds[at] = UNSURE;
    if (start < end) confidence.set(start, span.confidence);
  }
  for (const fix of fixes)
    for (let at = Math.max(0, fix.start); at < Math.min(text.length, fix.end); at += 1)
      kinds[at] = FIXED;
  const ordinals = new Map<number, number>();
  hits.forEach(({ start, end }, index) => {
    ordinals.set(start, firstMatch + index);
    for (let at = start; at < end; at += 1) kinds[at] = MATCH;
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
    } else if (kinds[from] === FIXED) {
      // Two changes side by side are two marks, each with its own way back.
      const fix = fixes.find((entry) => entry.start <= from && from < entry.end);
      if (fix) to = Math.min(to, fix.end);
      const mark = (
        <mark key={from} className={FIXED_MARK}>
          {text.slice(from, to)}
        </mark>
      );
      // A search hit can cut into a change; only a whole one is offered for taking back.
      const whole = fix?.start === from && fix.end === to;
      parts.push(fixed && fix && whole ? <span key={from}>{fixed(fix, mark)}</span> : mark);
    } else {
      const sure = confidence.get(from);
      const mark = (
        <mark
          key={from}
          className={UNSURE_MARK}
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
