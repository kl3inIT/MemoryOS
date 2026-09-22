import { useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Check, Pencil, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { cn } from "@/lib/utils";
import {
  editMinutesItem,
  editMinutesSummary,
  meetingKey,
  type MeetingDetail,
  type MeetingMinutesItem,
} from "./meetings-api";

/**
 * The minutes as the owner may correct them. A model that misheard one conclusion should cost one edit, not a rerun
 * of the whole meeting; what it first wrote stays on the record either way.
 */
export function EditableSummary({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(meeting.minutes.summary);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();

  async function save() {
    setPending(true);
    setError(undefined);
    try {
      cache.setQueryData(meetingKey(meeting.id), await editMinutesSummary(meeting.id, draft));
      setEditing(false);
    } catch (failed) {
      setError(problemMessage(presentProblem(failed, "mutation").message));
    } finally {
      setPending(false);
    }
  }

  if (!editing)
    return (
      <div className="grid gap-1">
        <p className="whitespace-pre-wrap text-content-secondary">{meeting.minutes.summary}</p>
        {meeting.owned && (
          <div>
            <Button
              size="sm"
              prominence="tertiary"
              onClick={() => {
                setDraft(meeting.minutes.summary);
                setEditing(true);
              }}
            >
              <Pencil aria-hidden="true" />
              {ui("Sửa tóm tắt")}
            </Button>
          </div>
        )}
      </div>
    );

  return (
    <div className="grid gap-2">
      <Textarea
        value={draft}
        rows={6}
        maxLength={20000}
        aria-label={ui("Tóm tắt")}
        onChange={(event) => setDraft(event.target.value)}
      />
      {error && <p className="text-sm text-status-danger-content">{error}</p>}
      <div className="flex gap-2">
        <Button size="sm" pending={pending} onClick={() => void save()}>
          <Check aria-hidden="true" />
          {ui("Lưu")}
        </Button>
        <Button size="sm" prominence="tertiary" onClick={() => setEditing(false)}>
          <X aria-hidden="true" />
          {ui("Huỷ")}
        </Button>
      </div>
    </div>
  );
}

/** One decision or one piece of work, with what it says, who owns it and when it is due. */
export function EditableItem({
  meeting,
  item,
  kind,
  children,
}: {
  meeting: MeetingDetail;
  item: MeetingMinutesItem;
  kind: "ACTION" | "DECISION";
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(item.text);
  const [owner, setOwner] = useState(item.owner ?? "");
  const [due, setDue] = useState(item.due ?? "");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();
  // A decision belongs to the meeting rather than to a person, so it carries neither an owner nor a deadline.
  const assignable = kind === "ACTION";

  async function save() {
    setPending(true);
    setError(undefined);
    try {
      cache.setQueryData(
        meetingKey(meeting.id),
        await editMinutesItem(
          meeting.id,
          item.id,
          text,
          assignable ? owner : "",
          assignable ? due : "",
        ),
      );
      setEditing(false);
    } catch (failed) {
      setError(problemMessage(presentProblem(failed, "mutation").message));
    } finally {
      setPending(false);
    }
  }

  if (!editing)
    return (
      <div className="grid gap-1">
        {children}
        {meeting.owned && (
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              prominence="tertiary"
              onClick={() => {
                setText(item.text);
                setOwner(item.owner ?? "");
                setDue(item.due ?? "");
                setEditing(true);
              }}
            >
              <Pencil aria-hidden="true" />
              {ui("Sửa")}
            </Button>
            {item.edited && <span className="text-xs text-content-muted">{ui("Bạn đã sửa")}</span>}
          </div>
        )}
      </div>
    );

  return (
    <div className="grid gap-2">
      <Textarea
        value={text}
        rows={2}
        maxLength={2000}
        aria-label={ui("Nội dung")}
        onChange={(event) => setText(event.target.value)}
      />
      <div className={cn("grid gap-2", assignable && "sm:grid-cols-2")}>
        {assignable && (
          <div className="grid gap-1">
            <Label htmlFor={`owner-${item.id}`}>{ui("Người nhận")}</Label>
            <Input
              id={`owner-${item.id}`}
              value={owner}
              maxLength={200}
              onChange={(event) => setOwner(event.target.value)}
            />
          </div>
        )}
        {assignable && (
          <div className="grid gap-1">
            <Label htmlFor={`due-${item.id}`}>{ui("Hạn")}</Label>
            <Input
              id={`due-${item.id}`}
              value={due}
              maxLength={100}
              onChange={(event) => setDue(event.target.value)}
            />
          </div>
        )}
      </div>
      {error && <p className="text-sm text-status-danger-content">{error}</p>}
      <div className="flex gap-2">
        <Button size="sm" pending={pending} onClick={() => void save()}>
          <Check aria-hidden="true" />
          {ui("Lưu")}
        </Button>
        <Button size="sm" prominence="tertiary" onClick={() => setEditing(false)}>
          <X aria-hidden="true" />
          {ui("Huỷ")}
        </Button>
      </div>
    </div>
  );
}
