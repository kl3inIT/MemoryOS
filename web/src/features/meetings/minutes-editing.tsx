import { useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Check, Pencil, Plus, Trash2, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import {
  addMinutesItem,
  editMinutesItem,
  editMinutesSummary,
  meetingKey,
  removeMinutesItem,
  type MeetingDetail,
  type MeetingMinutesItem,
} from "./meetings-api";

/**
 * The pencil sits beside the line it edits and shows on hover or focus, as Fireflies and Otter do; a device without
 * hover always shows it.
 */
const REVEAL =
  "shrink-0 opacity-0 transition-opacity group-hover/row:opacity-100 focus-visible:opacity-100 [@media(hover:none)]:opacity-100";

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
      <div className="group/row flex items-start gap-2">
        <p className="min-w-0 flex-1 whitespace-pre-wrap text-content-secondary">
          {meeting.minutes.summary}
        </p>
        {meeting.owned && (
          <IconButton
            size="sm"
            aria-label={ui("Sửa tóm tắt")}
            className={REVEAL}
            onClick={() => {
              setDraft(meeting.minutes.summary);
              setEditing(true);
            }}
          >
            <Pencil />
          </IconButton>
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
  const [pending, setPending] = useState<"save" | "remove">();
  const [error, setError] = useState<string>();
  // A decision belongs to the meeting rather than to a person, so it carries neither an owner nor a deadline.
  const assignable = kind === "ACTION";

  async function remove() {
    setPending("remove");
    setError(undefined);
    try {
      cache.setQueryData(meetingKey(meeting.id), await removeMinutesItem(meeting.id, item.id));
    } catch (failed) {
      setError(problemMessage(presentProblem(failed, "mutation").message));
      setPending(undefined);
    }
  }

  async function save() {
    setPending("save");
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
      setPending(undefined);
    }
  }

  if (!editing)
    return (
      <div className="group/row flex items-start gap-2">
        <div className="min-w-0 flex-1">
          {children}
          {item.edited && <p className="mt-0.5 text-xs text-content-muted">{ui("Bạn đã sửa")}</p>}
        </div>
        {meeting.owned && (
          <IconButton
            size="sm"
            aria-label={ui("Sửa")}
            className={REVEAL}
            onClick={() => {
              setText(item.text);
              setOwner(item.owner ?? "");
              setDue(item.due ?? "");
              setEditing(true);
            }}
          >
            <Pencil />
          </IconButton>
        )}
      </div>
    );

  return (
    <div className="grid gap-2">
      <ItemFields
        id={item.id}
        assignable={assignable}
        text={text}
        owner={owner}
        due={due}
        onText={setText}
        onOwner={setOwner}
        onDue={setDue}
      />
      {error && <p className="text-sm text-status-danger-content">{error}</p>}
      <div className="flex gap-2">
        <Button
          size="sm"
          pending={pending === "save"}
          disabled={!!pending}
          onClick={() => void save()}
        >
          <Check aria-hidden="true" />
          {ui("Lưu")}
        </Button>
        <Button
          size="sm"
          prominence="tertiary"
          disabled={!!pending}
          onClick={() => setEditing(false)}
        >
          <X aria-hidden="true" />
          {ui("Huỷ")}
        </Button>
        <Button
          size="sm"
          prominence="tertiary"
          className="ml-auto text-status-danger-content"
          pending={pending === "remove"}
          disabled={!!pending}
          onClick={() => void remove()}
        >
          <Trash2 aria-hidden="true" />
          {ui("Xoá")}
        </Button>
      </div>
    </div>
  );
}

/** What an item says, and for a piece of work who takes it and by when. */
function ItemFields({
  id,
  assignable,
  text,
  owner,
  due,
  onText,
  onOwner,
  onDue,
}: {
  id: string;
  assignable: boolean;
  text: string;
  owner: string;
  due: string;
  onText: (value: string) => void;
  onOwner: (value: string) => void;
  onDue: (value: string) => void;
}) {
  const ui = useAppTranslation();
  return (
    <>
      <Textarea
        value={text}
        rows={2}
        maxLength={2000}
        aria-label={ui("Nội dung")}
        onChange={(event) => onText(event.target.value)}
      />
      {assignable && (
        <div className="grid gap-2 sm:grid-cols-2">
          <div className="grid gap-1">
            <Label htmlFor={`owner-${id}`}>{ui("Người nhận")}</Label>
            <Input
              id={`owner-${id}`}
              value={owner}
              maxLength={200}
              onChange={(event) => onOwner(event.target.value)}
            />
          </div>
          <div className="grid gap-1">
            <Label htmlFor={`due-${id}`}>{ui("Hạn")}</Label>
            <Input
              id={`due-${id}`}
              value={due}
              maxLength={100}
              onChange={(event) => onDue(event.target.value)}
            />
          </div>
        </div>
      )}
    </>
  );
}

/** A decision or a piece of work the model missed, written in by the owner. */
export function NewItem({
  meeting,
  kind,
  label,
}: {
  meeting: MeetingDetail;
  kind: "ACTION" | "DECISION";
  label: string;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  const [owner, setOwner] = useState("");
  const [due, setDue] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();

  function close() {
    setOpen(false);
    setText("");
    setOwner("");
    setDue("");
    setError(undefined);
  }

  async function add() {
    setPending(true);
    setError(undefined);
    try {
      cache.setQueryData(
        meetingKey(meeting.id),
        await addMinutesItem(meeting.id, kind, text, owner, due),
      );
      close();
    } catch (failed) {
      setError(problemMessage(presentProblem(failed, "mutation").message));
    } finally {
      setPending(false);
    }
  }

  if (!open)
    return (
      <div>
        <Button
          size="sm"
          prominence="tertiary"
          className="-ml-2 text-content-secondary"
          onClick={() => setOpen(true)}
        >
          <Plus aria-hidden="true" />
          {label}
        </Button>
      </div>
    );

  return (
    <div className="grid gap-2">
      <ItemFields
        id={`new-${kind}`}
        assignable={kind === "ACTION"}
        text={text}
        owner={owner}
        due={due}
        onText={setText}
        onOwner={setOwner}
        onDue={setDue}
      />
      {error && <p className="text-sm text-status-danger-content">{error}</p>}
      <div className="flex gap-2">
        <Button size="sm" pending={pending} disabled={!text.trim()} onClick={() => void add()}>
          <Check aria-hidden="true" />
          {ui("Thêm")}
        </Button>
        <Button size="sm" prominence="tertiary" disabled={pending} onClick={close}>
          <X aria-hidden="true" />
          {ui("Huỷ")}
        </Button>
      </div>
    </div>
  );
}
