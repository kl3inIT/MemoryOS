import { createElement, use, type ReactNode } from "react";
import { TooltipIconButton } from "@/components/composites/tooltip-icon-button";
import { Link } from "@tanstack/react-router";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ApplicationSessionContext } from "@/features/identity/application-session-context";
import {
  ActionBarPrimitive,
  AuiIf,
  getMcpAppFromToolPart,
  MessagePrimitive,
  useAuiState,
  type PartState,
} from "@assistant-ui/react";
import { Copy, FileX, ShieldAlert } from "lucide-react";
import { MarkdownText } from "@/components/assistant-ui/elements/markdown-text";
import { ThinkingIndicator } from "@/components/assistant-ui/elements/thinking-indicator";
import { ErrorState } from "@/components/assistant-ui/elements/error-state";
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
import { ChatMcpApproval } from "@/features/chat/mcp/chat-mcp-step";
import { ChatMcpUnavailable } from "@/features/chat/mcp/chat-mcp-unavailable";
import { ChatResearchView } from "@/features/chat/research/chat-research-view";
import type { ResearchState } from "@/features/chat/research/chat-research";
import { ChatMessageActions, ChatUserMessageContent } from "./chat-message-actions";
import { ChatFilePart, ChatSharedFilePart, ChatMessageAttachment } from "./chat-attachments";
import { ChatArtifactCards } from "./chat-artifact-view";
import { ChatReplyAddon } from "./chat-reply-addon";
import { ChatMessageTiming } from "./chat-message-timing";
import { ChatUserMessageQuote, ChatUserText } from "./chat-quote";

/**
 * Reasoning and tool steps share one disclosure, except a call that waits for the person (MEM-253): as ChatGPT and
 * Claude ask, its approval sits in the answer itself, never inside the collapsed thinking, and stays there with its
 * outcome after the person answers.
 */
function activityGroups(part: PartState): readonly "group-activity"[] | null {
  if (part.type === "reasoning") return ["group-activity"];
  // MEM-275: a call's MCP Apps view stands in the answer, outside the collapsed activity, as Claude shows it.
  if (part.type === "tool-call")
    return ("approval" in part && part.approval) || getMcpAppFromToolPart(part)
      ? null
      : ["group-activity"];
  return null;
}
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
  const waiting = useAuiState(
    (state) => state.message.metadata.custom.waiting as boolean | undefined,
  );
  return (
    <ThinkingIndicator
      role="status"
      label={waiting ? ui("Đang chờ lượt…") : ui("Đang suy nghĩ…")}
      className="mb-3"
    />
  );
}

/** Why a committed turn failed, in words the reader can act on; a model manager is sent to fix the credential. */
function ChatFailureNotice({ code }: { code?: string }) {
  const ui = useAppTranslation();
  // A shared conversation renders outside the signed-in session.
  const manager = use(ApplicationSessionContext)?.capabilities.includes("MODELS_MANAGE") ?? false;
  const answered = useAuiState((state) =>
    state.message.parts.some((part) => part.type === "text" && part.text.trim().length > 0),
  );
  let title: string;
  let detail: ReactNode;
  if (code === "CHAT_MODEL_OUTPUT_LIMIT") {
    title = ui("Chạm giới hạn output");
    detail = ui(
      "Mô hình đã dừng vì chạm giới hạn độ dài output trong cấu hình mô hình. Hãy tăng giới hạn output của mô hình hoặc chọn mô hình khác.",
    );
  } else if (code === "CHAT_CONTEXT_LIMIT") {
    title = ui("Vượt cửa sổ ngữ cảnh");
    detail = ui(
      "Hội thoại vượt quá cửa sổ ngữ cảnh của mô hình. Hãy bắt đầu hội thoại mới hoặc chọn mô hình có ngữ cảnh lớn hơn.",
    );
  } else if (code === "CHAT_PROVIDER_RATE_LIMITED") {
    title = ui("Nhà cung cấp model đang giới hạn");
    detail = ui(
      "Đã chạm giới hạn tốc độ của nhà cung cấp. Hãy thử lại sau ít phút hoặc chọn model khác.",
    );
  } else if (code === "CHAT_PROVIDER_CREDENTIAL_REJECTED" && manager) {
    title = ui("Provider từ chối API key");
    detail = <Link to="/admin/ai-providers">{ui("Cập nhật API key")}</Link>;
  } else if (code === "CHAT_PROVIDER_CREDENTIAL_REJECTED") {
    title = ui("Model không dùng được");
    detail = ui("Hãy chọn model khác hoặc báo quản trị viên.");
  } else if (answered) {
    title = ui("Câu trả lời bị gián đoạn");
    detail = ui("Nội dung đã nhận được giữ lại.");
  } else {
    title = ui("Không tạo được câu trả lời");
    detail = ui("Hãy thử lại.");
  }
  // The assistant-ui Error state element where the answer would have been, titled by the failure's kind (Onyx
  // ErrorBanner). The server status drives it, so a failed answer reloaded from history shows it too.
  return <ErrorState className="mt-3" title={title} detail={detail} />;
}

export function AssistantMessage({ readOnly }: { readOnly: boolean }) {
  const ui = useAppTranslation();

  const serverStatus = useAuiState((state) => state.message.metadata.custom.serverStatus);
  const failureCode = useAuiState(
    (state) => state.message.metadata.custom.failureCode as string | undefined,
  );
  const refusalReason = useAuiState(
    (state) => state.message.metadata.custom.refusalReason as string | undefined,
  );
  const canceled = useAuiState(
    (state) =>
      state.message.status?.type === "incomplete" && state.message.status.reason === "cancelled",
  );
  const Addon = use(ChatReplyAddon);
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
                // The registered MCP Apps renderer draws a view's part.
                if (getMcpAppFromToolPart(part)) return null;
                return part.approval ? (
                  <ChatMcpApproval part={part} />
                ) : (
                  <ChatToolStep part={part} />
                );
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
        {!readOnly && <ChatMcpUnavailable />}
        {Addon && createElement(Addon)}
        {refusalReason && (
          <p className="mt-2 flex items-center gap-1.5 font-secondary-body text-content-muted">
            {refusalReason === "blocked_topic" ? (
              <ShieldAlert className="size-4" aria-hidden />
            ) : (
              <FileX className="size-4" aria-hidden />
            )}
            {refusalReason === "blocked_topic"
              ? ui("Chủ đề bị hạn chế")
              : ui("Không có trong tài liệu của tổ chức")}
          </p>
        )}
        {(serverStatus === "CANCELED" || canceled) && (
          <p className="mt-2 font-secondary-body text-content-muted">{ui("Đã dừng")}</p>
        )}
        {serverStatus === "FAILED" && <ChatFailureNotice code={failureCode} />}
        <ActionBarPrimitive.Root hideWhenRunning className="mt-3 flex flex-wrap items-center gap-1">
          <AuiIf
            condition={(state) =>
              state.message.parts.some(
                (part) => part.type === "text" && part.text.trim().length > 0,
              )
            }
          >
            <ActionBarPrimitive.Copy asChild>
              <TooltipIconButton
                aria-label={ui("Sao chép câu trả lời")}
                prominence="internal"
                size="sm"
              >
                <Copy />
              </TooltipIconButton>
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
