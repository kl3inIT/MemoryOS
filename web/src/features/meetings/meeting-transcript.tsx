import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useVirtualizer } from "@tanstack/react-virtual";
import { ChevronDown, ChevronUp, FileDown, Star } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Empty, EmptyDescription, EmptyHeader } from "@/components/ui/empty";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import type { MeetingRecorder, RecorderSnapshot } from "./meeting-recorder";
import type { MeetingTrack } from "./meeting-socket";
import { slug } from "./meeting-file-name";
import { exportMeetingTranscript } from "@/lib/hey-api/sdk.gen";
import { formatClock, saveDocument, type MeetingDetail } from "./meetings-api";
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
/** Frames a jump waits for its line to render before giving up. */
const REVEAL_FRAMES = 10;

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

  /**
   * Brings one line into view in two steps: the list scrolls it into the rendered window, then the line is
   * scrolled into the page, since the list may sit below the fold and a short list never scrolls itself.
   */
  const reveal = useCallback(
    (index: number, block: "start" | "center") => {
      const id = shown[index]?.id;
      if (id === undefined) return;
      following.current = false;
      rows.scrollToIndex(index, { align: block });
      let frames = 0;
      const settle = () => {
        const line = document.getElementById(id);
        if (line) line.scrollIntoView({ block });
        else if (++frames < REVEAL_FRAMES) requestAnimationFrame(settle);
      };
      requestAnimationFrame(settle);
    },
    [shown, rows],
  );

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
    reveal(index, "center");
  }, [target, shown, starredOnly, reveal]);

  function jump(step: number) {
    if (total === 0) return;
    const next = (((at + step) % total) + total) % total;
    setAt(next);
    let line = 0;
    while ((firstMatch[line + 1] ?? Infinity) <= next) line += 1;
    following.current = false;
    pendingHit.current = next;
    rows.scrollToIndex(line, { align: "center" });
  }

  function reach(utterance: Utterance) {
    const index = shown.indexOf(utterance);
    if (index >= 0) reveal(index, "start");
  }

  const tools = meeting.utterances.length > 0 && (
    <TranscriptTools
      meeting={meeting}
      query={query}
      onQuery={(next) => {
        setQuery(next);
        setAt(0);
      }}
      total={total}
      current={current}
      onJump={jump}
      starredCount={starred.size}
      starredOnly={starredOnly}
      onStarredOnly={() => setStarredOnly((only) => !only)}
    />
  );

  if (meeting.utterances.length === 0 && !speaking)
    return (
      <Empty>
        <EmptyHeader>
          <EmptyDescription>
            {listening
              ? ui("Đang nghe…")
              : meeting.status === "TRANSCRIBING"
                ? ui("Transcript sẽ hiện khi nhận dạng xong.")
                : ui("Cuộc họp này chưa có transcript.")}
          </EmptyDescription>
        </EmptyHeader>
      </Empty>
    );
  const newest = meeting.utterances.at(-1);
  const correctable = meeting.owned && meeting.status === "ENDED";

  return (
    <div className="grid gap-3">
      {!starredOnly && <TranscriptTimeline meeting={meeting} onReach={reach} />}
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
        // oxlint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- a scrolling region must take focus.
        tabIndex={0}
        className="max-h-[calc(100dvh-16rem)] overflow-auto overscroll-contain rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
        onScroll={(event) => {
          const element = event.currentTarget;
          following.current =
            element.scrollHeight - element.scrollTop - element.clientHeight < FOLLOW_SLACK;
        }}
      >
        <ol className="relative" style={{ height: rows.getTotalSize() }}>
          {rows.getVirtualItems().map((item) => {
            const utterance = shown[item.index];
            if (!utterance) return null;
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
                <TranscriptLine
                  meeting={meeting}
                  utterance={utterance}
                  query={query}
                  firstMatch={firstMatch[item.index] ?? 0}
                  currentMatch={current}
                  correctable={correctable}
                  starred={starred.has(utterance.id)}
                  onStar={onStar}
                />
              </li>
            );
          })}
        </ol>
        {recorder && <LivePreviews meeting={meeting} recorder={recorder} onChange={follow} />}
      </div>
    </div>
  );
}

