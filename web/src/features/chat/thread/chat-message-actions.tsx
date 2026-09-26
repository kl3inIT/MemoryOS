import { useAppTranslation } from "@/i18n/use-app-translation";
import { use, useRef, useState, type ReactNode } from "react";
import { useAuiState } from "@assistant-ui/react";
import { Pencil } from "lucide-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { IconButton } from "@/components/ui/icon-button";
import { EditMessage } from "@/components/assistant-ui/elements/edit-message";
import { MessageBranches } from "@/components/assistant-ui/elements/message-branches";
import { MessageActions, type Reaction } from "@/components/assistant-ui/elements/message-actions";
import { FeedbackDialog } from "@/components/assistant-ui/elements/feedback-dialog";
import { useTranslation } from "react-i18next";
import {
  removeChatFeedbackMutation,
  setChatFeedbackMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { ChatEditingContext } from "./chat-editing-context";
import { FormDialog } from "@/components/composites/form-dialog";
import { actionErrorText } from "@/lib/action-errors";
import { invalidateChatVersions, type Feedback } from "@/features/chat/chat-api";
import { fileIdFromReference } from "@/features/library/files";
import { ChatFilePicker } from "@/features/library/file-picker";
import { ChatBranchAction } from "./chat-branch-action";
import { ChatRegenerateMenu } from "./chat-regenerate-menu";

type MessagePart = ReturnType<typeof useMessage>["parts"][number];

function useMessage() {
  return useAuiState((state) => state.message);
}

/** The library file a message part refers to, for parts that carry one. */
function fileIdOf(part: MessagePart | { type: string; data?: unknown; image?: unknown }) {
  const reference =
    part.type === "file" && "data" in part
      ? part.data
      : part.type === "image" && "image" in part
        ? part.image
        : undefined;
  return typeof reference === "string" ? fileIdFromReference(reference) : undefined;
}

export function ChatUserMessageContent({
  children,
  readOnly,
}: {
  children: ReactNode;
  readOnly: boolean;
}) {
  const ui = useAppTranslation();
  const editing = use(ChatEditingContext);
  const message = useMessage();
  const [editor, setEditor] = useState(false);
  const [text, setText] = useState("");
  const [fileIds, setFileIds] = useState<string[]>([]);
  const request = useRef(crypto.randomUUID());
  const save = useMutation({
    mutationFn: () => editing!.edit(message.id, text, request.current, fileIds),
    onSuccess: () => setEditor(false),
  });
  const available =
    !readOnly &&
    !!editing?.sessionId &&
    editing.branches.some((entry) => entry.id === message.id && entry.parentMessageId);
  function openEditor() {
    setFileIds(
      [
        ...message.parts,
        ...(message.attachments ?? []).flatMap((attachment) => attachment.content ?? []),
      ].flatMap((part) => {
        const id = fileIdOf(part);
        return id ? [id] : [];
      }),
    );
    setText(
      message.parts
        .filter((part) => part.type === "text")
        .map((part) => part.text)
        .join(""),
    );
    request.current = crypto.randomUUID();
    save.reset();
    setEditor(true);
  }
  return (
    <>
      {editor && editing && available ? (
        <EditMessage
          value={text}
          pending={save.isPending}
          saveDisabled={editing.busy}
          error={save.isError ? ui(actionErrorText(save.error)) : undefined}
          hasAttachments={fileIds.length > 0}
          onValueChange={(value) => {
            setText(value);
            request.current = crypto.randomUUID();
          }}
          onCancel={() => {
            setEditor(false);
            save.reset();
          }}
          onSave={() => {
            if (save.isPending || editing.busy) return;
            save.mutate();
          }}
        >
          <ChatFilePicker
            selected={fileIds}
            disabled={save.isPending || editing.busy}
            onSelect={(ids) => {
              setFileIds(ids);
              request.current = crypto.randomUUID();
            }}
          />
        </EditMessage>
      ) : (
        <div className="max-w-9/10 rounded-2xl bg-surface-sunken px-4 py-3 whitespace-pre-wrap wrap-anywhere">
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
            onClick={openEditor}
          >
            <Pencil />
          </IconButton>
          <ChatMessageActions author="user" />
        </div>
      )}
    </>
  );
}

export function ChatMessageActions({ author }: { author: "user" | "assistant" }) {
  const ui = useAppTranslation();
  const editing = use(ChatEditingContext);
  const message = useMessage();
  const [rating, setRating] = useState<Reaction>(null);
  const regenerateRequest = useRef(crypto.randomUUID());
  // A retry with the same model replays its request; another model is a new command.
  const modelRequest = useRef<{ model?: string; id?: string }>({});
  const cache = useQueryClient();
  const removeFeedback = useMutation({
    ...removeChatFeedbackMutation(),
    onSuccess: (_removed, { path }) => invalidateChatVersions(cache, path.sessionId),
  });
  if (!editing?.sessionId) return null;
  const sessionId = editing.sessionId;
  const node = editing.branches.find((entry) => entry.id === message.id);
  if (!node?.parentMessageId) return null;
  const parentMessageId = node.parentMessageId;
  const siblings = editing.branches.filter((entry) => entry.parentMessageId === parentMessageId);
  const index = siblings.findIndex((entry) => entry.id === message.id);
  const feedback = editing.feedback.find((entry) => entry.assistantMessageId === message.id);
  const content = message.parts.some((part) => part.type === "text" && part.text.trim());
  const disabled = editing.busy || removeFeedback.isPending;
  return (
    <>
      <MessageBranches
        label={author === "user" ? ui("Phiên bản câu hỏi") : ui("Phiên bản câu trả lời")}
        count={siblings.length}
        index={index}
        disabled={editing.busy}
        onIndexChange={(next) => {
          void editing.branch(siblings[next]!.id, message.id).catch(() => {});
        }}
      />
      {author === "assistant" && (
        <MessageActions
          feedbackAvailable={content}
          reaction={
            feedback?.positive === true ? "up" : feedback?.positive === false ? "down" : null
          }
          disabled={disabled}
          onRegenerate={() => {
            void editing
              .regenerate(parentMessageId, regenerateRequest.current)
              .then(() => {
                regenerateRequest.current = crypto.randomUUID();
              })
              .catch(() => {});
          }}
          onReactionChange={(next) => {
            removeFeedback.reset();
            if (next) {
              setRating(next);
              return;
            }
            if (removeFeedback.isPending) return;
            removeFeedback.mutate({
              path: { sessionId, assistantMessageId: message.id },
              signal: AbortSignal.timeout(30000),
            });
          }}
        />
      )}
      {author === "assistant" && (
        <ChatRegenerateMenu
          sessionId={sessionId}
          disabled={disabled}
          onSelect={(modelId) => {
            if (modelRequest.current.model !== modelId)
              modelRequest.current = { model: modelId, id: crypto.randomUUID() };
            void editing
              .regenerate(parentMessageId, modelRequest.current.id!, modelId)
              .then(() => {
                modelRequest.current = {};
              })
              .catch(() => {});
          }}
        />
      )}
      <ChatBranchAction
        sessionId={sessionId}
        messageId={message.id}
        originTitle={editing.sessionTitle}
        disabled={disabled}
      />
      {removeFeedback.isError && (
        <p role="alert" className="text-xs">
          {ui(actionErrorText(removeFeedback.error))}
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
  const save = useMutation({
    ...setChatFeedbackMutation(),
    onSuccess: () => invalidateChatVersions(cache, sessionId),
  });
  const reasons: { id: string; label: string }[] = REASONS.map((id) => ({ id, label: t(id) }));
  if (reason && !reasons.some((entry) => entry.id === reason))
    reasons.push({ id: reason, label: reason });
  return (
    <FormDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={positive ? t("positiveTitle") : t("negativeTitle")}
      description={t("description")}
      submitLabel={t("send")}
      onSubmit={async () => {
        await save.mutateAsync({
          path: { sessionId, assistantMessageId: messageId },
          body: { positive, comment, reason },
          signal: AbortSignal.timeout(30000),
        });
      }}
    >
      <FeedbackDialog
        reasons={reasons}
        selected={reason}
        note={comment}
        onNoteChange={setComment}
        onToggleReason={setReason}
      />
    </FormDialog>
  );
}
