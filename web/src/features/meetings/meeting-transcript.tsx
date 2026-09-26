import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { ChevronDown, ChevronUp, FileDown, Star } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import type { MeetingRecorder, RecorderSnapshot } from "./meeting-recorder";
import type { MeetingTrack } from "./meeting-socket";
import { slug } from "./meeting-file-name";
import { exportTranscript, formatClock, type MeetingDetail } from "./meetings-api";
import { useRecorderValue } from "./recorder-state";
import { SpeakerChip } from "./speaker-chip";
import { speakerColor, speakerName } from "./speakers";
import { matches } from "./transcript-search";
import { Said } from "./transcript-text";
import { WordCorrection } from "./word-correction";

/** A line of one or two sentences; rows are measured once rendered, so this only seeds the scrollbar. */
const ESTIMATED_LINE = 64;
/** Lines kept rendered beyond the visible ones, so a flick of the wheel never shows empty space. */
const OVERSCAN = 8;
/** How close to the end still counts as reading the end, so new lines keep the view following them. */
const FOLLOW_SLACK = 48;

type Utterance = MeetingDetail["utterances"][number];

/** A request to bring one line into view; `seq` repeats a request for the same line. */
export type TranscriptTarget = { utteranceId: string; seq: number };

/**
 * The meeting as it was said, one line per utterance. Only the lines in view are rendered — an afternoon's
 * meeting is thousands of lines — so every way of reaching a line (a topic, a search hit, a minutes quote)
 * scrolls the list to it by index rather than looking for an element that may not exist yet. While the
 * meeting is being recorded the list follows new lines, unless the reader has scrolled up to read.
 */
