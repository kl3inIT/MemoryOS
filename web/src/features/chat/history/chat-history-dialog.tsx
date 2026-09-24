import { useQuery } from "@tanstack/react-query";
import { FileText, ThumbsDown, ThumbsUp } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { StatusBadge } from "@/components/ui/status-badge";
import { appText } from "@/i18n/app-text";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { getChatHistoryTranscriptOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatHistoryEntry, ChatHistoryMessage } from "@/lib/hey-api/types.gen";
import { askerName } from "./chat-history";

/**
 * One conversation read in full, in the middle of the screen. Opening this panel is recorded in the audit log, and a citation is named rather
 * than opened: reading a source goes through the reader's own authority, never the asker's.
 */
export function ChatHistoryDialog({
  entry,
  onClose,
}: {
  entry: ChatHistoryEntry | null;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const transcript = useQuery({
    ...getChatHistoryTranscriptOptions({ path: { sessionId: entry?.id ?? "" } }),
    enabled: entry !== null,
  });

  return (
    <Dialog open={entry !== null} onOpenChange={(open) => (open ? null : onClose())}>
      {/* A transcript is read, not edited beside the list, so it opens in the middle rather than at the edge. */}
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
        {entry ? (
          <>
            <DialogHeader>
              <DialogTitle>{entry.title}</DialogTitle>
              <DialogDescription className="flex flex-wrap items-center gap-2">
                <span>{ui(askerName(entry))}</span>
                <span aria-hidden="true">·</span>
                <span>
                  {formatUiDate(entry.updatedAt, { dateStyle: "medium", timeStyle: "short" })}
                </span>
                {entry.deleted ? (
                  <StatusBadge tone="neutral" size="sm">
                    {ui("Deleted")}
                  </StatusBadge>
                ) : null}
              </DialogDescription>
            </DialogHeader>

            {transcript.isPending ? (
              <p role="status" className="font-secondary-body text-content-muted">
                {ui("Loading the conversation…")}
              </p>
            ) : transcript.isError ? (
              <p role="alert" className="font-secondary-body text-status-danger-content">
                {ui("The conversation could not be loaded.")}
              </p>
            ) : (
              <div className="flex flex-col gap-4">
                {transcript.data?.messages.map((message) => (
                  <Turn key={message.id} message={message} />
                ))}
              </div>
            )}
          </>
        ) : null}
      </DialogContent>
    </Dialog>
  );
}

function Turn({ message }: { message: ChatHistoryMessage }) {
  const ui = useAppTranslation();
  const asked = message.role === "USER";
  return (
    <article
      className={
        asked
          ? "rounded-md border border-border-subtle bg-surface-subtle p-3"
          : "rounded-md border border-border-subtle bg-surface-raised p-3"
      }
    >
      <p className="flex items-center gap-2 font-secondary-action text-content-muted">
        {asked ? ui("Question") : ui("Answer")}
        {message.modelName ? (
          <span className="font-secondary-body">{message.modelName}</span>
        ) : null}
        {message.positive === true ? (
          <ThumbsUp
            aria-label={ui("Marked good")}
            className="size-3.5 text-status-success-content"
          />
        ) : message.positive === false ? (
          <ThumbsDown
            aria-label={ui("Marked bad")}
            className="size-3.5 text-status-danger-content"
          />
        ) : null}
      </p>
      <p className="mt-1 whitespace-pre-wrap text-content-primary">{message.content}</p>
      {message.comment ? (
        <p className="mt-2 font-secondary-body text-content-muted">
          {ui(appText("Feedback: {{comment}}", { comment: message.comment }))}
        </p>
      ) : null}
      {message.citations.length > 0 ? (
        <ul className="mt-2 flex flex-wrap gap-2">
          {message.citations.map((citation, index) => (
            <li
              key={`${citation}-${index}`}
              className="flex items-center gap-1.5 rounded-sm bg-surface-subtle px-2 py-1 font-secondary-body text-content-secondary"
            >
              <FileText aria-hidden="true" className="size-3.5" />
              {citation}
            </li>
          ))}
        </ul>
      ) : null}
    </article>
  );
}