/** Searching the transcript, showing only the starred lines, and taking it away as a document. */
function TranscriptTools({
  meeting,
  query,
  onQuery,
  total,
  current,
  onJump,
  starredCount,
  starredOnly,
  onStarredOnly,
}: {
  meeting: MeetingDetail;
  query: string;
  onQuery: (query: string) => void;
  /** Search hits across the whole transcript, and the one in view. */
  total: number;
  current: number;
  onJump: (step: number) => void;
  starredCount: number;
  starredOnly: boolean;
  onStarredOnly: () => void;
}) {
  const ui = useAppTranslation();
  /** The file is named after the meeting, so a folder of them reads as a folder of meetings. */
  const take = useMutation({
    mutationFn: async (format: "DOCX" | "PDF") =>
      (await exportMeetingTranscript({ path: { meetingId: meeting.id }, query: { format } })).data,
    onSuccess: (file, format) =>
      saveDocument(file, `transcript-${slug(meeting.title)}.${format === "PDF" ? "pdf" : "docx"}`),
  });
  return (
    <div className="flex flex-wrap items-center gap-2">
      <div className="flex items-center gap-1">
        <Input
          value={query}
          onChange={(event) => onQuery(event.target.value)}
          placeholder={ui("Tìm trong transcript")}
          aria-label={ui("Tìm trong transcript")}
          size="sm"
          className="w-56"
        />
        {query.trim() !== "" && (
          <>
            <span className="text-xs text-content-muted tabular-nums">
              {total === 0 ? ui("Không thấy") : ui("{{at}}/{{total}}", { at: current + 1, total })}
            </span>
            <IconButton
              size="sm"
              disabled={total === 0}
              aria-label={ui("Kết quả trước")}
              onClick={() => onJump(-1)}
            >
              <ChevronUp />
            </IconButton>
            <IconButton
              size="sm"
              disabled={total === 0}
              aria-label={ui("Kết quả tiếp theo")}
              onClick={() => onJump(1)}
            >
              <ChevronDown />
            </IconButton>
          </>
        )}
      </div>
      {(starredCount > 0 || starredOnly) && (
        <Button
          prominence={starredOnly ? "secondary" : "tertiary"}
          size="sm"
          aria-pressed={starredOnly}
          onClick={onStarredOnly}
        >
          <Star
            data-icon="inline-start"
            aria-hidden="true"
            className={starredOnly ? "fill-current" : undefined}
          />
          {ui("Câu đã đánh dấu ({{count}})", { count: starredCount })}
        </Button>
      )}
      <div className="ml-auto flex items-center gap-1">
        <Button
          prominence="tertiary"
          size="sm"
          pending={take.isPending && take.variables === "DOCX"}
          disabled={take.isPending}
          onClick={() => take.mutate("DOCX")}
        >
          <FileDown data-icon="inline-start" aria-hidden="true" />
          {ui("Tải Word")}
        </Button>
        <Button
          prominence="tertiary"
          size="sm"
          pending={take.isPending && take.variables === "PDF"}
          disabled={take.isPending}
          onClick={() => take.mutate("PDF")}
        >
          <FileDown data-icon="inline-start" aria-hidden="true" />
          {ui("Tải PDF")}
        </Button>
      </div>
    </div>
  );
}

/** The topics of the minutes, each opening the transcript on the line it starts at. */
function TranscriptTimeline({
  meeting,
  onReach,
}: {
  meeting: MeetingDetail;
  onReach: (line: Utterance) => void;
}) {
  const ui = useAppTranslation();
  const topics = meeting.minutes.topics.flatMap((topic) => {
    const line = meeting.utterances.find((utterance) => utterance.id === topic.sourceUtteranceId);
    return line ? [{ topic, line }] : [];
  });
  if (topics.length === 0) return null;
  return (
    <nav aria-label={ui("Dòng thời gian")} className="rounded-xl border border-border-default p-2">
      <h3 className="px-2 pt-1 pb-1.5 text-xs font-medium text-content-muted">
        {ui("Dòng thời gian")}
      </h3>
      <ol className="grid gap-0.5">
        {topics.map(({ topic, line }) => (
          <li key={topic.id}>
            <button
              type="button"
              className="flex w-full gap-3 rounded-lg px-2 py-1.5 text-left text-sm hover:bg-surface-base"
              onClick={() => onReach(line)}
            >
              <span className="w-18 shrink-0 font-mono text-xs text-content-muted tabular-nums">
                {formatClock(line.startMs)}
              </span>
              <span className="min-w-0 flex-1 text-content-primary">{topic.text}</span>
            </button>
          </li>
        ))}
      </ol>
    </nav>
  );
}

/** One line: when it was said, who said it, what they said with its search hits, and its star. */
function TranscriptLine({
  meeting,
  utterance,
  query,
  firstMatch,
  currentMatch,
  correctable,
  starred,
  onStar,
}: {
  meeting: MeetingDetail;
  utterance: Utterance;
  query: string;
  firstMatch: number;
  currentMatch: number;
  /** The owner of an ended meeting may write what an unclear word was. */
  correctable: boolean;
  starred: boolean;
  onStar: (utteranceId: string, starred: boolean) => void;
}) {
  const ui = useAppTranslation();
  return (
    <div className="flex gap-3 rounded-lg px-2 py-2 hover:bg-surface-base">
      <span className="w-18 shrink-0 pt-0.5 font-mono text-xs text-content-muted tabular-nums">
        {formatClock(utterance.startMs)}
      </span>
      <div className="min-w-0 flex-1">
        <SpeakerChip meeting={meeting} track={utterance.track} label={utterance.speaker} />
        <p className="mt-0.5 text-content-secondary">
          <Said
            text={utterance.text}
            spans={utterance.spans}
            query={query}
            firstMatch={firstMatch}
            currentMatch={currentMatch}
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
      <IconButton
        size="sm"
        className="shrink-0 self-start"
        aria-pressed={starred}
        aria-label={ui("Đánh dấu câu này")}
        onClick={() => onStar(utterance.id, !starred)}
      >
        <Star aria-hidden="true" className={starred ? "fill-current" : "opacity-40"} />
      </IconButton>
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
        <li key={track} className="flex gap-3 px-2 py-2">
          <span className="w-18 shrink-0 pt-0.5 text-xs text-content-muted">{ui("đang nói")}</span>
          <div className="min-w-0 flex-1">
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
