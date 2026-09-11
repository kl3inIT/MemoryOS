import { useContext, useRef, useState, type ReactNode } from "react";
import { useAuiState } from "@assistant-ui/react";
import { Pencil } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { IconButton } from "@/components/ui/icon-button";
import { EditMessage } from "@/components/assistant-ui/elements/edit-message";
import { MessageBranches } from "@/components/assistant-ui/elements/message-branches";
import { MessageActions, type Reaction } from "@/components/assistant-ui/elements/message-actions";
import { FeedbackDialog } from "@/components/assistant-ui/elements/feedback-dialog";
import { sameOriginMutationHeaders } from "@/lib/api";
import { setChatFeedback, removeChatFeedback } from "@/lib/hey-api/sdk.gen";
import { ChatEditingContext } from "./chat-editing-context";
import { ChatDialog } from "./chat-dialog";
import { chatActionError } from "./chat-action-utils";
import type { Feedback } from "./chat-workspace-api";

export function ChatUserMessageContent({
  children,
  readOnly,
}: {
  children: ReactNode;
  readOnly: boolean;
}) {
  const editing = useContext(ChatEditingContext);
  const message = useAuiState((state) => state.message);
  const [editor, setEditor] = useState(false);
  const [text, setText] = useState("");
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
          error={error}
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
              .edit(message.id, text, request.current)
              .then(() => setEditor(false))
              .catch((cause: unknown) => setError(chatActionError(cause)))
              .finally(() => {
                inFlight.current = false;
                setSaving(false);
              });
          }}
        />
      ) : (
        <div className="max-w-[90%] rounded-2xl bg-surface-sunken px-4 py-3 whitespace-pre-wrap [overflow-wrap:anywhere]">
          {children}
        </div>
      )}
      {available && !editor && (
        <div className="mt-1 flex items-center gap-1">
          <IconButton
            aria-label="Chỉnh sửa câu hỏi"
            title="Chỉnh sửa câu hỏi"
            size="sm"
            prominence="internal"
            disabled={editing.busy}
            onClick={() => {
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
        label={role === "user" ? "Phiên bản câu hỏi" : "Phiên bản câu trả lời"}
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
          {error}
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

const REASONS = {
  incorrect: "Thông tin chưa đúng",
  incomplete: "Thiếu thông tin",
  sources: "Nguồn chưa phù hợp",
  style: "Cách trình bày",
};
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
  const cache = useQueryClient();
  const [comment, setComment] = useState(feedback?.comment ?? "");
  const [reason, setReason] = useState(feedback?.reason ?? "");
  const selectedLabel = REASONS[reason as keyof typeof REASONS] ?? reason;
  const reasons = Object.values(REASONS);
  if (selectedLabel && !reasons.includes(selectedLabel)) reasons.push(selectedLabel);
  return (
    <ChatDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={positive ? "Đánh giá hữu ích" : "Đánh giá chưa hữu ích"}
      description="Góp ý được lưu cho đúng phiên bản câu trả lời này."
      submitLabel="Gửi đánh giá"
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
        selected={selectedLabel}
        note={comment}
        onNoteChange={setComment}
        onToggleReason={(label) =>
          setReason(Object.entries(REASONS).find(([, value]) => value === label)?.[0] ?? label)
        }
      />
    </ChatDialog>
  );
}
