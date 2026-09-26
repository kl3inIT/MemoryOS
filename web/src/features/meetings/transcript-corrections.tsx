import { useEffect, useRef, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Undo2, WandSparkles, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { uiLocale } from "@/i18n/format";
import {
  acceptAllMeetingCorrectionsMutation,
  acceptMeetingCorrectionMutation,
  keepMeetingWordingMutation,
  listMeetingCorrectionsOptions,
  proposeMeetingCorrectionsMutation,
  revertAllMeetingCorrectionsMutation,
  revertMeetingCorrectionMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { MeetingCorrectionApplied } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { Said } from "./transcript-text";
import {
  correctionsQueryKey,
  foldCorrection,
  meetingQueryKey,
  type MeetingCorrection,
  type MeetingDetail,
} from "./meetings-api";
import { useFailureText } from "./use-failure-text";

const confirmFailure = (error: unknown) => presentProblem(error, "mutation").message;

/** The mutations that decide proposals: one stretch answers its line and proposal, a whole pass the meeting. */
function useCorrectionDecisions(meetingId: string) {
  const cache = useQueryClient();
  const folded = (answer: MeetingCorrectionApplied | MeetingCorrection) =>
    foldCorrection(cache, meetingId, answer);
  const replaced = (detail: MeetingDetail) => {
    cache.setQueryData(meetingQueryKey(meetingId), detail);
    return cache.invalidateQueries({ queryKey: correctionsQueryKey(meetingId) });
  };
  return {
    accept: useMutation({ ...acceptMeetingCorrectionMutation(), onSuccess: folded }),
    keep: useMutation({ ...keepMeetingWordingMutation(), onSuccess: folded }),
    revert: useMutation({ ...revertMeetingCorrectionMutation(), onSuccess: folded }),
    acceptAll: useMutation({ ...acceptAllMeetingCorrectionsMutation(), onSuccess: replaced }),
    revertAll: useMutation({ ...revertAllMeetingCorrectionsMutation(), onSuccess: replaced }),
  };
}

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
  const failureText = useFailureText();
  const [wording, setWording] = useState<Record<string, string>>({});
  // A pass runs on the server whether or not this page is still open, so whether one is running comes from the
  // meeting itself. Leaving and coming back shows it still running, and the button stays shut until it is done.
  // The meeting page polls the meeting while it is correcting.
  const running = meeting.correcting;
  const path = { meetingId: meeting.id };
  const corrections = useQuery({ ...listMeetingCorrectionsOptions({ path }), enabled });
  const wasRunning = useRef(running);
  useEffect(() => {
    // A pass started elsewhere has just finished: its proposals are waiting to be read.
    if (wasRunning.current && !running)
      void cache.invalidateQueries({ queryKey: correctionsQueryKey(meeting.id) });
    wasRunning.current = running;
  }, [running, cache, meeting.id]);

  const run = useMutation({
    ...proposeMeetingCorrectionsMutation(),
    // Marked running straight away, so the button shuts even before the server answers.
    onMutate: () => {
      cache.setQueryData<MeetingDetail>(meetingQueryKey(meeting.id), (current) =>
        current ? { ...current, correcting: true } : current,
      );
    },
    onSettled: () =>
      Promise.all([
        cache.invalidateQueries({ queryKey: correctionsQueryKey(meeting.id) }),
        cache.invalidateQueries({ queryKey: meetingQueryKey(meeting.id) }),
      ]),
  });
  const { accept, keep, revert, acceptAll, revertAll } = useCorrectionDecisions(meeting.id);
  const decisions = [accept, keep, revert];
  /** One decision at a time: starting one clears what the last one said; what the pass found stays. */
  function reset() {
    for (const mutation of decisions) mutation.reset();
  }
  const failed = [...decisions, run].find((mutation) => mutation.isError)?.error;
  const found = run.isSuccess && !running ? run.data.corrections.length : null;

  const all = corrections.data ?? [];
  const pending = all.filter((item) => item.status === "PENDING");
  const applied = all.filter((item) => item.status === "ACCEPTED");
  const busy =
    running ||
    [run, accept, keep, revert, acceptAll, revertAll].some((mutation) => mutation.isPending);
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
      onClick={() => {
        reset();
        run.reset();
        run.mutate({ path });
      }}
    >
      <WandSparkles aria-hidden="true" className={running ? "animate-pulse" : undefined} />
      {running
        ? ui("Đang hiệu chỉnh…")
        : ui("Hiệu chỉnh {{count}} đoạn khó nghe", { count: unclear })}
    </Button>
  );
  const panel = (!!failed || found !== null || pending.length > 0 || applied.length > 0) && (
    <section className="grid gap-3">
      {failed ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {failureText(failed)}
        </p>
      ) : null}

      {found !== null && (
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
            errorMessage={confirmFailure}
            onConfirm={async () => {
              reset();
              await acceptAll.mutateAsync({ path, body: { runId } });
            }}
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
                size="sm"
                className="w-auto max-w-xs min-w-40"
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
                onClick={() => {
                  reset();
                  const written = wording[item.id];
                  accept.mutate({
                    path: { ...path, correctionId: item.id },
                    body: {
                      text: written === undefined || written === item.after ? null : written,
                    },
                  });
                }}
              >
                <Check aria-hidden="true" />
                {ui("Nhận")}
              </Button>
              <Button
                prominence="secondary"
                size="sm"
                disabled={busy}
                onClick={() => {
                  reset();
                  keep.mutate({ path: { ...path, correctionId: item.id } });
                }}
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
            errorMessage={confirmFailure}
            onConfirm={async () => {
              reset();
              await revertAll.mutateAsync({ path, body: { runId: appliedRun } });
            }}
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
                onClick={() => {
                  reset();
                  revert.mutate({ path: { ...path, correctionId: item.id } });
                }}
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
