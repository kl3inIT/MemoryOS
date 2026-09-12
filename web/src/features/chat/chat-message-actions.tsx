import { useAppTranslation } from "@/i18n/use-app-translation";
import { useContext, useRef, useState, type ReactNode } from "react";
import { useAuiState } from "@assistant-ui/react";
import { Pencil } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { IconButton } from "@/components/ui/icon-button";
import { EditMessage } from "@/components/assistant-ui/elements/edit-message";
import { MessageBranches } from "@/components/assistant-ui/elements/message-branches";
import { MessageActions, type Reaction } from "@/components/assistant-ui/elements/message-actions";
import { FeedbackDialog } from "@/components/assistant-ui/elements/feedback-dialog";
import { useTranslation } from "react-i18next";
import { sameOriginMutationHeaders } from "@/lib/api";
import { setChatFeedback, removeChatFeedback } from "@/lib/hey-api/sdk.gen";
import { ChatEditingContext } from "./chat-editing-context";
import { ChatDialog } from "./chat-dialog";
import { chatActionError } from "./chat-action-utils";
import type { Feedback } from "./chat-workspace-api";
import { fileIdFromReference } from "./chat-files";
import { ChatFilePicker } from "./chat-file-picker";

export function ChatUserMessageContent({
  children,
  readOnly,
}: {
  children: ReactNode;
  readOnly: boolean;
}) {
  const ui = useAppTranslation();

  const editing = useContext(ChatEditingContext);
  const message = useAuiState((state) => state.message);
  const [editor, setEditor] = useState(false);
  const [text, setText] = useState("");
  const [fileIds, setFileIds] = useState<string[]>([]);
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);
  const request = useRef(crypto.randomUUID());
  const inFlight = useRef(false);
  const available =
    !readOnly &&
    !!editing?.sessionId &&
    editing.branches.some((entry) => entry.id === message.id && entry.parentMessageId);
  return (
    <>
      {editor && editing && available ? (
        <EditMessage
          value={text}
          pending={saving}
          saveDisabled={editing.busy}
          error={error ? ui(error) : undefined}
          hasAttachments={fileIds.length > 0}
          onValueChange={(value) => {
            setText(value);
            request.current = crypto.randomUUID();
          }}
          onCancel={() => {
            setEditor(false);
            setError(undefined);
          }}
          onSave={() => {
            if (inFlight.current || editing.busy) return;
            inFlight.current = true;
            setSaving(true);
            setError(undefined);
            void editing
              .edit(message.id, text, request.current, fileIds)
              .then(() => setEditor(false))
              .catch((cause: unknown) => setError(chatActionError(cause)))
              .finally(() => {
                inFlight.current = false;
                setSaving(false);
              });
          }}
        >
          <ChatFilePicker
            selected={fileIds}
            disabled={saving || editing.busy}
            onSelect={(ids) => {
              setFileIds(ids);
              request.current = crypto.randomUUID();
            }}
          />
        </EditMessage>
      ) : (
        <div className="max-w-[90%] rounded-2xl bg-surface-sunken px-4 py-3 whitespace-pre-wrap [overflow-wrap:anywhere]">
          {children}
        </div>
      )}
      {available && !editor && (
        <div className="mt-1 flex items-center gap-1">
          <IconButton
            aria-label={ui("Chỉnh sửa câu hỏi")}
            title={ui("Chỉnh sửa câu hỏi")}
            size="sm"
            prominence="internal"
            disabled={editing.busy}
            onClick={() => {
              setFileIds(
                [
                  ...message.parts,
                  ...(message.attachments ?? []).flatMap((attachment) => attachment.content ?? []),
                ]
                  .map((part) => {
                    const reference =
                      part.type === "file"
                        ? part.data
                        : part.type === "image"
                          ? part.image
                          : undefined;
                    return typeof reference === "string"
                      ? fileIdFromReference(reference)
                      : undefined;
                  })
                  .filter((id): id is string => !!id),
              );
              setText(
                message.parts
                  .filter((part) => part.type === "text")
                  .map((part) => part.text)
                  .join(""),
              );
              request.current = crypto.randomUUID();
              setError(undefined);
              setEditor(true);
            }}
          >
            <Pencil />
          </IconButton>
          <ChatMessageActions role="user" />
        </div>
      )}
    </>
  );
}

