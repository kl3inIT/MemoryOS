import { useEffect, useRef, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, ChevronDown, Undo2, WandSparkles, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { HelpPopover } from "@/components/ui/help-popover";
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

const NONE: MeetingCorrection[] = [];

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
 * the proposals, which stay above the transcript they change, and the changes in force, which the transcript marks
 * in the lines they changed. All are empty when {@code enabled} is false.
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
  // A proposal is offered only while its line still says the words it was made for.
  const pending = all.filter(
    (item) =>
      item.status === "PENDING" &&
      said(meeting, item.utteranceId).slice(item.start, item.end) === item.before,
  );
  const applied = all.filter((item) => item.status === "ACCEPTED");
  const busy =
    running ||
    [run, accept, keep, revert, acceptAll, revertAll].some((mutation) => mutation.isPending);
  // What a pass would look at: every stretch the provider marked, on lines nobody has rewritten by hand.
  const unclear = meeting.utterances
    .filter((utterance) => utterance.editSource !== "HUMAN")
    .reduce((count, utterance) => count + utterance.spans.length, 0);
  if (!enabled || (unclear === 0 && all.length === 0 && !running))
    return { trigger: null, panel: null, applied: NONE, undoAll: null };
  const runId = pending[0]?.runId;

  const trigger = (unclear > 0 || running) && (
    <div className="flex items-center gap-1">
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
      <HelpPopover label={ui("Hiệu chỉnh")}>
        <p className="text-sm text-content-secondary">
          {ui(
            "AI đọc lại những đoạn máy nghe chưa chắc và đề xuất chữ thay thế. Transcript chỉ đổi ở chỗ bạn bấm Nhận, và chỗ nào đã đổi cũng hoàn tác được.",
          )}
        </p>
      </HelpPopover>
    </div>
  );
  // Each change is marked in its own line; this is the way back from all of them at once. A pass is taken back
  // whole, and every word written by hand is a pass of its own.
  const appliedRuns = [...new Set(applied.map((item) => item.runId))];
  const undoAll = applied.length > 0 && (
    <ConfirmDialog
      trigger={
        <Button prominence="tertiary" size="sm" disabled={busy}>
          <Undo2 aria-hidden="true" />
          {ui("Hoàn tác tất cả")}
        </Button>
      }
      title={ui("Hoàn tác tất cả?")}
      description={ui("{{count}} chỗ trở lại như máy nghe ban đầu.", { count: applied.length })}
      confirmLabel={ui("Hoàn tác tất cả")}
      pendingLabel={ui("Đang hoàn tác…")}
      errorMessage={confirmFailure}
      onConfirm={async () => {
        reset();
        for (const run of appliedRuns) await revertAll.mutateAsync({ path, body: { runId: run } });
      }}
    />
  );
  const panel = (!!failed || found !== null || pending.length > 0) && (
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
        <Collapsible>
          <div className="flex items-center gap-1">
            <CollapsibleTrigger asChild>
              <Button prominence="tertiary" size="sm" className="-ml-2">
                <ChevronDown
                  data-icon="inline-start"
                  aria-hidden="true"
                  className="transition-transform in-data-[state=open]:rotate-180"
                />
                {ui("Đề xuất ({{count}})", { count: pending.length })}
              </Button>
            </CollapsibleTrigger>
            <HelpPopover label={ui("Điểm của đề xuất")}>
              <p className="text-sm text-content-secondary">
                {ui(
                  "Chắc: mô hình tin vào chữ thay thế đến đâu. Hợp ngữ cảnh: chữ mới khớp với các câu xung quanh đến đâu. Giữ nguyên ý: câu sau khi sửa còn đúng ý ban đầu đến đâu.",
                )}
              </p>
            </HelpPopover>
          </div>
          <CollapsibleContent>
            {/* Taking every proposal at once is offered beside the proposals, to someone who can see them. */}
            <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
              <p className="text-xs text-content-muted">
                {ui("Transcript chỉ đổi ở chỗ bạn nhận.")}
              </p>
              <ConfirmDialog
                trigger={
                  <Button prominence="secondary" size="sm" disabled={busy}>
                    {ui("Nhận hết")}
                  </Button>
                }
                title={ui("Nhận hết?")}
                description={ui("Transcript sẽ đổi ở {{count}} chỗ. Chỗ nào cũng hoàn tác được.", {
                  count: pending.length,
                })}
                confirmLabel={ui("Nhận hết")}
                pendingLabel={ui("Đang nhận…")}
                confirmTone="default"
                errorMessage={confirmFailure}
                onConfirm={async () => {
                  reset();
                  await acceptAll.mutateAsync({ path, body: { runId } });
                }}
              />
            </div>
            <ol className="mt-2 grid gap-2">
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
          </CollapsibleContent>
        </Collapsible>
      )}
    </section>
  );
  return { trigger, panel, applied, undoAll };
}

/** Lets a page that loads its meeting first place the button and the proposals where its layout wants them. */
export function TranscriptCorrections({
  meeting,
  enabled,
  children,
}: {
  meeting: MeetingDetail;
  enabled: boolean;
  children: (parts: {
    trigger: ReactNode;
    panel: ReactNode;
    /** The changes in force, for the transcript to mark where they stand. */
    applied: MeetingCorrection[];
    /** Takes every change of the last pass back, for the transcript to offer beside what its marks mean. */
    undoAll: ReactNode;
  }) => ReactNode;
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
