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
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import type { ConnectionState } from "./chat-transport";
import { ChatMessageActions, ChatUserMessageContent } from "./chat-message-actions";

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
                  Bạn muốn tìm hiểu điều gì?
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
                    aria-label="Đến tin nhắn mới nhất"
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
              {connection === "recovering" && (
                <p role="status" className="mb-2 font-secondary-body text-content-secondary">
                  Đang kết nối lại câu trả lời…
                </p>
              )}
              {connection === "uncertain" || error ? (
                <div
                  role="alert"
                  className="mb-3 flex flex-wrap items-center gap-2 font-secondary-body text-content-secondary"
                >
                  <span>{error ?? "Chưa xác nhận được trạng thái câu trả lời."}</span>
                  <Button
                    size="sm"
                    prominence="secondary"
                    pending={checking}
                    onClick={() => void onCheck()}
                  >
                    Kiểm tra hội thoại
                  </Button>
                </div>
              ) : null}
              <ComposerPrimitive.Root className="flex w-full flex-col gap-2 rounded-2xl border border-border-default bg-surface-raised p-2.5 shadow-sm transition-colors focus-within:border-border-strong">
                <ComposerPrimitive.Input
                  aria-label="Câu hỏi"
                  placeholder="Nhập câu hỏi…"
                  rows={1}
                  maxLength={32000}
                  className="max-h-48 min-h-12 w-full resize-none bg-transparent px-2.5 py-1.5 text-base leading-6 outline-none placeholder:text-content-muted"
                />
                <div className="flex items-center justify-between gap-3">
                  {modelPicker}
                  <AuiIf condition={(state) => !state.thread.isRunning}>
                    <ComposerPrimitive.Send asChild>
                      <IconButton aria-label="Gửi câu hỏi" prominence="primary">
                        <ArrowUp />
                      </IconButton>
                    </ComposerPrimitive.Send>
                  </AuiIf>
                  <AuiIf condition={(state) => state.thread.isRunning}>
                    <IconButton
                      aria-label={stopping ? "Đang yêu cầu dừng" : "Dừng trả lời"}
                      prominence="secondary"
                      disabled={stopping}
                      onClick={onStop}
                    >
                      <Square />
                    </IconButton>
                  </AuiIf>
                </div>
              </ComposerPrimitive.Root>
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
        <MessagePrimitive.Parts />
      </ChatUserMessageContent>
    </MessagePrimitive.Root>
  );
}

function AssistantMessage({ readOnly }: { readOnly: boolean }) {
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
        {(serverStatus === "CANCELED" || canceled) && (
          <p className="mt-2 font-secondary-body text-content-muted">Đã dừng</p>
        )}
        {serverStatus === "FAILED" && (
          <p role="status" className="mt-2 font-secondary-body text-content-secondary">
            Câu trả lời bị gián đoạn. Nội dung đã nhận được giữ lại.
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
                aria-label="Sao chép câu trả lời"
                title="Sao chép câu trả lời"
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
