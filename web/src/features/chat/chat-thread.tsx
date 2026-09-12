import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  ActionBarPrimitive,
  AuiIf,
  ComposerPrimitive,
  MessagePrimitive,
  ThreadPrimitive,
  useAuiState,
  type TextMessagePartProps,
} from "@assistant-ui/react";
import { ArrowDown, ArrowUp, Copy, Square } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ErrorState } from "@/components/assistant-ui/elements/error-state";
import { ConnectionState as ConnectionNotice } from "@/components/assistant-ui/elements/connection-state";
import { MarkdownText } from "@/components/assistant-ui/elements/markdown-text";
import {
  ChatMarkdownLink,
  ChatSearchStatus,
  ChatSources,
  ChatSourcesProvider,
  ChatSourcesWorkspace,
} from "./chat-sources";
import { remarkCitations } from "./chat-evidence";
import { cn } from "@/lib/utils";
import { IconButton } from "@/components/ui/icon-button";
import type { ConnectionState } from "./chat-transport";
import { ChatMessageActions, ChatUserMessageContent } from "./chat-message-actions";
import {
  ChatComposerFiles,
  ChatFilePart,
  ChatSharedFilePart,
  ChatMessageAttachment,
} from "./chat-attachments";
import { ChatComposerRoot, ChatComposerSend } from "./chat-composer";
import { ComposerAttachments } from "@/components/assistant-ui/elements/attachment.aui";
import { ChatArtifactCards } from "./chat-artifact-view";

export function ChatThread({
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
}: {
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
}) {
  const ui = useAppTranslation();

  const { t } = useTranslation("chatStatus");
  const isEmpty = useAuiState((state) => state.thread.isEmpty);
  return (
    <ChatSourcesWorkspace>
      <ThreadPrimitive.Root
        className="aui-root flex h-full min-h-0 min-w-0 flex-1 flex-col"
        style={{ ["--thread-max-width" as string]: "48rem" }}
      >
        <ThreadPrimitive.Viewport
          data-testid="chat-viewport"
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
              <ComposerPrimitive.AttachmentDropzone className="rounded-2xl data-[dragging]:ring-2">
                <ChatComposerRoot className="flex w-full flex-col gap-2 rounded-2xl border border-border-default bg-surface-raised p-2.5 shadow-sm transition-colors focus-within:border-border-strong">
                  <ComposerAttachments />
                  <ComposerPrimitive.Input
                    aria-label={ui("Câu hỏi")}
                    placeholder={ui("Nhập câu hỏi…")}
                    rows={1}
                    maxLength={32000}
                    className="max-h-48 min-h-12 w-full resize-none bg-transparent px-2.5 py-1.5 text-base leading-6 outline-none placeholder:text-content-muted"
                  />
                  <AuiIf condition={(state) => state.composer.attachments.length > 20}>
                    <p role="alert" className="text-sm">
                      {ui("Mỗi tin nhắn có tối đa 20 tệp. Hãy gỡ bớt trước khi gửi.")}
                    </p>
                  </AuiIf>
                  <div
                    data-testid="chat-composer-actions"
                    className="flex flex-nowrap items-center justify-between gap-2"
                  >
                    <div className="flex min-w-0 items-center gap-1">
                      <ChatComposerFiles />
                      {modelPicker}
                    </div>
                    <AuiIf condition={(state) => !state.thread.isRunning}>
                      <ChatComposerSend asChild>
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
                </ChatComposerRoot>
              </ComposerPrimitive.AttachmentDropzone>
            </ThreadPrimitive.ViewportFooter>
          )}
          {isEmpty && afterComposer && (
            <div className="mx-auto w-full max-w-(--thread-max-width) pb-8">{afterComposer}</div>
          )}
        </ThreadPrimitive.Viewport>
      </ThreadPrimitive.Root>
    </ChatSourcesWorkspace>
  );
}

function UserMessage({ readOnly }: { readOnly: boolean }) {
  return (
    <MessagePrimitive.Root className="flex flex-col items-end">
      <ChatUserMessageContent readOnly={readOnly}>
        <MessagePrimitive.Attachments>
          {() => <ChatMessageAttachment readOnly={readOnly} />}
        </MessagePrimitive.Attachments>
        <MessagePrimitive.Parts
          components={{ File: readOnly ? ChatSharedFilePart : ChatFilePart }}
        />
      </ChatUserMessageContent>
    </MessagePrimitive.Root>
  );
}

function AssistantMessage({ readOnly }: { readOnly: boolean }) {
  const ui = useAppTranslation();

  const serverStatus = useAuiState((state) => state.message.metadata.custom.serverStatus);
  const canceled = useAuiState(
    (state) =>
      state.message.status?.type === "incomplete" && state.message.status.reason === "cancelled",
  );
  return (
    <MessagePrimitive.Root className="min-w-0 [overflow-wrap:anywhere]">
      <ChatSourcesProvider>
        <ChatSearchStatus />
        <MessagePrimitive.Parts components={{ Text: AnswerMarkdown, Empty: EmptyAnswer }} />
        <ChatArtifactCards />
        {(serverStatus === "CANCELED" || canceled) && (
          <p className="mt-2 font-secondary-body text-content-muted">{ui("Đã dừng")}</p>
        )}
        {serverStatus === "FAILED" && (
          <p role="status" className="mt-2 font-secondary-body text-content-secondary">
            {ui("Câu trả lời bị gián đoạn. Nội dung đã nhận được giữ lại.")}
          </p>
        )}
        <ActionBarPrimitive.Root className="mt-3 flex flex-wrap items-center gap-1">
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
          </AuiIf>
          <ChatSources />
          {!readOnly && <ChatMessageActions role="assistant" />}
        </ActionBarPrimitive.Root>
      </ChatSourcesProvider>
    </MessagePrimitive.Root>
  );
}

function EmptyAnswer() {
  return null;
}

function AnswerMarkdown({ text }: TextMessagePartProps) {
  if (!text.trim()) return null;
  return <MarkdownText remarkPlugins={[remarkCitations]} components={{ a: ChatMarkdownLink }} />;
}