export function ChatMessageActions({ role }: { role: "user" | "assistant" }) {
  const ui = useAppTranslation();

  const editing = useContext(ChatEditingContext);
  const message = useAuiState((state) => state.message);
  const [rating, setRating] = useState<Reaction>(null);
  const [removing, setRemoving] = useState(false);
  const [error, setError] = useState<string>();
  const removeBusy = useRef(false);
  const regenerateRequest = useRef(crypto.randomUUID());
  const cache = useQueryClient();
  if (!editing?.sessionId) return null;
  const sessionId = editing.sessionId;
  const node = editing.branches.find((entry) => entry.id === message.id);
  if (!node?.parentMessageId) return null;
  const siblings = editing.branches.filter(
    (entry) => entry.parentMessageId === node.parentMessageId,
  );
  const index = siblings.findIndex((entry) => entry.id === message.id);
  const feedback = editing.feedback.find((entry) => entry.assistantMessageId === message.id);
  const content = message.parts.some((part) => part.type === "text" && part.text.trim());
  return (
    <>
      <MessageBranches
        label={role === "user" ? ui("Phiên bản câu hỏi") : ui("Phiên bản câu trả lời")}
        count={siblings.length}
        index={index}
        disabled={editing.busy}
        onIndexChange={(next) => {
          void editing.branch(siblings[next]!.id, message.id).catch(() => {});
        }}
      />
      {role === "assistant" && (
        <MessageActions
          feedbackAvailable={content}
          reaction={
            feedback?.positive === true ? "up" : feedback?.positive === false ? "down" : null
          }
          disabled={editing.busy || removing}
          onRegenerate={() => {
            void editing
              .regenerate(node.parentMessageId!, regenerateRequest.current)
              .then(() => {
                regenerateRequest.current = crypto.randomUUID();
              })
              .catch(() => {});
          }}
          onReactionChange={(next) => {
            setError(undefined);
            if (next) {
              setRating(next);
              return;
            }
            if (removeBusy.current) return;
            removeBusy.current = true;
            setRemoving(true);
            void removeChatFeedback({
              path: { sessionId, assistantMessageId: message.id },
              headers: sameOriginMutationHeaders,
              signal: AbortSignal.timeout(30000),
              throwOnError: true,
            })
              .then(() => cache.invalidateQueries({ queryKey: ["chat-feedback", sessionId] }))
              .catch((cause: unknown) => setError(chatActionError(cause)))
              .finally(() => {
                removeBusy.current = false;
                setRemoving(false);
              });
          }}
        />
      )}
      {error && (
        <p role="alert" className="text-xs">
          {ui(error)}
        </p>
      )}
      {rating && (
        <FeedbackEditor
          sessionId={sessionId}
          messageId={message.id}
          feedback={feedback}
          positive={rating === "up"}
          onClose={() => setRating(null)}
        />
      )}
    </>
  );
}

const REASONS = ["incorrect", "incomplete", "sources", "style"] as const;
function FeedbackEditor({
  sessionId,
  messageId,
  feedback,
  positive,
  onClose,
}: {
  sessionId: string;
  messageId: string;
  feedback?: Feedback;
  positive: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation("feedback");
  const cache = useQueryClient();
  const [comment, setComment] = useState(feedback?.comment ?? "");
  const [reason, setReason] = useState(feedback?.reason ?? "");
  const reasons: { id: string; label: string }[] = REASONS.map((id) => ({ id, label: t(id) }));
  if (reason && !reasons.some((entry) => entry.id === reason))
    reasons.push({ id: reason, label: reason });
  return (
    <ChatDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={positive ? t("positiveTitle") : t("negativeTitle")}
      description={t("description")}
      submitLabel={t("send")}
      onSubmit={async () => {
        await setChatFeedback({
          path: { sessionId, assistantMessageId: messageId },
          body: { positive, comment, reason },
          headers: sameOriginMutationHeaders,
          signal: AbortSignal.timeout(30000),
          throwOnError: true,
        });
        await cache.invalidateQueries({ queryKey: ["chat-feedback", sessionId] });
      }}
    >
      <FeedbackDialog
        reasons={reasons}
        selected={reason}
        note={comment}
        onNoteChange={setComment}
        onToggleReason={setReason}
      />
    </ChatDialog>
  );
}
