import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { useMutation } from "@tanstack/react-query";
import { useVirtualizer } from "@tanstack/react-virtual";
import { ArrowDown, ChevronDown, ChevronUp, FileDown, Star } from "lucide-react";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Empty, EmptyDescription, EmptyHeader } from "@/components/ui/empty";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import type { MeetingRecorder, RecorderSnapshot } from "./meeting-recorder";
import type { MeetingTrack } from "./meeting-socket";
import { slug } from "@/lib/meeting-file-name";
import { exportMeetingTranscript } from "@/lib/hey-api/sdk.gen";
import {
  formatClock,
  saveDocument,
  type MeetingCorrection,
  type MeetingDetail,
  type TimelineEntry,
} from "./meetings-api";
import { useRecorderValue } from "./recorder-state";
import { SpeakerBadge } from "./speaker-chip";
import { speakerName } from "./speakers";
import { matches } from "./transcript-search";
import { AppliedCorrection } from "./applied-correction";
import { MarkLegend, Said } from "./transcript-text";
import { scrollerOf, useFollowEnd } from "./use-follow-end";
import { WordCorrection } from "./word-correction";

/** A line of one or two sentences; rows are measured once rendered, so this only seeds the scrollbar. */
const ESTIMATED_LINE = 40;
/** Lines kept rendered beyond the visible ones, so a flick of the wheel never shows empty space. */
const OVERSCAN = 8;
/** How far a line may run past the top of the window and still be the one being read. */
const READING_EDGE = 8;
/** Frames a jump waits for its line to render before giving up. */
const REVEAL_FRAMES = 10;

/** A dialog or a confirmation open over the page. */
const OPEN_DIALOG = '[role="dialog"], [role="alertdialog"]';

type Utterance = MeetingDetail["utterances"][number];

/** Whether a key was pressed in a place that takes text. */
function typing(target: EventTarget | null) {
  return (
    target instanceof HTMLElement &&
    (target.isContentEditable || target.matches("input, textarea, select"))
  );
}

/** A request to bring one line into view, and where in the window; `seq` repeats a request for the same line. */
export type TranscriptTarget = { utteranceId: string; block: "start" | "center"; seq: number };

/**
 * The meeting as it was said, one line per utterance; the lines one voice says in a row read as one turn under
 * its name (Otter, Fireflies, Lightfield). Only the lines in view are rendered — an afternoon's
 * meeting is thousands of lines — so every way of reaching a line (a topic, a search hit, a minutes quote)
 * scrolls the list to it by index rather than looking for an element that may not exist yet. The list has no
 * scrollbar of its own: it is read by scrolling the page, so a long transcript is never a window inside a
 * window. While the meeting is being recorded the page follows new lines, unless the reader has scrolled back;
 * a button that stays on screen then leads back to the newest one.
 */
