import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChatPromptShortcutPopover } from "@/features/agents/prompt-shortcuts";
import {
  ActionBarPrimitive,
  AuiIf,
  ComposerPrimitive,
  MessagePrimitive,
  ThreadPrimitive,
  groupPartByType,
  useAuiState,
} from "@assistant-ui/react";
import { ArrowDown, ArrowUp, Copy, Square } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ErrorState } from "@/components/assistant-ui/elements/error-state";
import { ConnectionState as ConnectionNotice } from "@/components/assistant-ui/elements/connection-state";
import { MarkdownText } from "@/components/assistant-ui/elements/markdown-text";
import {
  ChatMarkdownLink,
  ChatSources,
  ChatSourcesProvider,
  ChatSourcesWorkspace,
} from "./chat-sources";
import { remarkCitations, remarkSandboxLinks } from "./chat-evidence";
import { cn } from "@/lib/utils";
import { useChatPreferences } from "@/features/identity/chat-preferences";
import { IconButton } from "@/components/ui/icon-button";
import type { ConnectionState } from "./chat-transport";
import { ChatMessageActions, ChatUserMessageContent } from "./chat-message-actions";
import { ChatFilePart, ChatSharedFilePart, ChatMessageAttachment } from "./chat-attachments";
import { ChatComposerDraft, ChatComposerRoot, ChatComposerSend } from "./chat-composer";
import {
  ChatDictationAutoSend,
  ChatDictationButton,
  ChatDictationStrip,
  ChatVoiceFailure,
} from "@/features/voice/chat-dictation-controls";
import { ChatReadAloudButton } from "@/features/voice/chat-read-aloud";
import {
  ChatAutoListen,
  ChatAutoPlayback,
  ChatSpeakingIndicator,
} from "@/features/voice/chat-auto-playback";
import { useAutoPlayback } from "@/features/voice/use-chat-auto-playback";
import { ComposerAttachments } from "@/components/assistant-ui/elements/attachment.aui";
import { ChatArtifactCards } from "./chat-artifact-view";
import { ChatImages } from "./chat-images";
import { ChatGeneratedFiles } from "./chat-generated-files";
import { ChatMessageTiming } from "./chat-message-timing";
import { ChatActivityGroup, ChatReasoningStep, ChatToolStep } from "./chat-activity-view";
import { ChatResearchView } from "./chat-research-view";
import type { ResearchState } from "./chat-research";
import { ThinkingIndicator } from "@/components/assistant-ui/elements/thinking-indicator";

const activityGroups = groupPartByType({
  reasoning: ["group-activity"],
  "tool-call": ["group-activity"],
});
const answerPlugins = [remarkCitations, remarkSandboxLinks];
const answerComponents = { a: ChatMarkdownLink };
import {
  ChatComposerQuote,
  ChatSelectionToolbar,
  ChatUserMessageQuote,
  ChatUserText,
} from "./chat-quote";

