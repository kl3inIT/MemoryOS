import { useEffect, useRef } from "react";
import { Bookmark } from "lucide-react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { formatClock, type TimelineEntry } from "./meetings-api";
import { scrollerOf } from "./use-follow-end";

type TimelineProps = {
  entries: TimelineEntry[];
  /** The entry the line at the top of the transcript belongs to; absent while the whole transcript is in view. */
  current: string | undefined;
  onReach: (utteranceId: string) => void;
};

/**
 * The subjects of the meeting and the moments the reader marked, as a table of contents in the meeting's panel:
 * each opens the transcript on its line, and the one being read is marked. A meeting with neither has no
 * timeline, unless one can still be started by marking a moment.
 */
export function TranscriptTimeline({
  marking,
  ...timeline
}: TimelineProps & {
  /** The meeting is being recorded here, so a moment can still be marked. */
  marking: boolean;
}) {
  const ui = useAppTranslation();
  if (timeline.entries.length === 0 && !marking) return null;
  return (
    <nav aria-label={ui("Dòng thời gian")} className="flex flex-col gap-2">
      <h3 className="text-xs font-medium text-content-muted">{ui("Dòng thời gian")}</h3>
      {timeline.entries.length > 0 ? (
        <TimelineList {...timeline} />
      ) : (
        <p className="text-sm text-content-muted">
          {ui("Bấm Đánh dấu để lưu lại một thời điểm và quay lại từ đây.")}
        </p>
      )}
    </nav>
  );
}

function TimelineList({ entries, current, onReach }: TimelineProps) {
  const list = useRef<HTMLOListElement>(null);

  // The panel scrolls when the entries outnumber its height; the marked one is kept inside it. Only the panel is
  // moved: scrolling the entry into view would move the page as well.
  useEffect(() => {
    const marked = list.current?.querySelector('[aria-current="true"]');
    if (!(marked instanceof HTMLElement)) return;
    const frame = scrollerOf(marked);
    const outer = frame.getBoundingClientRect();
    const inner = marked.getBoundingClientRect();
    if (inner.top < outer.top) frame.scrollTop -= outer.top - inner.top;
    else if (inner.bottom > outer.bottom) frame.scrollTop += inner.bottom - outer.bottom;
  }, [current]);

  return (
    <ol ref={list}>
      {entries.map((entry) => {
        const reading = entry.id === current;
        return (
          <li key={entry.id}>
            <button
              type="button"
              aria-current={reading ? "true" : undefined}
              className={cn(
                "flex w-full gap-3 border-l-2 py-1.5 pr-2 pl-3 text-left text-sm outline-none hover:bg-surface-base focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:ring-inset",
                reading
                  ? "border-content-primary bg-surface-base font-medium text-content-primary"
                  : "border-border-default text-content-secondary",
              )}
              onClick={() => onReach(entry.line.id)}
            >
              <span className="w-14 shrink-0 pt-0.5 font-mono text-xs font-normal text-content-muted tabular-nums">
                {formatClock(entry.atMs)}
              </span>
              <span className="min-w-0 flex-1">
                {entry.kind === "bookmark" && (
                  <Bookmark
                    className="mr-1.5 inline size-3.5 align-text-top text-content-muted"
                    aria-hidden="true"
                  />
                )}
                {entry.text}
              </span>
            </button>
          </li>
        );
      })}
    </ol>
  );
}
