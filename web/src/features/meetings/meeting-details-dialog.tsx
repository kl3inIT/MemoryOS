import { useId, useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { meetingKey, meetingsKey, updateMeetingDetails, type MeetingDetail } from "./meetings-api";

/**
 * The name and the people, filled in once the meeting is running: the start form asks only what the recording needs,
 * and Fireflies names a recording on the recording page for the same reason.
 */
export function MeetingDetailsDialog({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  const id = useId();
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState(meeting.title);
  const [participants, setParticipants] = useState(meeting.participants.join(", "));
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();

  async function save(event: FormEvent) {
    event.preventDefault();
    if (pending) return;
    setPending(true);
    setError(undefined);
    try {
      const names = participants
        .split(/[,;\n]/)
        .map((name) => name.trim())
        .filter(Boolean);
      cache.setQueryData(
        meetingKey(meeting.id),
        await updateMeetingDetails(meeting.id, title, names),
      );
      void cache.invalidateQueries({ queryKey: meetingsKey, exact: true });
      setOpen(false);
    } catch (failed) {
      setError(problemMessage(presentProblem(failed, "mutation").message));
    } finally {
      setPending(false);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (pending) return;
        if (next) {
          setTitle(meeting.title);
          setParticipants(meeting.participants.join(", "));
          setError(undefined);
        }
        setOpen(next);
      }}
    >
      <DialogTrigger asChild>
        <Button prominence="secondary">
          <Pencil aria-hidden="true" />
          {ui("Sửa thông tin")}
        </Button>
      </DialogTrigger>
      <DialogContent aria-describedby={undefined}>
        <form onSubmit={(event) => void save(event)} className="grid gap-5">
          <DialogHeader>
            <DialogTitle>{ui("Thông tin cuộc họp")}</DialogTitle>
          </DialogHeader>
          <fieldset disabled={pending} className="grid gap-4">
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-title`}>{ui("Tên cuộc họp")}</Label>
              <Input
                id={`${id}-title`}
                value={title}
                required
                maxLength={200}
                onChange={(event) => setTitle(event.target.value)}
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-participants`}>{ui("Thành phần")}</Label>
              <Input
                id={`${id}-participants`}
                value={participants}
                placeholder={ui("Tên người dự, cách nhau bằng dấu phẩy")}
                onChange={(event) => setParticipants(event.target.value)}
              />
            </div>
          </fieldset>
          {error && (
            <p role="alert" className="text-sm text-status-danger-content">
              {error}
            </p>
          )}
          <DialogFooter>
            <Button
              type="button"
              prominence="tertiary"
              disabled={pending}
              onClick={() => setOpen(false)}
            >
              {ui("Huỷ")}
            </Button>
            <Button type="submit" pending={pending}>
              {ui("Lưu")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
