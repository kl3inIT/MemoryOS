import type { ReactNode } from "react";
import type { UtteranceSpan } from "./meeting-socket";

/** The provider's own text, with the stretches it was unsure of marked. */
export function Said({ text, spans }: { text: string; spans: UtteranceSpan[] }) {
  if (spans.length === 0) return text;
  const parts: ReactNode[] = [];
  let at = 0;
  for (const [index, span] of spans.entries()) {
    const start = Math.max(at, span.start);
    const end = Math.min(text.length, span.end);
    if (start >= end) continue;
    if (start > at) parts.push(text.slice(at, start));
    parts.push(
      <mark
        key={index}
        className="rounded-sm bg-status-warning-surface px-0.5 text-status-warning-content"
        title={`${Math.round(span.confidence * 100)}%`}
      >
        {text.slice(start, end)}
      </mark>,
    );
    at = end;
  }
  if (at < text.length) parts.push(text.slice(at));
  return parts;
}
