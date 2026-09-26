import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  ActionBarPrimitive,
  AuiIf,
  MessagePrimitive,
  groupPartByType,
  useAuiState,
} from "@assistant-ui/react";
import { Copy } from "lucide-react";
import { MarkdownText } from "@/components/assistant-ui/elements/markdown-text";
import { ThinkingIndicator } from "@/components/assistant-ui/elements/thinking-indicator";
import { IconButton } from "@/components/ui/icon-button";
import {
  ChatMarkdownLink,
  ChatSources,
  ChatSourcesProvider,
} from "@/features/chat/sources/chat-sources";
import { remarkCitations, remarkSandboxLinks } from "@/features/chat/sources/chat-evidence";
import { ChatReadAloudButton } from "@/features/voice/chat-read-aloud";
import { ChatImages } from "@/features/chat/image/chat-images";
import { ChatGeneratedFiles } from "@/features/chat/interpreter/chat-generated-files";
import {
  ChatActivityGroup,
  ChatReasoningStep,
  ChatToolStep,
} from "@/features/chat/activity/chat-activity-view";
import { ChatResearchView } from "@/features/chat/research/chat-research-view";
import type { ResearchState } from "@/features/chat/research/chat-research";
import { ChatMessageActions, ChatUserMessageContent } from "./chat-message-actions";
import { ChatFilePart, ChatSharedFilePart, ChatMessageAttachment } from "./chat-attachments";
import { ChatArtifactCards } from "./chat-artifact-view";
import { ChatMessageTiming } from "./chat-message-timing";
import { ChatUserMessageQuote, ChatUserText } from "./chat-quote";

const activityGroups = groupPartByType({
  reasoning: ["group-activity"],
  "tool-call": ["group-activity"],
});
const answerPlugins = [remarkCitations, remarkSandboxLinks];
const answerComponents = { a: ChatMarkdownLink };

const noText = () => null;
const noFile = () => null;

/**
 * As Onyx, a question's attached files sit above its bubble on the page background, as the same outlined cards as
 * generated files, instead of blending into the bubble; the bubble holds only the text.
 */
export function UserMessage({ readOnly }: { readOnly: boolean }) {
  return (
    <MessagePrimitive.Root data-aui-quote-selectable="false" className="flex flex-col items-end">
      <div className="mb-2 flex max-w-9/10 flex-wrap justify-end gap-2 empty:hidden">
        <MessagePrimitive.Attachments>
          {() => <ChatMessageAttachment readOnly={readOnly} />}
        </MessagePrimitive.Attachments>
        <MessagePrimitive.Parts
          components={{ Text: noText, File: readOnly ? ChatSharedFilePart : ChatFilePart }}
        />
      </div>
      <ChatUserMessageContent readOnly={readOnly}>
        <ChatUserMessageQuote />
        <MessagePrimitive.Parts components={{ Text: ChatUserText, File: noFile }} />
      </ChatUserMessageContent>
    </MessagePrimitive.Root>
  );
}

/**
 * Before the first part arrives. Afterwards the last activity group stays live while the model writes its next
 * call (ChatActivityGroup), so the answer never shows a finished header above a run that is still working.
 */
function ChatPendingIndicator() {
  const ui = useAppTranslation();
  return <ThinkingIndicator role="status" label={ui("Đang suy nghĩ…")} className="mb-3" />;
}

export function AssistantMessage({ readOnly }: { readOnly: boolean }) {
  const ui = useAppTranslation();

  const serverStatus = useAuiState((state) => state.message.metadata.custom.serverStatus);
  const failureCode = useAuiState(
    (state) => state.message.metadata.custom.failureCode as string | undefined,
  );
  const canceled = useAuiState(
    (state) =>
      state.message.status?.type === "incomplete" && state.message.status.reason === "cancelled",
  );
  return (
    <MessagePrimitive.Root className="group/message min-w-0 wrap-anywhere">
      <ChatSourcesProvider>
        <MessagePrimitive.GroupedParts groupBy={activityGroups} indicator="empty">
          {({ part, children }) => {
            switch (part.type) {
              case "group-activity":
                return (
                  <ChatActivityGroup
                    indices={part.indices}
                    running={part.status.type === "running"}
                  >
                    {children}
                  </ChatActivityGroup>
                );
              case "reasoning":
                return <ChatReasoningStep running={part.status.type === "running"} />;
              case "tool-call":
                return <ChatToolStep part={part} />;
              case "text":
                // Only the answer body can be quoted, not activity, sources or actions.
                return part.text.trim() ? (
                  <div data-aui-quote-selectable>
                    <MarkdownText remarkPlugins={answerPlugins} components={answerComponents} />
                  </div>
                ) : null;
              case "data":
                return part.name === "research" ? (
                  <ChatResearchView research={part.data as ResearchState} />
                ) : null;
              case "indicator":
                return <ChatPendingIndicator />;
              default:
                return null;
            }
          }}
        </MessagePrimitive.GroupedParts>
        <ChatArtifactCards />
        <ChatImages />
        <ChatGeneratedFiles />
        {(serverStatus === "CANCELED" || canceled) && (
          <p className="mt-2 font-secondary-body text-content-muted">{ui("Đã dừng")}</p>
        )}
        {serverStatus === "FAILED" && (
          <p role="status" className="mt-2 font-secondary-body text-content-secondary">
            {failureCode === "CHAT_MODEL_OUTPUT_LIMIT"
              ? ui(
                  "Mô hình đã dừng vì chạm giới hạn độ dài output trong cấu hình mô hình. Hãy tăng giới hạn output của mô hình hoặc chọn mô hình khác.",
                )
              : failureCode === "CHAT_CONTEXT_LIMIT"
                ? ui(
                    "Hội thoại vượt quá cửa sổ ngữ cảnh của mô hình. Hãy bắt đầu hội thoại mới hoặc chọn mô hình có ngữ cảnh lớn hơn.",
                  )
                : ui("Câu trả lời bị gián đoạn. Nội dung đã nhận được giữ lại.")}
          </p>
        )}
        <ActionBarPrimitive.Root hideWhenRunning className="mt-3 flex flex-wrap items-center gap-1">
          <AuiIf
            condition={(state) =>
              state.message.parts.some(
                (part) => part.type === "text" && part.text.trim().length > 0,
              )
            }
          >
            <ActionBarPrimitive.Copy asChild>
              <IconButton
                aria-label={ui("Sao chép câu trả lời")}
                title={ui("Sao chép câu trả lời")}
                prominence="internal"
                size="sm"
              >
                <Copy />
              </IconButton>
            </ActionBarPrimitive.Copy>
            <ChatReadAloudButton />
          </AuiIf>
          <ChatSources />
          {!readOnly && <ChatMessageActions author="assistant" />}
          <ChatMessageTiming />
        </ActionBarPrimitive.Root>
      </ChatSourcesProvider>
    </MessagePrimitive.Root>
  );
}