export function Transcript({
  meeting,
  recorder,
  target,
  onStar,
}: {
  meeting: MeetingDetail;
  /** The recorder while this meeting is being recorded here; its unfinished sentences end the list. */
  recorder: MeetingRecorder | undefined;
  target?: TranscriptTarget;
  onStar: (utteranceId: string, starred: boolean) => void;
}) {
  const ui = useAppTranslation();
  const [query, setQuery] = useState("");
  const [starredOnly, setStarredOnly] = useState(false);
  const [at, setAt] = useState(0);
  const listening = useRecorderValue(recorder, (snapshot) => snapshot.phase === "recording");
  const speaking = useRecorderValue(
    recorder,
    (snapshot) => livePreviews(snapshot.previews).length > 0,
  );
  const starred = useMemo(() => new Set(meeting.starred), [meeting.starred]);
  const shown = useMemo(
    () =>
      starredOnly
        ? meeting.utterances.filter((utterance) => starred.has(utterance.id))
        : meeting.utterances,
    [meeting.utterances, starred, starredOnly],
  );
  // Each hit is numbered across the whole transcript, so the arrows can walk them in reading order.
  const { firstMatch, total } = useMemo(() => {
    let counted = 0;
    const first: number[] = [];
    for (const utterance of shown) {
      first.push(counted);
      counted += matches(utterance.text, query).length;
    }
    return { firstMatch: first, total: counted };
  }, [shown, query]);
  const current = total === 0 ? -1 : ((at % total) + total) % total;

  const scroller = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const count = shown.length;
  // The virtualizer hands out functions read during render; no memoized component receives them.
  // oxlint-disable-next-line react/incompatible-library
  const rows = useVirtualizer({
    count,
    getScrollElement: () => scroller.current,
    estimateSize: () => ESTIMATED_LINE,
    overscan: OVERSCAN,
    getItemKey: (index) => shown[index]?.id ?? index,
  });

  /** Keeps the newest line in view while recording, unless the reader scrolled away from the end. */
  const follow = useCallback(() => {
    const element = scroller.current;
    if (!listening || !following.current || !element) return;
    if (count > 0) rows.scrollToIndex(count - 1, { align: "end" });
    // The unfinished sentences sit below the list, so the view ends at the very bottom.
    requestAnimationFrame(() => {
      element.scrollTop = element.scrollHeight;
    });
  }, [listening, count, rows]);
  useEffect(follow, [follow]);

  // A search hit is reached in two steps: its line is scrolled into the rendered window, then the hit itself
  // is centred once its line has rendered, because one line can be longer than the view.
  const pendingHit = useRef<number>(undefined);
  useEffect(() => {
    if (pendingHit.current === undefined) return;
    const hit = document.getElementById(`meeting-match-${pendingHit.current}`);
    if (!hit) return;
    pendingHit.current = undefined;
    hit.scrollIntoView({ block: "center" });
  });

  const handled = useRef<number>(undefined);
  useEffect(() => {
    if (!target || handled.current === target.seq) return;
    const index = shown.findIndex((utterance) => utterance.id === target.utteranceId);
    if (index < 0 && starredOnly) {
      // The line asked for is not starred; the whole transcript is shown so it can be reached.
      setStarredOnly(false);
      return;
    }
    handled.current = target.seq;
    if (index < 0) return;
    following.current = false;
    rows.scrollToIndex(index, { align: "center" });
  }, [target, shown, starredOnly, rows]);

  /** The file is named after the meeting, so a folder of them reads as a folder of meetings. */
  async function take(format: "DOCX" | "PDF") {
    const file = await exportTranscript(meeting.id, format);
    const url = URL.createObjectURL(file);
    const link = Object.assign(window.document.createElement("a"), {
      href: url,
      download: `transcript-${slug(meeting.title)}.${format === "PDF" ? "pdf" : "docx"}`,
    });
    window.document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  function jump(step: number) {
    if (total === 0) return;
    const next = (((at + step) % total) + total) % total;
    setAt(next);
    let line = 0;
    while (line + 1 < firstMatch.length && firstMatch[line + 1]! <= next) line += 1;
    following.current = false;
    pendingHit.current = next;
    rows.scrollToIndex(line, { align: "center" });
  }

  function reach(utterance: Utterance) {
    const index = shown.indexOf(utterance);
    if (index < 0) return;
    following.current = false;
    rows.scrollToIndex(index, { align: "start" });
  }

  const tools = meeting.utterances.length > 0 && (
    <div className="flex flex-wrap items-center gap-2">
      <div className="flex items-center gap-1">
        <Input
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setAt(0);
          }}
          placeholder={ui("Tìm trong transcript")}
          aria-label={ui("Tìm trong transcript")}
          className="h-8 w-56"
        />
        {query.trim() !== "" && (
          <>
            <span className="text-xs text-content-muted tabular-nums">
              {total === 0 ? ui("Không thấy") : ui("{{at}}/{{total}}", { at: current + 1, total })}
            </span>
            <Button
              prominence="tertiary"
              size="sm"
              disabled={total === 0}
              aria-label={ui("Kết quả trước")}
              onClick={() => jump(-1)}
            >
              <ChevronUp aria-hidden="true" />
            </Button>
            <Button
              prominence="tertiary"
              size="sm"
              disabled={total === 0}
              aria-label={ui("Kết quả tiếp theo")}
              onClick={() => jump(1)}
            >
              <ChevronDown aria-hidden="true" />
            </Button>
          </>
        )}
      </div>
      {(starred.size > 0 || starredOnly) && (
        <Button
          prominence={starredOnly ? "secondary" : "tertiary"}
          size="sm"
          aria-pressed={starredOnly}
          onClick={() => setStarredOnly((only) => !only)}
        >
          <Star aria-hidden="true" className={starredOnly ? "fill-current" : undefined} />
          {ui("Câu đã đánh dấu ({{count}})", { count: starred.size })}
        </Button>
      )}
      <div className="ml-auto flex items-center gap-1">
        <Button prominence="tertiary" size="sm" onClick={() => void take("DOCX")}>
          <FileDown aria-hidden="true" />
          {ui("Tải Word")}
        </Button>
        <Button prominence="tertiary" size="sm" onClick={() => void take("PDF")}>
          <FileDown aria-hidden="true" />
          {ui("Tải PDF")}
        </Button>
      </div>
    </div>
  );

  if (meeting.utterances.length === 0 && !speaking)
    return (
      <p className="rounded-xl border border-dashed border-border-default px-4 py-8 text-center text-sm text-content-muted">
        {listening
          ? ui("Đang nghe…")
          : meeting.status === "TRANSCRIBING"
            ? ui("Transcript sẽ hiện khi nhận dạng xong.")
            : ui("Cuộc họp này chưa có transcript.")}
      </p>
    );
  const topics = meeting.minutes.topics.flatMap((topic) => {
    const line = meeting.utterances.find((utterance) => utterance.id === topic.sourceUtteranceId);
    return line ? [{ topic, line }] : [];
  });
  const newest = meeting.utterances.at(-1);
  const correctable = meeting.owned && meeting.status === "ENDED";

  return (
    <div className="grid gap-3">
      {topics.length > 0 && !starredOnly && (
        <nav
          aria-label={ui("Dòng thời gian")}
          className="rounded-xl border border-border-default p-2"
        >
          <h3 className="px-2 pt-1 pb-1.5 text-xs font-medium text-content-muted">
            {ui("Dòng thời gian")}
          </h3>
          <ol className="grid gap-0.5">
            {topics.map(({ topic, line }) => (
              <li key={topic.id}>
                <button
                  type="button"
                  className="grid w-full grid-cols-[4.5rem_1fr] gap-x-3 rounded-lg px-2 py-1.5 text-left text-sm hover:bg-surface-base"
                  onClick={() => reach(line)}
                >
                  <span className="font-mono text-xs text-content-muted tabular-nums">
                    {formatClock(line.startMs)}
                  </span>
                  <span className="text-content-primary">{topic.text}</span>
                </button>
              </li>
            ))}
          </ol>
        </nav>
      )}
      {tools}
      {shown.length === 0 && (
        <p className="text-sm text-content-muted">{ui("Chưa đánh dấu câu nào.")}</p>
      )}
      {/* Rendered lines come and go as the list scrolls, so new lines are announced here instead. */}
      <p className="sr-only" aria-live="polite">
        {listening && newest ? (
          <>
            <span>{speakerName(meeting, newest.track, newest.speaker, ui)}</span>{" "}
            <span>{newest.text}</span>
          </>
        ) : null}
      </p>
      <div
        ref={scroller}
        role="region"
        aria-label={ui("Transcript")}
        // The list scrolls on its own, so it is reachable and scrollable from the keyboard.
        tabIndex={0}
        className="max-h-[70vh] overflow-auto overscroll-contain rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
        onScroll={(event) => {
          const element = event.currentTarget;
          following.current =
            element.scrollHeight - element.scrollTop - element.clientHeight < FOLLOW_SLACK;
        }}
      >
        <ol className="relative" style={{ height: rows.getTotalSize() }}>
          {rows.getVirtualItems().map((item) => {
            const utterance = shown[item.index]!;
            const isStarred = starred.has(utterance.id);
            return (
              <li
                key={item.key}
                id={utterance.id}
                ref={rows.measureElement}
                data-index={item.index}
                aria-setsize={shown.length}
                aria-posinset={item.index + 1}
                className="absolute inset-x-0 top-0 pb-1"
                style={{ transform: `translateY(${item.start}px)` }}
              >
                <div className="grid grid-cols-[4.5rem_1fr_auto] gap-x-3 rounded-lg px-2 py-2 hover:bg-surface-base">
                  <span className="pt-0.5 font-mono text-xs text-content-muted tabular-nums">
                    {formatClock(utterance.startMs)}
                  </span>
                  <div className="min-w-0">
                    <SpeakerChip
                      meeting={meeting}
                      track={utterance.track}
                      label={utterance.speaker}
                    />
                    <p className="mt-0.5 text-content-secondary">
                      <Said
                        text={utterance.text}
                        spans={utterance.spans}
                        query={query}
                        firstMatch={firstMatch[item.index] ?? 0}
                        currentMatch={current}
                        unsure={
                          correctable
                            ? (span, mark) => (
                                <WordCorrection
                                  meeting={meeting}
                                  utteranceId={utterance.id}
                                  text={utterance.text}
                                  span={span}
                                >
                                  {mark}
                                </WordCorrection>
                              )
                            : undefined
                        }
                      />
                    </p>
                  </div>
                  <Button
                    prominence="tertiary"
                    size="sm"
                    className="self-start"
                    aria-pressed={isStarred}
                    aria-label={ui("Đánh dấu câu này")}
                    onClick={() => onStar(utterance.id, !isStarred)}
                  >
                    <Star
                      aria-hidden="true"
                      className={isStarred ? "fill-current" : "opacity-40"}
                    />
                  </Button>
                </div>
              </li>
            );
          })}
        </ol>
        {recorder && <LivePreviews meeting={meeting} recorder={recorder} onChange={follow} />}
      </div>
    </div>
  );
}

