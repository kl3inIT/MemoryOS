import { ChatEditingContext } from "./chat-editing-context";
import { useContext, useRef, useState } from "react";
import { useAuiState } from "@assistant-ui/react";
import { ChevronLeft, ChevronRight, Pencil, RotateCcw, ThumbsDown, ThumbsUp } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { IconButton } from "@/components/ui/icon-button";
import { Select } from "@/components/ui/select";
import { sameOriginMutationHeaders } from "@/lib/api";
import { setChatFeedback, removeChatFeedback } from "@/lib/hey-api/sdk.gen";
import { ChatDialog } from "./chat-dialog";
import { chatField } from "./chat-action-utils";
import type { Feedback } from "./chat-workspace-api";

export function ChatMessageActions({ role }: { role: "user" | "assistant" }) {
  const editing = useContext(ChatEditingContext);
  const message = useAuiState((state) => state.message);
  const [editor, setEditor] = useState(false);
  const [feedbackOpen, setFeedbackOpen] = useState(false);
  const [text, setText] = useState("");
  const editRequest = useRef(crypto.randomUUID());
  const regenerateRequest = useRef(crypto.randomUUID());
  if (!editing?.sessionId) return null;
  const node = editing.branches.find((entry) => entry.id === message.id);
  if (!node?.parentMessageId) return null;
  const siblings = editing.branches.filter(
    (entry) => entry.parentMessageId === node.parentMessageId,
  );
  const index = siblings.findIndex((entry) => entry.id === message.id);
  const feedback = editing.feedback.find((entry) => entry.assistantMessageId === message.id);
  const content = message.parts
    .filter((part) => part.type === "text")
    .map((part) => part.text)
    .join("");
  return (
    <>
      {role === "user" ? (
        <IconButton
          aria-label="Chỉnh sửa câu hỏi"
          title="Chỉnh sửa câu hỏi"
          size="sm"
          prominence="internal"
          disabled={editing.busy}
          onClick={() => {
            setText(content);
            editRequest.current = crypto.randomUUID();
            setEditor(true);
          }}
        >
          <Pencil />
        </IconButton>
      ) : (
        <IconButton
          aria-label="Tạo lại câu trả lời"
          title="Tạo lại câu trả lời"
          size="sm"
          prominence="internal"
          disabled={editing.busy}
          onClick={() => {
            void editing
              .regenerate(node.parentMessageId!, regenerateRequest.current)
              .then(() => {
                regenerateRequest.current = crypto.randomUUID();
              })
              .catch(() => {});
          }}
        >
          <RotateCcw />
        </IconButton>
      )}
      {siblings.length > 1 && (
        <div
          role="group"
          aria-label={role === "user" ? "Phiên bản câu hỏi" : "Phiên bản câu trả lời"}
          className="flex items-center gap-1 text-xs"
        >
          <IconButton
            size="sm"
            prominence="internal"
            aria-label="Phiên bản trước"
            disabled={editing.busy || index <= 0}
            onClick={() => void editing.branch(siblings[index - 1]!.id, message.id).catch(() => {})}
          >
            <ChevronLeft />
          </IconButton>
          <span>
            {index + 1} / {siblings.length}
          </span>
          <IconButton
            size="sm"
            prominence="internal"
            aria-label="Phiên bản sau"
            disabled={editing.busy || index >= siblings.length - 1}
            onClick={() => void editing.branch(siblings[index + 1]!.id, message.id).catch(() => {})}
          >
            <ChevronRight />
          </IconButton>
        </div>
      )}
      {role === "assistant" && content.trim() && (
        <IconButton
          aria-label="Đánh giá câu trả lời"
          title="Đánh giá câu trả lời"
          size="sm"
          prominence="internal"
          aria-pressed={!!feedback}
          disabled={editing.busy}
          onClick={() => setFeedbackOpen(true)}
        >
          {feedback?.positive === false ? <ThumbsDown /> : <ThumbsUp />}
        </IconButton>
      )}
      {editor && (
        <ChatDialog
          open
          onOpenChange={setEditor}
          title="Chỉnh sửa câu hỏi"
          description="Tạo một nhánh mới. Câu hỏi và câu trả lời cũ vẫn có thể chọn lại."
          submitLabel="Lưu và gửi"
          onSubmit={async () => {
            await editing.edit(message.id, text, editRequest.current);
          }}
        >
          <textarea
            aria-label="Nội dung câu hỏi"
            required
            maxLength={32000}
            rows={6}
            className={chatField}
            value={text}
            onChange={(event) => {
              setText(event.target.value);
              editRequest.current = crypto.randomUUID();
            }}
          />
        </ChatDialog>
      )}
      {feedbackOpen && (
        <FeedbackEditor
          sessionId={editing.sessionId}
          messageId={message.id}
          feedback={feedback}
          onClose={() => setFeedbackOpen(false)}
        />
      )}
    </>
  );
}

function FeedbackEditor({
  sessionId,
  messageId,
  feedback,
  onClose,
}: {
  sessionId: string;
  messageId: string;
  feedback?: Feedback;
  onClose: () => void;
}) {
  const cache = useQueryClient();
  const [rating, setRating] = useState(
    feedback?.positive === false ? "negative" : feedback?.positive === true ? "positive" : "",
  );
  const [comment, setComment] = useState(feedback?.comment ?? "");
  const [reason, setReason] = useState(feedback?.reason ?? "");
  const [remove, setRemove] = useState(false);
  return (
    <ChatDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title="Đánh giá câu trả lời"
      description="Đánh giá gắn với đúng phiên bản câu trả lời này."
      onSubmit={async () => {
        const path = { sessionId, assistantMessageId: messageId };
        if (remove)
          await removeChatFeedback({
            path,
            headers: sameOriginMutationHeaders,
            throwOnError: true,
          });
        else
          await setChatFeedback({
            path,
            body: { positive: rating ? rating === "positive" : null, comment, reason },
            headers: sameOriginMutationHeaders,
            throwOnError: true,
          });
        await cache.invalidateQueries({ queryKey: ["chat-feedback", sessionId] });
      }}
    >
      <fieldset disabled={remove} className="space-y-4">
        <label className="block space-y-1">
          <span>Mức độ hữu ích</span>
          <Select value={rating} onChange={(e) => setRating(e.target.value)}>
            <option value="">Chỉ góp ý</option>
            <option value="positive">Hữu ích</option>
            <option value="negative">Chưa hữu ích</option>
          </Select>
        </label>
        <label className="block space-y-1">
          <span>Lý do</span>
          <Select value={reason} onChange={(e) => setReason(e.target.value)}>
            <option value="">Chọn lý do (không bắt buộc)</option>
            <option value="incorrect">Thông tin chưa đúng</option>
            <option value="incomplete">Thiếu thông tin</option>
            <option value="sources">Nguồn chưa phù hợp</option>
            <option value="style">Cách trình bày</option>
            {reason && !["incorrect", "incomplete", "sources", "style"].includes(reason) && (
              <option value={reason}>{reason}</option>
            )}
          </Select>
        </label>
        <label className="block space-y-1">
          <span>Góp ý</span>
          <textarea
            className={chatField}
            rows={4}
            maxLength={4000}
            required={!rating}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
        </label>
      </fieldset>
      {feedback && (
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={remove} onChange={(e) => setRemove(e.target.checked)} />
          Xóa đánh giá đã lưu
        </label>
      )}
    </ChatDialog>
  );
}