export function ChatThread({
  composerMenu,
  modelPicker,
  modelNotice,
  connection,
  stopping,
  onStop,
  error,
  onCheck,
  checking,
  starters,
  welcome,
  afterComposer,
  readOnly = false,
  sendDisabled = false,
}: {
  /** Left of the composer toolbar: the `+` menu for files and tools. */
  composerMenu?: ReactNode;
  /** Right of the composer toolbar, beside Send. */
  modelPicker: ReactNode;
  modelNotice?: string;
  connection: ConnectionState;
  stopping: boolean;
  onStop: () => void;
  error?: string;
  onCheck: () => Promise<void>;
  checking: boolean;
  starters?: ReactNode;
  welcome?: ReactNode;
  afterComposer?: ReactNode;
  readOnly?: boolean;
  /** Blocks Send while the conversation's agent and its tool policy are still unknown. */
  sendDisabled?: boolean;
}) {
  const ui = useAppTranslation();

  const { t } = useTranslation("chatStatus");
  const isEmpty = useAuiState((state) => state.thread.isEmpty);
  const dictating = useAuiState((state) => state.composer.dictation != null);
  const reading = useAutoPlayback().phase !== "idle";
  // Onyx "Chat Auto-scroll": follow the answer while it is written unless the member turned it off.
  const autoScroll = useChatPreferences().data?.autoScroll ?? true;
  return (
    <ChatSourcesWorkspace>
      <ThreadPrimitive.Root
        className="aui-root flex h-full min-h-0 min-w-0 flex-1 flex-col [&_[data-chat-search-match]]:rounded-xl [&_[data-chat-search-match]]:bg-highlight-match/40"
        style={{ ["--thread-max-width" as string]: "48rem" }}
      >
        <ThreadPrimitive.Viewport
          data-testid="chat-viewport"
          autoScroll={autoScroll}
          className={cn(
            "relative flex min-h-0 flex-1 flex-col overflow-x-hidden overflow-y-auto px-4 pt-6 [scrollbar-gutter:stable] sm:px-8",
            isEmpty && !welcome && "justify-center",
          )}
        >
          <AuiIf condition={(state) => state.thread.isEmpty}>
            <div className="mx-auto mb-8 w-full max-w-(--thread-max-width) text-center">
              {welcome ?? (
                <h1 className="text-2xl font-medium tracking-tight sm:text-3xl">
                  {ui("Bạn muốn tìm hiểu điều gì?")}
                </h1>
              )}
              {!welcome && starters}
            </div>
          </AuiIf>
          <div className="mx-auto w-full max-w-(--thread-max-width) space-y-7 pb-8 font-main-content-body empty:hidden">
            <ThreadPrimitive.Messages>
              {({ message }) =>
                message.role === "user" ? (
                  <UserMessage readOnly={readOnly} />
                ) : (
                  <AssistantMessage readOnly={readOnly} />
                )
              }
            </ThreadPrimitive.Messages>
          </div>
          {!readOnly && (
            <ThreadPrimitive.ViewportFooter
              className={cn(
                "relative mx-auto flex w-full max-w-(--thread-max-width) flex-col bg-surface-base pb-[max(1rem,env(safe-area-inset-bottom))] pt-3",
                !isEmpty && "sticky bottom-0 mt-auto rounded-t-2xl",
              )}
            >
              <div className="absolute -top-11 left-1/2 -translate-x-1/2">
                <ThreadPrimitive.ScrollToBottom asChild>
                  <IconButton
                    aria-label={ui("Đến tin nhắn mới nhất")}
                    prominence="secondary"
                    className="disabled:hidden"
                  >
                    <ArrowDown />
                  </IconButton>
                </ThreadPrimitive.ScrollToBottom>
              </div>
              {modelNotice && (
                <p role="status" className="mb-2 text-sm text-content-secondary">
                  {modelNotice}
                </p>
              )}
              {connection === "recovering" && !error && (
                <ConnectionNotice className="mb-2" label={t("reconnecting")} />
              )}
              {connection === "uncertain" || error ? (
                <ErrorState
                  className="mb-3"
                  title={t("unconfirmed")}
                  detail={error ?? t("checkBeforeSending")}
                  action={{ label: t("check"), pending: checking, onClick: () => void onCheck() }}
                />
              ) : null}
              <ChatVoiceFailure />
              <ChatSpeakingIndicator />
              <ChatAutoPlayback />
              <ComposerPrimitive.Unstable_TriggerPopoverRoot>
                <ComposerPrimitive.AttachmentDropzone className="rounded-2xl data-[dragging]:ring-2">
                  <ChatComposerRoot className="flex w-full flex-col gap-2 rounded-2xl border border-border-default bg-surface-raised p-2.5 shadow-sm transition-colors focus-within:border-border-strong">
                    <ChatComposerDraft />
                    <ChatDictationAutoSend />
                    <ChatAutoListen />
                    <ChatComposerQuote />
                    <ComposerAttachments />
                    <ComposerPrimitive.Input
                      aria-label={ui("Câu hỏi")}
                      placeholder={
                        dictating
                          ? ui("Đang nghe…")
                          : reading
                            ? ui("MemoryOS đang đọc…")
                            : ui("Nhập câu hỏi…")
                      }
                      rows={1}
                      maxLength={32000}
                      className="max-h-48 min-h-12 w-full resize-none bg-transparent px-2.5 py-1.5 text-base leading-6 outline-none placeholder:text-content-muted"
                    />
                    <ChatPromptShortcutPopover />
                    <ChatDictationStrip />
                    <AuiIf condition={(state) => state.composer.attachments.length > 20}>
                      <p role="alert" className="text-sm">
                        {ui("Mỗi tin nhắn có tối đa 20 tệp. Hãy gỡ bớt trước khi gửi.")}
                      </p>
                    </AuiIf>
                    <div
                      data-testid="chat-composer-actions"
                      className="flex flex-nowrap items-center justify-between gap-2"
                    >
                      {composerMenu ?? <span />}
                      <div className="flex min-w-0 items-center gap-1">
                        {modelPicker}
                        <ChatDictationButton />
                        <AuiIf condition={(state) => !state.thread.isRunning}>
                          <ChatComposerSend asChild disabled={sendDisabled}>
                            <IconButton aria-label={ui("Gửi câu hỏi")} prominence="primary">
                              <ArrowUp />
                            </IconButton>
                          </ChatComposerSend>
                        </AuiIf>
                        <AuiIf condition={(state) => state.thread.isRunning}>
                          <IconButton
                            aria-label={stopping ? ui("Đang yêu cầu dừng") : ui("Dừng trả lời")}
                            prominence="secondary"
                            disabled={stopping}
                            onClick={onStop}
                          >
                            <Square />
                          </IconButton>
                        </AuiIf>
                      </div>
                    </div>
                  </ChatComposerRoot>
                </ComposerPrimitive.AttachmentDropzone>
              </ComposerPrimitive.Unstable_TriggerPopoverRoot>
            </ThreadPrimitive.ViewportFooter>
          )}
          {isEmpty && afterComposer && (
            <div className="mx-auto w-full max-w-(--thread-max-width) pb-8">{afterComposer}</div>
          )}
        </ThreadPrimitive.Viewport>
        {!readOnly && <ChatSelectionToolbar />}
      </ThreadPrimitive.Root>
    </ChatSourcesWorkspace>
  );
}

const noText = () => null;
const noFile = () => null;

/**
 * As Onyx, a question's attached files sit above its bubble on the page background, as the same outlined cards as
 * generated files, instead of blending into the bubble; the bubble holds only the text.
 */
function UserMessage({ readOnly }: { readOnly: boolean }) {
  return (
    <MessagePrimitive.Root data-aui-quote-selectable="false" className="flex flex-col items-end">
      <div className="mb-2 flex max-w-[90%] flex-wrap justify-end gap-2 empty:hidden">
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

function AssistantMessage({ readOnly }: { readOnly: boolean }) {
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
    <MessagePrimitive.Root className="group/message min-w-0 [overflow-wrap:anywhere]">
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
          {!readOnly && <ChatMessageActions role="assistant" />}
          <ChatMessageTiming />
        </ActionBarPrimitive.Root>
      </ChatSourcesProvider>
    </MessagePrimitive.Root>
  );
}