function livePreviews(previews: RecorderSnapshot["previews"]) {
  return (
    Object.entries(previews) as [MeetingTrack, { speaker: string; text: string } | undefined][]
  ).filter((entry): entry is [MeetingTrack, { speaker: string; text: string }] => !!entry[1]?.text);
}

/**
 * The sentences each track is still saying. They change with every word, so only this part of the
 * transcript follows them.
 */
function LivePreviews({
  meeting,
  recorder,
  onChange,
}: {
  meeting: MeetingDetail;
  recorder: MeetingRecorder;
  onChange: () => void;
}) {
  const ui = useAppTranslation();
  // The recorder replaces its previews only when a sentence changes, so this reference is stable in between.
  const previews = useRecorderValue(recorder, (snapshot) => snapshot.previews);
  const said = livePreviews(previews);
  // A sentence growing by a word lengthens the list, so the view is asked to keep following it.
  useLayoutEffect(onChange, [previews, onChange]);
  if (said.length === 0) return null;
  return (
    <ol>
      {said.map(([track, preview]) => (
        <li key={track} className="grid grid-cols-[4.5rem_1fr_auto] gap-x-3 px-2 py-2">
          <span className="pt-0.5 text-xs text-content-muted">{ui("đang nói")}</span>
          <div className="min-w-0">
            <span className="inline-flex items-center gap-1.5 text-sm font-medium text-content-muted">
              <span
                className={cn(
                  "size-2.5 rounded-full opacity-60",
                  speakerColor(meeting, track, preview.speaker || "1"),
                )}
                aria-hidden="true"
              />
              {speakerName(meeting, track, preview.speaker || "1", ui)}
            </span>
            <p className="mt-0.5 italic text-content-muted">{preview.text}…</p>
          </div>
        </li>
      ))}
    </ol>
  );
}
