import { useEffect, useRef, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Undo2, WandSparkles, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { uiLocale } from "@/i18n/format";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { Said } from "./transcript-text";
import {
  acceptAllCorrections,
  acceptCorrection,
  correctionsKey,
  keepWording,
  loadCorrections,
  loadMeeting,
  meetingKey,
  proposeCorrections,
  revertAllCorrections,
  revertCorrection,
  type MeetingDetail,
} from "./meetings-api";

/**
 * The owner asks a model about every stretch the speech provider was unsure of, then decides each answer. The
 * transcript changes only where they say so, and every change can be taken back.
 *
 * Returns the button that starts a pass, which the page puts beside the tabs as Otter puts its template picker, and
 * the proposals, which stay above the transcript they change. Both are empty when {@code enabled} is false.
 */
function useTranscriptCorrections(meeting: MeetingDetail, enabled: boolean) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const [error, setError] = useState<string | null>(null);
  const [wording, setWording] = useState<Record<string, string>>({});
  const [found, setFound] = useState<number | null>(null);
  // A pass runs on the server whether or not this page is still open, so whether one is running comes from the
  // meeting itself. Leaving and coming back shows it still running, and the button stays shut until it is done.
  const running = meeting.correcting;
  useQuery({
    queryKey: meetingKey(meeting.id),
    queryFn: ({ signal }) => loadMeeting(meeting.id, signal),
    refetchInterval: running ? 3000 : false,
    enabled,
  });
  const corrections = useQuery({
    queryKey: correctionsKey(meeting.id),
    queryFn: ({ signal }) => loadCorrections(meeting.id, signal),
    enabled,
  });
  const wasRunning = useRef(running);
  useEffect(() => {
    // A pass started elsewhere has just finished: its proposals are waiting to be read.
    if (wasRunning.current && !running)
      void cache.invalidateQueries({ queryKey: correctionsKey(meeting.id) });
    wasRunning.current = running;
  }, [running, cache, meeting.id]);

  const run = useMutation({
    mutationFn: () => {
      setFound(null);
      // Mark it running straight away, so the button shuts even before the server answers.
      cache.setQueryData<MeetingDetail>(meetingKey(meeting.id), (current) =>
        current ? { ...current, correcting: true } : current,
      );
      return proposeCorrections(meeting.id);
    },
    onSuccess: (result) => setFound(result.corrections.length),
    onSettled: () =>
      Promise.all([
        cache.invalidateQueries({ queryKey: correctionsKey(meeting.id) }),
        cache.invalidateQueries({ queryKey: meetingKey(meeting.id) }),
      ]),
  });
  const decide = useMutation({
    mutationFn: (act: () => Promise<MeetingDetail>) => act(),
    onSuccess: (detail) => {
      cache.setQueryData(meetingKey(meeting.id), detail);
      return cache.invalidateQueries({ queryKey: correctionsKey(meeting.id) });
    },
  });

  async function guard(work: () => Promise<unknown>) {
    setError(null);
    try {
      await work();
    } catch (failed) {
      setError(problemMessage(presentProblem(failed, "mutation").message));
    }
  }

  const all = corrections.data ?? [];
  const pending = all.filter((item) => item.status === "PENDING");
  const applied = all.filter((item) => item.status === "ACCEPTED");
  const busy = running || run.isPending || decide.isPending;
  // What a pass would look at: every stretch the provider marked, on lines nobody has rewritten by hand.
  const unclear = meeting.utterances
    .filter((utterance) => utterance.editSource !== "HUMAN")
    .reduce((count, utterance) => count + utterance.spans.length, 0);
  if (!enabled || (unclear === 0 && all.length === 0 && !running))
    return { trigger: null, panel: null };
  const runId = pending[0]?.runId;
  const appliedRun = applied.at(-1)?.runId;

  const trigger = (unclear > 0 || running) && (
    <Button
      prominence="secondary"
      size="sm"
      disabled={busy}
      onClick={() => guard(() => run.mutateAsync())}
    >
      <WandSparkles aria-hidden="true" className={running ? "animate-pulse" : undefined} />
      {running
        ? ui("Đang hiệu chỉnh…")
        : ui("Hiệu chỉnh {{count}} đoạn khó nghe", { count: unclear })}
    </Button>
  );
  const panel = (error ||
    (found !== null && !running) ||
    pending.length > 0 ||
    applied.length > 0) && (
    <section className="grid gap-3">
      {error && <p className="text-sm text-status-danger-content">{error}</p>}

      {found !== null && !running && (
        <p role="status" className="text-sm text-content-muted">
          {found === 0
            ? ui("Không có chỗ nào cần sửa.")
            : ui("Tìm được {{count}} chỗ cần sửa.", { count: found })}
        </p>
      )}

      {pending.length > 0 && runId && (
        <div className="flex items-center justify-between gap-2">
          <h3 className="text-sm font-medium">
            {ui("Đề xuất ({{count}})", { count: pending.length })}
          </h3>
          <ConfirmDialog
            trigger={
              <Button prominence="tertiary" size="sm" disabled={busy}>
                {ui("Nhận hết")}
              </Button>
            }
            title={ui("Nhận hết?")}
            description={ui("Transcript sẽ đổi ở {{count}} chỗ.", { count: pending.length })}
            confirmLabel={ui("Nhận hết")}
            pendingLabel={ui("Đang nhận…")}
            onConfirm={() =>
              guard(() => decide.mutateAsync(() => acceptAllCorrections(meeting.id, runId)))
            }
          />
        </div>
      )}
      <ol className="grid gap-2">
        {pending.map((item) => (
          <li
            key={item.id}
            className="grid gap-2 rounded-xl border border-border-default px-3 py-3"
          >
            <p className="text-sm text-content-muted">
              <Said
                text={said(meeting, item.utteranceId)}
                spans={[{ start: item.start, end: item.end, confidence: item.confidence }]}
              />
            </p>
            <div className="flex flex-wrap items-baseline gap-2 text-sm">
              <s className="text-content-muted">{item.before}</s>
              <span aria-hidden="true" className="text-content-muted">
                →
              </span>
              <Input
                className="h-8 w-auto max-w-xs min-w-40"
                value={wording[item.id] ?? item.after}
                aria-label={ui("Chữ thay thế")}
                onChange={(event) =>
                  setWording((current) => ({ ...current, [item.id]: event.target.value }))
                }
              />
            </div>
            <p className="text-sm text-content-secondary">{item.reason}</p>
            <div className="flex flex-wrap items-center gap-3 text-xs text-content-muted tabular-nums">
              <Score label={ui("Chắc")} value={item.confidence} />
              <Score label={ui("Hợp ngữ cảnh")} value={item.contextFit} />
              <Score label={ui("Giữ nguyên ý")} value={item.meaningSafe} />
              {item.matchedGlossary && <span>{ui("Khớp thuật ngữ")}</span>}
            </div>
            <div className="flex flex-wrap gap-2">
              <Button
                size="sm"
                disabled={busy}
                onClick={() =>
                  guard(() =>
                    decide.mutateAsync(() =>
                      acceptCorrection(
                        meeting.id,
                        item.id,
                        wording[item.id] === undefined || wording[item.id] === item.after
                          ? undefined
                          : wording[item.id],
                      ),
                    ),
                  )
                }
              >
                <Check aria-hidden="true" />
                {ui("Nhận")}
              </Button>
              <Button
                prominence="secondary"
                size="sm"
                disabled={busy}
                onClick={() =>
                  guard(() => decide.mutateAsync(() => keepWording(meeting.id, item.id)))
                }
              >
                <X aria-hidden="true" />
                {ui("Giữ nguyên")}
              </Button>
            </div>
          </li>
        ))}
      </ol>

      {applied.length > 0 && appliedRun && (
        <div className="flex items-center justify-between gap-2">
          <h3 className="text-sm font-medium">
            {ui("Đã sửa ({{count}})", { count: applied.length })}
          </h3>
          <ConfirmDialog
            trigger={
              <Button prominence="tertiary" size="sm" disabled={busy}>
                {ui("Hoàn tác cả lượt")}
              </Button>
            }
            title={ui("Hoàn tác cả lượt?")}
            description={ui("{{count}} chỗ trở lại như máy nghe ban đầu.", {
              count: applied.length,
            })}
            confirmLabel={ui("Hoàn tác cả lượt")}
            pendingLabel={ui("Đang hoàn tác…")}
            onConfirm={() =>
              guard(() => decide.mutateAsync(() => revertAllCorrections(meeting.id, appliedRun)))
            }
          />
        </div>
      )}
      {applied.length > 0 && (
        <ol className="grid gap-1">
          {applied.map((item) => (
            <li
              key={item.id}
              className="flex flex-wrap items-center gap-2 rounded-lg px-3 py-1.5 text-sm text-content-secondary"
            >
              <s className="text-content-muted">{item.before}</s>
              <span aria-hidden="true" className="text-content-muted">
                →
              </span>
              <span>{item.after}</span>
              <Button
                prominence="tertiary"
                size="sm"
                className="ml-auto"
                disabled={busy}
                onClick={() =>
                  guard(() => decide.mutateAsync(() => revertCorrection(meeting.id, item.id)))
                }
              >
                <Undo2 aria-hidden="true" />
                {ui("Hoàn tác")}
              </Button>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
  return { trigger, panel };
}

/** Lets a page that loads its meeting first place the button and the proposals where its layout wants them. */
export function TranscriptCorrections({
  meeting,
  enabled,
  children,
}: {
  meeting: MeetingDetail;
  enabled: boolean;
  children: (parts: { trigger: ReactNode; panel: ReactNode }) => ReactNode;
}) {
  return children(useTranscriptCorrections(meeting, enabled));
}

/** The sentence a proposal sits in. A few words cannot be judged without the ones around them. */
function said(meeting: MeetingDetail, utteranceId: string) {
  return meeting.utterances.find((utterance) => utterance.id === utteranceId)?.text ?? "";
}

function Score({ label, value }: { label: string; value: number }) {
  return (
    <span>
      {label} {new Intl.NumberFormat(uiLocale(), { style: "percent" }).format(value)}
    </span>
  );
}
