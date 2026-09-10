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

export function ChatThread({
  modelPicker,
  modelNotice,
  connection,
  stopping,
  onStop,
  error,
  onCheck,
  checking,
}: {
  modelPicker: ReactNode;
  modelNotice?: string;
  connection: ConnectionState;
  stopping: boolean;
  onStop: () => void;
  error?: string;
  onCheck: () => Promise<void>;
  checking: boolean;
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
            isEmpty && "justify-center",
          )}
        >
          <AuiIf condition={(state) => state.thread.isEmpty}>
            <div className="mx-auto mb-8 w-full max-w-(--thread-max-width) text-center">
              <h1 className="text-2xl font-medium tracking-tight sm:text-3xl">
                How can I help you today?
              </h1>
            </div>
          </AuiIf>
          <div className="mx-auto w-full max-w-(--thread-max-width) space-y-7 pb-8 font-main-content-body empty:hidden">
            <ThreadPrimitive.Messages>
              {({ message }) => (message.role === "user" ? <UserMessage /> : <AssistantMessage />)}
            </ThreadPrimitive.Messages>
          </div>
          <ThreadPrimitive.ViewportFooter
            className={cn(
              "relative mx-auto flex w-full max-w-(--thread-max-width) flex-col bg-surface-base pb-[max(1rem,env(safe-area-inset-bottom))] pt-3",
              !isEmpty && "sticky bottom-0 mt-auto rounded-t-2xl",
            )}
          >
            <div className="absolute -top-11 left-1/2 -translate-x-1/2">
              <ThreadPrimitive.ScrollToBottom asChild>
                <IconButton
                  aria-label="Scroll to latest message"
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
                Reconnecting to your reply…
              </p>
            )}
            {connection === "uncertain" || error ? (
              <div
                role="alert"
                className="mb-3 flex flex-wrap items-center gap-2 font-secondary-body text-content-secondary"
              >
                <span>{error ?? "Reply status could not be confirmed."}</span>
                <Button
                  size="sm"
                  prominence="secondary"
                  pending={checking}
                  onClick={() => void onCheck()}
                >
                  Check conversation
                </Button>
              </div>
            ) : null}
            <ComposerPrimitive.Root className="flex w-full flex-col gap-2 rounded-2xl border border-border-default bg-surface-raised p-2.5 shadow-sm transition-colors focus-within:border-border-strong">
              <ComposerPrimitive.Input
                aria-label="Message"
                placeholder="Send a message…"
                rows={1}
                maxLength={32000}
                className="max-h-48 min-h-12 w-full resize-none bg-transparent px-2.5 py-1.5 text-base leading-6 outline-none placeholder:text-content-muted"
              />
              <div className="flex items-center justify-between gap-3">
                {modelPicker}
                <AuiIf condition={(state) => !state.thread.isRunning}>
                  <ComposerPrimitive.Send asChild>
                    <IconButton aria-label="Send message" prominence="primary">
                      <ArrowUp />
                    </IconButton>
                  </ComposerPrimitive.Send>
                </AuiIf>
                <AuiIf condition={(state) => state.thread.isRunning}>
                  <IconButton
                    aria-label={stopping ? "Requesting stop" : "Stop reply"}
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
        </ThreadPrimitive.Viewport>
      </ThreadPrimitive.Root>
    </ChatSourcesWorkspace>
  );
}

function UserMessage() {
  return (
    <MessagePrimitive.Root className="flex justify-end">
      <div className="max-w-[90%] rounded-2xl bg-surface-sunken px-4 py-3 whitespace-pre-wrap [overflow-wrap:anywhere]">
        <MessagePrimitive.Parts />
      </div>
    </MessagePrimitive.Root>
  );
}

function AssistantMessage() {
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
          <p className="mt-2 font-secondary-body text-content-muted">Stopped</p>
        )}
        {serverStatus === "FAILED" && (
          <p role="status" className="mt-2 font-secondary-body text-content-secondary">
            Reply interrupted. Partial answer saved.
          </p>
        )}
        <ActionBarPrimitive.Root className="mt-3 flex items-center gap-1">
          <AuiIf
            condition={(state) =>
              state.message.parts.some(
                (part) => part.type === "text" && part.text.trim().length > 0,
              )
            }
          >
            <ActionBarPrimitive.Copy asChild>
              <IconButton
                aria-label="Copy answer"
                title="Copy answer"
                prominence="internal"
                size="sm"
              >
                <Copy />
              </IconButton>
            </ActionBarPrimitive.Copy>
          </AuiIf>
          <ChatSources />
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