export function Transcript({
  meeting,
  recorder,
  target,
  timeline,
  corrections,
  undoAll,
  toolsSlot,
  onReading,
  onStar,
}: {
  meeting: MeetingDetail;
  /** The recorder while this meeting is being recorded here; its unfinished sentences end the list. */
  recorder: MeetingRecorder | undefined;
  target?: TranscriptTarget;
  /** The subjects and marked moments of the meeting, in time order. */
  timeline: TimelineEntry[];
  /** The changes in force, each marked in the line it changed. */
  corrections: MeetingCorrection[];
  /** Takes every change of the last pass back; shown beside what the marks mean. */
  undoAll?: ReactNode;
  /** Where the search is shown: the part of the page that stays in view while the lines scroll. */
  toolsSlot: HTMLElement | null;
  /** Told which of them the line at the top of the window belongs to. */
  onReading: (entryId: string | undefined) => void;
  onStar: (utteranceId: string, starred: boolean) => void;
}) {
  const ui = useAppTranslation();
  const [query, setQuery] = useState("");
  const [starredOnly, setStarredOnly] = useState(false);
  const [at, setAt] = useState(0);
  const listening = useRecorderValue(recorder, (snapshot) => snapshot.phase === "recording");
  // A shared reader has no local recorder, but the server still streams its utterances while recording.
  const recording = meeting.status === "RECORDING";
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
  const matched = total === 0 ? -1 : ((at % total) + total) % total;

  // A shared reader's lines arrive from the server, the recorder's own from its socket: both lengthen the list.
  const { grows, stop, resume, away } = useFollowEnd(recording);
  const list = useRef<HTMLOListElement>(null);
  // The page the list is scrolled with, and how far down that page the list starts.
  const [page, setPage] = useState<{ scroller: HTMLElement; offset: number }>();
  // oxlint-disable-next-line react-hooks/exhaustive-deps -- measured after every render; set only when it moved.
  useLayoutEffect(() => {
    const element = list.current;
    if (!element) return;
    const scroller = scrollerOf(element);
    const offset = Math.round(
      element.getBoundingClientRect().top -
        scroller.getBoundingClientRect().top +
        scroller.scrollTop,
    );
    // Whatever sits above the list can change height on any render; the state settles once it stops moving.
    if (page?.scroller !== scroller || page.offset !== offset) setPage({ scroller, offset });
  });
  const count = shown.length;
  // The virtualizer hands out functions read during render; no memoized component receives them.
  // oxlint-disable-next-line react/incompatible-library
  const rows = useVirtualizer({
    count,
    getScrollElement: () => page?.scroller ?? null,
    scrollMargin: page?.offset ?? 0,
    estimateSize: () => ESTIMATED_LINE,
    overscan: OVERSCAN,
    getItemKey: (index) => shown[index]?.id ?? index,
  });

  // What is pinned over the top of the lines hides that much of them, so the line being read is the first one
  // below it: the same distance a line reached from the timeline stops at.
  const [pinned, setPinned] = useState(0);
  // The lines render once the page they scroll in is known, so there is one to measure only from then on.
  const rendered = rows.getVirtualItems().length > 0;
  // Measured from a line itself, so it follows the window's width and the recording bar, which wraps as it must.
  useLayoutEffect(() => {
    const measure = () => {
      const line = list.current?.firstElementChild;
      if (line) setPinned(Number.parseFloat(getComputedStyle(line).scrollMarginTop) || 0);
    };
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [rendered, count, recorder]);
  // A transcript that fits the window is all in view: no one place in the timeline is the one being read.
  const top = (rows.scrollOffset ?? 0) + pinned;
  const reading =
    rows.getTotalSize() > (rows.scrollRect?.height ?? 0)
      ? rows.getVirtualItems().find((item) => item.end > top + READING_EDGE)
      : undefined;
  const readingMs = reading && shown[reading.index]?.startMs;
  const current =
    readingMs === undefined
      ? undefined
      : timeline.findLast((entry) => entry.line.startMs <= readingMs)?.id;
  useEffect(() => onReading(current), [current, onReading]);
  // Another tab takes the transcript off the page, and nothing is being read any more.
  useEffect(() => () => onReading(undefined), [onReading]);

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
   * Brings one line into view in two steps: the page is scrolled until the line is rendered, then the line
   * itself is placed, since its height is only known once it is on the page.
   */
  const reveal = useCallback(
    (index: number, block: "start" | "center") => {
      const id = shown[index]?.id;
      if (id === undefined) return;
      stop();
      rows.scrollToIndex(index, { align: block });
      let frames = 0;
      const settle = () => {
        const line = document.getElementById(id);
        if (line) line.scrollIntoView({ block });
        else if (++frames < REVEAL_FRAMES) requestAnimationFrame(settle);
      };
      requestAnimationFrame(settle);
    },
    [shown, rows, stop],
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
    reveal(index, target.block);
  }, [target, shown, starredOnly, reveal]);

  function jump(step: number) {
    if (total === 0) return;
    const next = (((at + step) % total) + total) % total;
    setAt(next);
    let line = 0;
    while ((firstMatch[line + 1] ?? Infinity) <= next) line += 1;
    stop();
    pendingHit.current = next;
    rows.scrollToIndex(line, { align: "center" });
  }

  const tools = meeting.utterances.length > 0 && (
    <TranscriptTools
      download={<TranscriptDownload meeting={meeting} />}
      query={query}
      onQuery={(next) => {
        setQuery(next);
        setAt(0);
      }}
      total={total}
      current={matched}
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
  const unsure = meeting.utterances.some((utterance) => utterance.spans.length > 0);
  const fixes = Map.groupBy(corrections, (correction) => correction.utteranceId);

  return (
    <div className="grid grid-cols-1 gap-3">
      {toolsSlot && createPortal(tools, toolsSlot)}
      {shown.length === 0 && (
        <p className="text-sm text-content-muted">{ui("Chưa gắn sao câu nào.")}</p>
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
      <MarkLegend
        unsure={unsure}
        correctable={correctable}
        fixed={corrections.length}
        action={undoAll}
      />
      {/* The lines and what floats over them share one box, so nothing floats over the tools above. */}
      <div className="min-w-0">
        <div role="region" aria-label={ui("Transcript")}>
          {/* Finished lines and the sentences still being said grow together, and the page follows both. */}
          <div ref={grows} className="scroll-mb-6">
            <ol ref={list} className="relative" style={{ height: rows.getTotalSize() }}>
              {rows.getVirtualItems().map((item) => {
                const utterance = shown[item.index];
                if (!utterance) return null;
                // Starred lines stand apart in the meeting, so each one says whose it is.
                const before = starredOnly ? undefined : shown[item.index - 1];
                return (
                  <li
                    key={item.key}
                    id={utterance.id}
                    ref={rows.measureElement}
                    data-index={item.index}
                    aria-setsize={shown.length}
                    aria-posinset={item.index + 1}
                    // A line reached from the timeline stops clear of what is pinned above it: the shell header on a phone,
                    // and from md up the recording bar, the tabs and the search as well.
                    className="absolute inset-x-0 top-0 scroll-mt-16 md:scroll-mt-[calc(var(--meeting-pinned-top)+7rem)]"
                    style={{
                      transform: `translateY(${item.start - rows.options.scrollMargin}px)`,
                    }}
                  >
                    <TranscriptLine
                      meeting={meeting}
                      utterance={utterance}
                      continued={
                        before?.track === utterance.track && before.speaker === utterance.speaker
                      }
                      query={query}
                      firstMatch={firstMatch[item.index] ?? 0}
                      currentMatch={matched}
                      correctable={correctable}
                      fixes={fixes.get(utterance.id)}
                      starred={starred.has(utterance.id)}
                      onStar={onStar}
                    />
                  </li>
                );
              })}
            </ol>
            {recorder && <LivePreviews meeting={meeting} recorder={recorder} />}
          </div>
        </div>
        {/* The way back to what is being said stays at the foot of the window, over the lines. */}
        <div className="pointer-events-none sticky bottom-4 z-10 flex h-0 items-end justify-end gap-2 *:pointer-events-auto">
          {away && (
            <Button size="sm" prominence="secondary" onClick={resume}>
              <ArrowDown data-icon="inline-start" aria-hidden="true" />
              {ui("Mới nhất")}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}

/** Taking the transcript away as a document, in either format. */
function TranscriptDownload({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  /** The file is named after the meeting, so a folder of them reads as a folder of meetings. */
  const take = useMutation({
    mutationFn: async (format: "DOCX" | "PDF") =>
      (await exportMeetingTranscript({ path: { meetingId: meeting.id }, query: { format } })).data,
    onSuccess: (file, format) =>
      saveDocument(file, `transcript-${slug(meeting.title)}.${format === "PDF" ? "pdf" : "docx"}`),
  });
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button prominence="tertiary" size="sm" pending={take.isPending}>
          <FileDown data-icon="inline-start" aria-hidden="true" />
          {ui("Tải về")}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuGroup>
          <DropdownMenuItem onSelect={() => take.mutate("DOCX")}>{ui("Tải Word")}</DropdownMenuItem>
          <DropdownMenuItem onSelect={() => take.mutate("PDF")}>{ui("Tải PDF")}</DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Searching the transcript, showing only the starred lines, and taking it away as a document. */
function TranscriptTools({
  download,
  query,
  onQuery,
  total,
  current,
  onJump,
  starredCount,
  starredOnly,
  onStarredOnly,
}: {
  download: ReactNode;
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
  const field = useRef<HTMLInputElement>(null);
  // Only the lines in view are on the page, so the browser's own find misses most of a meeting: its shortcut
  // opens this search instead, as does "/" outside a field.
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const modified = event.ctrlKey || event.metaKey;
      const find = modified && !event.altKey && !event.shiftKey && event.key.toLowerCase() === "f";
      const slash = event.key === "/" && !modified && !event.altKey && !typing(event.target);
      // A dialog over the page keeps the keyboard to itself.
      if ((!find && !slash) || document.querySelector(OPEN_DIALOG)) return;
      event.preventDefault();
      field.current?.focus();
      field.current?.select();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
  return (
    <div className="flex flex-wrap items-center gap-2">
      <div className="flex min-w-48 flex-1 items-center gap-1">
        <Input
          ref={field}
          value={query}
          onChange={(event) => onQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              onJump(event.shiftKey ? -1 : 1);
            } else if (event.key === "Escape" && query !== "") {
              // The first Escape empties the search; the next one is the page's.
              event.stopPropagation();
              onQuery("");
            }
          }}
          placeholder={ui("Tìm trong transcript (bấm /)")}
          aria-label={ui("Tìm trong transcript")}
          aria-keyshortcuts="Control+F Meta+F /"
          size="sm"
          className="flex-1"
        />
        {query.trim() !== "" && (
          <>
            <span role="status" className="text-xs text-content-muted tabular-nums">
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
          {ui("Câu đã gắn sao ({{count}})", { count: starredCount })}
        </Button>
      )}
      {download}
    </div>
  );
}

/**
 * One line: what was said with its search hits, its star and when it was said. The line that opens a turn names
 * the voice; the ones that follow show their time only to whoever is on them.
 */
function TranscriptLine({
  meeting,
  utterance,
  continued,
  query,
  firstMatch,
  currentMatch,
  correctable,
  fixes,
  starred,
  onStar,
}: {
  meeting: MeetingDetail;
  utterance: Utterance;
  /** The changes in force on this line. */
  fixes: MeetingCorrection[] | undefined;
  /** The line before this one was said by the same voice. */
  continued: boolean;
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
    // A turn opens with its voice's badge in the margin and the name over what was said; the lines that follow sit
    // under the same name. When each line was said is kept at the right, out of the way of the words.
    <div
      className={cn(
        "group flex items-start gap-3 rounded-lg px-2 hover:bg-surface-base",
        !continued && "mt-4",
      )}
    >
      <span className="flex w-6 shrink-0 py-0.5">
        {!continued && (
          <SpeakerBadge meeting={meeting} track={utterance.track} label={utterance.speaker} />
        )}
      </span>
      <div className="min-w-0 flex-1 py-0.5">
        {!continued && (
          <p className="mb-0.5 text-xs font-medium text-content-muted">
            {speakerName(meeting, utterance.track, utterance.speaker, ui)}
          </p>
        )}
        <p className="text-content-primary">
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
            // A change is marked only where the line still reads as it left it.
            fixes={fixes
              ?.map(({ id, start, after }) => ({ id, start, end: start + after.length, after }))
              .filter(({ start, end, after }) => utterance.text.slice(start, end) === after)}
            fixed={(fix, mark) => {
              const correction = fixes?.find((entry) => entry.id === fix.id);
              return correction ? (
                <AppliedCorrection meetingId={meeting.id} correction={correction}>
                  {mark}
                </AppliedCorrection>
              ) : (
                mark
              );
            }}
          />
        </p>
      </div>
      <span
        className={cn(
          "py-1 font-mono text-xs text-content-muted tabular-nums",
          continued && hoverReveal,
        )}
      >
        {formatClock(utterance.startMs)}
      </span>
      {/* A starred line keeps its star in view; the others offer one to whoever is on the line. The star is
          taller than a line of text and must not set the distance between the lines of a turn. */}
      <span className={cn("-my-0.5 flex", !starred && hoverReveal)}>
        <IconButton
          size="sm"
          aria-pressed={starred}
          aria-label={ui("Gắn sao câu lúc {{time}}", { time: formatClock(utterance.startMs) })}
          onClick={() => onStar(utterance.id, !starred)}
        >
          <Star aria-hidden="true" className={starred ? "fill-current" : undefined} />
        </IconButton>
      </span>
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
}: {
  meeting: MeetingDetail;
  recorder: MeetingRecorder;
}) {
  const ui = useAppTranslation();
  // The recorder replaces its previews only when a sentence changes, so this reference is stable in between.
  const previews = useRecorderValue(recorder, (snapshot) => snapshot.previews);
  const said = livePreviews(previews);
  if (said.length === 0) return null;
  return (
    <ol>
      {said.map(([track, preview]) => (
        <li key={track} className="mt-4 flex items-start gap-3 px-2 py-0.5">
          <SpeakerBadge
            meeting={meeting}
            track={track}
            label={preview.speaker || "1"}
            className="opacity-60"
          />
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-2 text-sm text-content-muted">
              <span className="font-medium">
                {speakerName(meeting, track, preview.speaker || "1", ui)}
              </span>
              <span className="text-xs">{ui("đang nói")}</span>
            </p>
            <p className="text-content-muted italic">{preview.text}…</p>
          </div>
        </li>
      ))}
    </ol>
  );
}
