import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Undo2, WandSparkles, X } from "lucide-react";
import { Button } from "@/components/ui/button";
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
  meetingKey,
  proposeCorrections,
  revertCorrection,
  type MeetingDetail,
} from "./meetings-api";

/**
 * The owner asks a model about every stretch the speech provider was unsure of, then decides each answer. The
 * transcript changes only where they say so, and every change can be taken back.
 */
export function TranscriptCorrections({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const [error, setError] = useState<string | null>(null);
  const [wording, setWording] = useState<Record<string, string>>({});
  const corrections = useQuery({
    queryKey: correctionsKey(meeting.id),
    queryFn: ({ signal }) => loadCorrections(meeting.id, signal),
  });

  const run = useMutation({
    mutationFn: () => proposeCorrections(meeting.id),
    onSuccess: () => cache.invalidateQueries({ queryKey: correctionsKey(meeting.id) }),
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
  const busy = run.isPending || decide.isPending;
  const runId = pending[0]?.runId;

  return (
    <section className="grid gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button
          prominence="secondary"
          size="sm"
          disabled={busy}
          onClick={() => guard(() => run.mutateAsync())}
        >
          <WandSparkles aria-hidden="true" />
          {run.isPending ? ui("Đang soát…") : ui("Soát lỗi nhận dạng")}
        </Button>
        {pending.length > 0 && runId && (
          <Button
            prominence="tertiary"
            size="sm"
            disabled={busy}
            onClick={() =>
              guard(() => decide.mutateAsync(() => acceptAllCorrections(meeting.id, runId)))
            }
          >
            {ui("Nhận hết ({{count}})", { count: pending.length })}
          </Button>
        )}
      </div>

      {error && <p className="text-sm text-status-danger-content">{error}</p>}

      {run.isSuccess && run.data.corrections.length === 0 && (
        <p className="text-sm text-content-muted">{ui("Không có chỗ nào cần sửa.")}</p>
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
