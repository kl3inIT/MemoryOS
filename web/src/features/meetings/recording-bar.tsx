import { Bookmark, Pause, Play, Square } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { DotMatrix } from "@/components/ui/dot-matrix";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import type { MeetingRecorder } from "./meeting-recorder";
import type { MeetingTrack } from "./meeting-socket";
import { formatClock, type MeetingKind } from "./meetings-api";
import { useRecorderValue } from "./recorder-state";

/** The time recorded so far; it is the only part of the page that follows the recorder's clock. */
export function RecordingClock({ recorder }: { recorder: MeetingRecorder }) {
  // Formatted inside the selector, so the clock repaints once a second rather than on every level reading.
  return <>{useRecorderValue(recorder, (snapshot) => formatClock(snapshot.elapsedMs))}</>;
}

/**
 * The live controls pinned above a meeting being recorded. The bar follows the recorder's phase; the clock
 * and each level meter subscribe on their own, so a level reading repaints a meter and nothing else.
 */
export function RecordingBar({
  recorder,
  kind,
  onStop,
  onBookmark,
}: {
  recorder: MeetingRecorder;
  kind: MeetingKind;
  onStop: () => Promise<void>;
  onBookmark: (atMs: number) => void;
}) {
  const ui = useAppTranslation();
  const phase = useRecorderValue(recorder, (snapshot) => snapshot.phase);
  const reconnecting = useRecorderValue(recorder, (snapshot) =>
    snapshot.tracks.some((track) => track.reconnecting),
  );
  // Joined, so the list is a value that only changes when a track is added.
  const tracks = useRecorderValue(recorder, (snapshot) =>
    snapshot.tracks.map((track) => track.track).join(","),
  );
  const paused = phase === "paused";
  return (
    <div className="sticky top-0 z-10 flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-border-subtle bg-surface-raised px-(--page-gutter) py-2.5">
      <DotMatrix
        state={paused ? "paused" : reconnecting ? "connecting" : "recording"}
        label={paused ? ui("Tạm dừng") : reconnecting ? ui("Đang kết nối lại") : ui("Đang ghi")}
        className="size-5"
      />
      <span
        className="font-mono text-base tabular-nums"
        role="timer"
        aria-label={ui("Thời gian ghi")}
      >
        <RecordingClock recorder={recorder} />
      </span>
      <span className="text-xs text-content-muted">
        {phase === "stopping"
          ? ui("Đang lưu phần cuối…")
          : paused
            ? ui("Tạm dừng")
            : ui("Đang ghi")}
      </span>
      <Button
        prominence="tertiary"
        size="sm"
        disabled={phase === "stopping"}
        onClick={() => onBookmark(recorder.getSnapshot().elapsedMs)}
      >
        <Bookmark data-icon="inline-start" aria-hidden="true" />
        {ui("Đánh dấu")}
      </Button>
      {tracks
        .split(",")
        .filter(Boolean)
        .map((track) => (
          <Meter
            key={track}
            recorder={recorder}
            track={track as MeetingTrack}
            paused={paused}
            label={track === "TAB" ? ui("Tab họp") : kind === "ONLINE" ? ui("Bạn") : ui("Micro")}
          />
        ))}
      <span className="flex-1" />
      {paused ? (
        <Button size="sm" prominence="secondary" onClick={() => void recorder.resume()}>
          <Play data-icon="inline-start" aria-hidden="true" />
          {ui("Tiếp tục")}
        </Button>
      ) : (
        <Button
          size="sm"
          prominence="secondary"
          disabled={phase !== "recording"}
          onClick={() => void recorder.pause()}
        >
          <Pause data-icon="inline-start" aria-hidden="true" />
          {ui("Tạm dừng")}
        </Button>
      )}
      <ConfirmDialog
        trigger={
          <Button size="sm" tone="danger" disabled={phase === "stopping"}>
            <Square data-icon="inline-start" aria-hidden="true" />
            {ui("Dừng")}
          </Button>
        }
        title={ui("Dừng ghi và kết thúc cuộc họp?")}
        description={<StoppedAt recorder={recorder} />}
        confirmLabel={ui("Dừng và kết thúc")}
        pendingLabel={ui("Đang lưu phần cuối…")}
        confirmTone="danger"
        onConfirm={onStop}
      />
    </div>
  );
}

function StoppedAt({ recorder }: { recorder: MeetingRecorder }) {
  const ui = useAppTranslation();
  const time = useRecorderValue(recorder, (snapshot) => formatClock(snapshot.elapsedMs));
  return ui("Đã lưu đến {{time}}. Sau khi dừng, cuộc họp không ghi tiếp được.", { time });
}

/** Rising bars, from 40% of the meter's height to all of it. */
const BAR_HEIGHTS = ["h-2/5", "h-11/20", "h-7/10", "h-17/20", "h-full"];
const BARS = BAR_HEIGHTS.length;

function Meter({
  recorder,
  track,
  paused,
  label,
}: {
  recorder: MeetingRecorder;
  track: MeetingTrack;
  paused: boolean;
  label: string;
}) {
  // RMS rarely exceeds 0.3 for speech; the meter is only a sign of life, so it repaints only when a bar changes.
  const lit = useRecorderValue(recorder, (snapshot) => {
    const level = snapshot.tracks.find((entry) => entry.track === track)?.level ?? 0;
    return Math.round(Math.min(1, level / 0.15) * BARS);
  });
  const shown = paused ? 0 : lit;
  return (
    <span
      className="inline-flex items-center gap-1.5 text-xs text-content-secondary"
      aria-label={label}
    >
      {label}
      <span className="flex h-3.5 items-end gap-0.5" aria-hidden="true">
        {Array.from({ length: BARS }, (_, index) => (
          <span
            key={index}
            className={cn(
              "w-1 rounded-sm",
              BAR_HEIGHTS[index],
              index < shown ? "bg-status-success-strong" : "bg-surface-strong",
            )}
          />
        ))}
      </span>
    </span>
  );
}
