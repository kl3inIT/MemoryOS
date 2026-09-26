import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import {
  CheckSquare,
  Clock,
  FileDown,
  Gavel,
  MessageSquareText,
  RefreshCw,
  Users,
  WifiOff,
} from "lucide-react";
import { EmptyState } from "@/components/composites/empty-state";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { i18n } from "@/i18n";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { ExportMinutesDialog } from "./export-minutes-dialog";
import { EditableItem, EditableSummary, NewItem } from "./minutes-editing";
import {
  formatClock,
  formatWhen,
  markMinutesItem,
  meetingKey,
  patchMeeting,
  publishMinutes,
  rerunMinutes,
  withMinutesItem,
  type MeetingDetail,
  type MeetingMinutesItem,
} from "./meetings-api";

/** What acts on the whole minutes, beside the tabs that show them. */
export function MinutesActions({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [pending, setPending] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [opening, setOpening] = useState(false);
  const navigate = useNavigate();

  /** Publishes the minutes into the library, then opens a new conversation with them in the composer. */
  async function openInChat() {
    setOpening(true);
    try {
      const file = await publishMinutes(meeting.id);
      await navigate({ to: "/", search: { attach: file.fileId } });
    } finally {
      setOpening(false);
    }
  }

  async function rerun(discardEdits = false) {
    setPending(true);
    try {
      cache.setQueryData(meetingKey(meeting.id), await rerunMinutes(meeting.id, discardEdits));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-1">
      {meeting.owned &&
        (meeting.minutes.edited ? (
          <ConfirmDialog
            trigger={
              <Button size="sm" prominence="tertiary" pending={pending}>
                <RefreshCw aria-hidden="true" />
                {ui("Viết lại")}
              </Button>
            }
            title={ui("Viết lại tóm tắt?")}
            description={ui("Những chỗ bạn đã sửa sẽ bị thay bằng bản mới.")}
            confirmLabel={ui("Viết lại")}
            pendingLabel={ui("Đang viết lại…")}
            confirmTone="danger"
            onConfirm={() => rerun(true)}
          />
        ) : (
          <Button size="sm" prominence="tertiary" pending={pending} onClick={() => void rerun()}>
            <RefreshCw aria-hidden="true" />
            {ui("Viết lại")}
          </Button>
        ))}
      <Button size="sm" prominence="tertiary" pending={opening} onClick={() => void openInChat()}>
        <MessageSquareText aria-hidden="true" />
        {ui("Mở trong Chat")}
      </Button>
      <Button size="sm" prominence="secondary" onClick={() => setExporting(true)}>
        <FileDown aria-hidden="true" />
        {ui("Xuất biên bản")}
      </Button>
      {exporting && (
        <ExportMinutesDialog meeting={meeting} open onOpenChange={(next) => setExporting(next)} />
      )}
    </div>
  );
}

/** The model's account of the meeting, with what it is still doing or why it could not. */
export function MinutesSummary({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [pending, setPending] = useState(false);
  const { status, generatedAt } = meeting.minutes;

  async function rerun() {
    setPending(true);
    try {
      cache.setQueryData(meetingKey(meeting.id), await rerunMinutes(meeting.id, false));
    } finally {
      setPending(false);
    }
  }

  if (status === "PENDING" || status === "RUNNING")
    return (
      <p
        role="status"
        className="rounded-xl bg-status-info-surface px-4 py-3 text-sm text-status-info-content"
      >
        {ui("Đang viết tóm tắt, quyết định và việc cần làm…")}
      </p>
    );
  if (status === "FAILED")
    return (
      <EmptyState
        role="alert"
        icon={<WifiOff />}
        title={ui("Chưa viết được tóm tắt")}
        detail={ui("Transcript vẫn còn nguyên. Thử lại khi mô hình sẵn sàng.")}
        action={
          <Button size="sm" prominence="secondary" pending={pending} onClick={() => void rerun()}>
            <RefreshCw aria-hidden="true" />
            {ui("Viết lại")}
          </Button>
        }
      />
    );
  return (
    <div className="grid gap-3">
      {generatedAt && (
        <p className="text-xs text-content-muted">
          {ui("Viết lúc {{when}}", { when: formatWhen(generatedAt, i18n.language) })}
        </p>
      )}
      <EditableSummary meeting={meeting} />
    </div>
  );
}

/** Decisions and action items, each with the sentence it rests on. */
export function MinutesItems({
  meeting,
  items,
  kind,
  onReveal,
}: {
  meeting: MeetingDetail;
  items: MeetingMinutesItem[];
  kind: "ACTION" | "DECISION";
  /** Opens the transcript on the line an item rests on. */
  onReveal: (utteranceId: string) => void;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [failed, setFailed] = useState(false);
  // What the model missed is written in beside what it found, by the owner only.
  const add = meeting.owned && meeting.minutes.status === "READY" && (
    <NewItem
      meeting={meeting}
      kind={kind}
      label={kind === "ACTION" ? ui("Thêm việc") : ui("Thêm quyết định")}
    />
  );
  if (items.length === 0)
    return (
      <div className="grid gap-3">
        <EmptyState
          icon={kind === "ACTION" ? <CheckSquare /> : <Gavel />}
          title={
            kind === "ACTION" ? ui("Không có việc nào được giao") : ui("Không có quyết định nào")
          }
        />
        {add}
      </div>
    );

  async function toggle(item: MeetingMinutesItem, done: boolean) {
    setFailed(false);
    try {
      const marked = await markMinutesItem(meeting.id, item.id, done);
      patchMeeting(cache, meeting.id, (current) => withMinutesItem(current, marked));
    } catch {
      setFailed(true);
    }
  }

  return (
    <>
      {failed && (
        <p role="alert" className="mb-2 text-sm text-status-danger-content">
          {ui("Chưa lưu được thay đổi. Hãy thử lại.")}
        </p>
      )}
      <ul className="grid gap-1">
        {items.map((item) => {
          const source = item.sourceUtteranceId
            ? meeting.utterances.find((utterance) => utterance.id === item.sourceUtteranceId)
            : undefined;
          return (
            <li
              key={item.id}
              className="grid grid-cols-[auto_1fr] gap-x-3 rounded-lg px-2 py-2 hover:bg-surface-base"
            >
              {kind === "ACTION" ? (
                <Checkbox
                  className="mt-1"
                  checked={item.done}
                  aria-label={ui("Đánh dấu xong: {{text}}", { text: item.text })}
                  disabled={!meeting.owned}
                  onCheckedChange={(checked) => void toggle(item, checked === true)}
                />
              ) : (
                <Gavel className="mt-1 size-4 text-content-muted" aria-hidden="true" />
              )}
              <div className="min-w-0">
                <EditableItem meeting={meeting} item={item} kind={kind}>
                  <p
                    className={cn(
                      "text-content-primary",
                      item.done && "text-content-muted line-through",
                    )}
                  >
                    {item.text}
                  </p>
                  {(item.owner || item.due) && (
                    <p className="mt-0.5 flex flex-wrap gap-3 text-xs text-content-secondary">
                      {item.owner && (
                        <span className="inline-flex items-center gap-1">
                          <Users className="size-3" aria-hidden="true" />
                          {item.owner}
                        </span>
                      )}
                      {item.due && (
                        <span className="inline-flex items-center gap-1">
                          <Clock className="size-3" aria-hidden="true" />
                          {item.due}
                        </span>
                      )}
                    </p>
                  )}
                  {item.quote && (
                    <p className="mt-1 text-xs text-content-muted">
                      {item.sourceUtteranceId && (
                        <button
                          type="button"
                          className="mr-1.5 font-mono text-action-selection hover:underline"
                          onClick={() => source && onReveal(source.id)}
                        >
                          {formatClock(source?.startMs ?? 0)}
                        </button>
                      )}
                      “{item.quote}”
                    </p>
                  )}
                </EditableItem>
              </div>
            </li>
          );
        })}
      </ul>
      {add && <div className="mt-2 px-2">{add}</div>}
    </>
  );
}
