import { useEffect, useId, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Textarea } from "@/components/ui/textarea";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ApiError } from "@/lib/api";
import { patchMeeting, saveMeetingNotes, type MeetingDetail } from "./meetings-api";

/** The owner's own notes, saved shortly after typing stops and whenever the field is left. */
export function MeetingNotes({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const id = useId();
  const [value, setValue] = useState(meeting.notes);
  const [state, setState] = useState<"saved" | "saving" | "dirty" | "conflict">("saved");
  const revision = useRef(meeting.revision);
  const timer = useRef<number>(undefined);
  const latest = useRef(meeting.notes);
  const stored = useRef(meeting.notes);
  // Saves run one after another, so the second never reuses the revision the first is about to replace.
  const queue = useRef<Promise<void>>(Promise.resolve());

  useEffect(() => {
    revision.current = meeting.revision;
  }, [meeting.revision]);
  useEffect(() => () => window.clearTimeout(timer.current), []);

  function change(next: string) {
    latest.current = next;
    setValue(next);
    setState("dirty");
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(flush, 1_200);
  }

  function flush() {
    window.clearTimeout(timer.current);
    queue.current = queue.current.then(save);
  }

  async function save() {
    const next = latest.current;
    if (next === stored.current) {
      setState((current) => (current === "conflict" ? current : "saved"));
      return;
    }
    setState("saving");
    try {
      const saved = await saveMeetingNotes(meeting.id, next, revision.current);
      revision.current = saved.revision;
      stored.current = next;
      patchMeeting(cache, meeting.id, (current) => ({
        ...current,
        notes: saved.notes,
        revision: saved.revision,
      }));
      setState(latest.current === next ? "saved" : "dirty");
    } catch (failed) {
      setState(failed instanceof ApiError && failed.status === 409 ? "conflict" : "dirty");
    }
  }

  return (
    <div className="grid gap-2">
      <Textarea
        id={`${id}-notes`}
        value={value}
        aria-label={ui("Ghi chú của tôi")}
        maxLength={50_000}
        rows={12}
        onChange={(event) => change(event.target.value)}
        // Always queued: a save still in flight may store a value this one has to replace.
        onBlur={flush}
      />
      <p className="text-xs text-content-muted" role="status">
        {state === "saving"
          ? ui("Đang lưu…")
          : state === "saved"
            ? ui("Đã lưu")
            : state === "conflict"
              ? ui("Ghi chú vừa đổi ở nơi khác. Tải lại trang.")
              : ui("Chưa lưu")}
      </p>
    </div>
  );
}
