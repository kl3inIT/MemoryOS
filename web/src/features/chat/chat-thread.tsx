import {
  ActionBarPrimitive,
  AuiIf,
  ComposerPrimitive,
  MessagePrimitive,
  ThreadPrimitive,
  useAuiState,
} from "@assistant-ui/react";
import { MarkdownTextPrimitive, type CodeHeaderProps } from "@assistant-ui/react-markdown";
import { ArrowDown, ArrowUp, Check, Copy, Square } from "lucide-react";
import { useState, type ComponentProps } from "react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import type { ConnectionState } from "./chat-transport";
import "./chat.css";

export function ChatThread({
  connection,
  stopping,
  onStop,
  error,
  onCheck,
  checking,
}: {
  connection: ConnectionState;
  stopping: boolean;
  onStop: () => void;
  error?: string;
  onCheck: () => Promise<void>;
  checking: boolean;
}) {
  return (
    <ThreadPrimitive.Root className="flex h-full min-h-0 flex-col">
      <ThreadPrimitive.Viewport
        data-testid="chat-viewport"
        className="relative flex min-h-0 flex-1 flex-col overflow-y-auto px-4 pt-6 [scrollbar-gutter:stable] sm:px-8"
      >
        <AuiIf condition={(state) => state.thread.isEmpty}>
          <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col justify-center gap-3 pb-10">
            <h1 className="font-heading-h2">What can I help you with?</h1>
            <p className="text-content-secondary">
              Ask a question, explore an idea, or work through a problem.
            </p>
          </div>
        </AuiIf>
        <div className="mx-auto w-full max-w-3xl space-y-7 pb-8">
          <ThreadPrimitive.Messages>
            {({ message }) => (message.role === "user" ? <UserMessage /> : <AssistantMessage />)}
          </ThreadPrimitive.Messages>
        </div>
        <ThreadPrimitive.ViewportFooter className="sticky bottom-0 mx-auto mt-auto w-full max-w-3xl bg-surface-base pb-4 pt-3">
          <div className="absolute -top-10 right-0">
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
          <ComposerPrimitive.Root className="rounded-2xl border border-border-default bg-surface-base p-3 shadow-sm focus-within:ring-2 focus-within:ring-ring/30">
            <ComposerPrimitive.Input
              aria-label="Message"
              placeholder="Message MemoryOS…"
              rows={2}
              maxLength={32000}
              className="max-h-48 min-h-16 w-full resize-none bg-transparent font-main-ui-body outline-none placeholder:text-content-muted"
            />
            <div className="flex items-center justify-between gap-3">
              <span className="font-secondary-body text-content-muted">
                Shift + Enter for a new line
              </span>
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
      <MessagePrimitive.Parts components={{ Text: MarkdownText }} />
      <AuiIf
        condition={(state) =>
          state.message.status?.type === "running" && state.message.parts.length === 0
        }
      >
        <p role="status" className="text-content-muted">
          Thinking…
        </p>
      </AuiIf>
      {(serverStatus === "CANCELED" || canceled) && (
        <p className="mt-2 font-secondary-body text-content-muted">Stopped</p>
      )}
      {serverStatus === "FAILED" && (
        <p role="status" className="mt-2 font-secondary-body text-content-secondary">
          Reply interrupted. Partial answer saved.
        </p>
      )}
      <ActionBarPrimitive.Root className="mt-2 flex gap-1">
        <ActionBarPrimitive.Copy asChild>
          <IconButton aria-label="Copy answer" title="Copy answer" prominence="internal" size="sm">
            <Copy />
          </IconButton>
        </ActionBarPrimitive.Copy>
      </ActionBarPrimitive.Root>
    </MessagePrimitive.Root>
  );
}

function MarkdownText() {
  return (
    <MarkdownTextPrimitive
      className="chat-markdown"
      smooth={false}
      components={{ CodeHeader, a: MarkdownLink }}
    />
  );
}

function MarkdownLink({ href, children }: ComponentProps<"a">) {
  // Model output must not navigate into privileged application actions.
  const external = href && /^https?:\/\//i.test(href);
  return external ? (
    <a href={href} target="_blank" rel="noopener noreferrer">
      {children}
    </a>
  ) : (
    <span>{children}</span>
  );
}

function CodeHeader({ code, language }: CodeHeaderProps) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="flex items-center justify-between border-b border-border-subtle px-3 py-1 font-secondary-body text-content-secondary">
      <span>{language || "Code"}</span>
      <Button
        size="sm"
        prominence="internal"
        aria-label="Copy code"
        onClick={async () => {
          try {
            await navigator.clipboard.writeText(code);
            setCopied(true);
          } catch {
            setCopied(false);
          }
        }}
      >
        {copied ? <Check /> : <Copy />}
        {copied ? "Copied" : "Copy"}
      </Button>
    </div>
  );
}
